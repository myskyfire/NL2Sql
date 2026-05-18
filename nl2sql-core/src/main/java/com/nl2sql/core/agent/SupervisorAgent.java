package com.nl2sql.core.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nl2sql.core.agent.engine.WorkflowEngine;
import com.nl2sql.core.agent.intent.IntentClassifier;
import com.nl2sql.core.agent.planner.PlannerAgent;
import com.nl2sql.core.agent.planner.QueryPlan;
import com.nl2sql.core.agent.routing.RoutingResult;
import com.nl2sql.core.agent.routing.SkillRouter;
import com.nl2sql.core.agent.tools.DatasourceClarificationTool;
import com.nl2sql.core.agent.tools.ToolRegistry;
import com.nl2sql.core.agent.worker.Worker;
import com.nl2sql.core.cache.QueryCacheService;
import com.nl2sql.core.llm.LLMService;
import com.nl2sql.core.service.SessionContextManager;
import com.nl2sql.core.tracing.TraceSpan;
import com.nl2sql.core.tracing.TracingService;
import com.nl2sql.core.tracing.TracingContext;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.LinkedHashMap;

/**
 * Supervisor Agent - 替换 ReActAgent，采用 Plan-and-Execute + Multi-Agent 架构
 *
 * 职责：
 * 1. 意图分类 + 路由分发
 * 2. 简单查询直接执行（保留现有DIRECT路径）
 * 3. 复杂查询生成Plan，交给WorkflowEngine编排执行
 */
@Slf4j
public class SupervisorAgent {

    private final LLMService llmService;
    private final SkillRouter skillRouter;
    private final PlannerAgent plannerAgent;
    private final WorkflowEngine workflowEngine;
    private final DatasourceClarificationTool datasourceClarificationTool;
    private final TracingService tracingService;
    private final ToolRegistry toolRegistry;
    private final SessionContextManager sessionContextManager;
    private final QueryCacheService queryCacheService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final Map<String, Worker> workers = new HashMap<>();

    public SupervisorAgent(
            LLMService llmService,
            SkillRouter skillRouter,
            PlannerAgent plannerAgent,
            WorkflowEngine workflowEngine,
            DatasourceClarificationTool datasourceClarificationTool,
            TracingService tracingService,
            ToolRegistry toolRegistry,
            SessionContextManager sessionContextManager,
            QueryCacheService queryCacheService,
            List<Worker> workers
    ) {
        this.llmService = llmService;
        this.skillRouter = skillRouter;
        this.plannerAgent = plannerAgent;
        this.workflowEngine = workflowEngine;
        this.datasourceClarificationTool = datasourceClarificationTool;
        this.tracingService = tracingService;
        this.toolRegistry = toolRegistry;
        this.sessionContextManager = sessionContextManager;
        this.queryCacheService = queryCacheService;

        for (Worker w : workers) {
            this.workers.put(w.getWorkerType(), w);
        }

        log.info("[SupervisorAgent] 初始化完成，已注册 Workers: {}", this.workers.keySet());
    }

    /**
     * 执行用户请求（替换 ReActAgent.execute）
     *
     * @param userMessage      用户原始消息
     * @param datasourceId     数据源ID（可能为null）
     * @param historyMessages  历史消息（暂不使用）
     * @return 执行结果 JSON
     */
    public String execute(String userMessage, Long datasourceId,
                         List<Map<String, Object>> historyMessages) {
        Long userId = com.nl2sql.common.context.UserContext.getUserId();
        String username = com.nl2sql.common.context.UserContext.getUsername();

        log.info("[SupervisorAgent] 开始执行，用户消息: {}, datasourceId={}, userId={}",
            userMessage, datasourceId, userId);

        TraceSpan rootRun = null;
        if (tracingService != null && tracingService.isEnabled()) {
            rootRun = tracingService.traceChain("SupervisorAgent.execute",
                Map.of("userMessage", userMessage != null ? userMessage.substring(0, Math.min(200, userMessage.length())) : "",
                       "datasourceId", datasourceId != null ? datasourceId.toString() : "null"),
                null);
            TracingContext.setRunId(rootRun != null ? rootRun.getId() : null);
        }

        try {
        if (datasourceId == null) {
            log.info("[SupervisorAgent] 数据源为空，先调用澄清");
            if (datasourceClarificationTool != null) {
                String clarificationResult = datasourceClarificationTool.clarifyDatasource(userMessage);
                
                try {
                    Map<String, Object> result = objectMapper.readValue(clarificationResult, Map.class);
                    Map<String, Object> clarification = (Map<String, Object>) result.get("clarification");
                    
                    if (clarification != null) {
                        Boolean autoExecuted = com.nl2sql.common.util.BooleanUtils.toBoolean(clarification.get("autoExecuted"));
                        Long recommendedDsId = clarification.get("recommendedDatasourceId") != null ?
                            ((Number) clarification.get("recommendedDatasourceId")).longValue() : null;
                        
                        if (Boolean.TRUE.equals(autoExecuted) && recommendedDsId != null) {
                            log.info("[SupervisorAgent] ✅ 数据源自动选择: ID={}, 重新执行", recommendedDsId);
                            return execute(userMessage, recommendedDsId, historyMessages);
                        }
                    }
                } catch (Exception e) {
                    log.warn("[SupervisorAgent] 解析澄清响应失败: {}", e.getMessage());
                }
                
                return clarificationResult;
            }
            return endTrace(rootRun, "{\"success\":false,\"error\":\"数据源为空，clarify_datasource工具未注册\"}");
        }

        RoutingResult routing = skillRouter.route(userMessage, datasourceId);
        log.info("[SupervisorAgent] 路由结果: strategy={}, skills={}, reason={}",
            routing.getStrategy(), routing.getRecommendedSkills(), routing.getReason());

        String result;
        switch (routing.getStrategy()) {
            case DIRECT:
                result = executeDirect(routing.getRecommendedSkills().get(0),
                    datasourceId, userId, username, userMessage);
                break;
            case LLM_ASSISTED:
                result = executeWithPlan(userMessage, datasourceId, userId, username, routing);
                break;
            case FALLBACK:
            default:
                log.info("[SupervisorAgent] 降级模式，使用Plan执行");
                result = executeWithPlan(userMessage, datasourceId, userId, username, routing);
                break;
        }

        return endTrace(rootRun, result);
        } catch (Exception e) {
            if (tracingService != null && rootRun != null) {
                tracingService.endRun(rootRun, null, e.getMessage());
            }
            throw e;
        } finally {
            TracingContext.clear();
        }
    }

    private String endTrace(TraceSpan rootRun, String result) {
        if (tracingService != null && rootRun != null) {
            Map<String, Object> outputs = new LinkedHashMap<>();
            outputs.put("resultLength", result != null ? result.length() : 0);
            tracingService.endRun(rootRun, outputs, null);
            
            // 将 runId 注入到响应中，供前端反馈使用
            if (result != null) {
                try {
                    Map<String, Object> resultMap = objectMapper.readValue(result, Map.class);
                    resultMap.put("runId", rootRun.getId().toString());
                    result = objectMapper.writeValueAsString(resultMap);
                } catch (Exception e) {
                    log.debug("[LangSmith] 注入 runId 失败: {}", e.getMessage());
                }
            }
        }
        return result;
    }

    /**
     * 直接执行（简单查询，跳过Plan）
     * 优先使用 SKILL.md 中的 workflow 定义，如果无 workflow 则降级到 Worker
     */
    private String executeDirect(String skillName, Long datasourceId,
                                  Long userId, String username, String userMessage) {
        log.info("[SupervisorAgent] 直接执行: {}", skillName);

        // 特殊处理：数据源澄清
        if ("clarify_datasource".equals(skillName)) {
            if (datasourceClarificationTool != null) {
                return datasourceClarificationTool.clarifyDatasource(userMessage);
            }
            return "{\"success\":false,\"error\":\"clarify_datasource工具未注册\"}";
        }

        // ✅ 总结/图表等已有上下文的 skill 直接走硬编码，不走 SKILL.md workflow（避免重复查询）
        if ("summarize_result".equals(skillName) || "generate_chart".equals(skillName)) {
            return executeDirectSkill(skillName, datasourceId, userId, username, userMessage);
        }

        // ✅ 优先尝试从 SKILL.md 的 workflow 定义执行
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("question", userMessage);
        params.put("datasourceId", datasourceId);
        params.put("userId", userId);
        params.put("username", username);
        params.put("sessionId", com.nl2sql.common.context.UserContext.getSessionId());

        try {
            String result = workflowEngine.executeFromSkillWorkflow(skillName, params);
            if (result != null && !result.contains("\"error\":\"未找到Skill的workflow定义")) {
                log.info("[SupervisorAgent] ✅ 通过 SKILL.md workflow 执行成功: {}", skillName);
                return result;
            }
        } catch (Exception e) {
            log.warn("[SupervisorAgent] SKILL.md workflow 执行失败，降级到 Worker: {}", e.getMessage());
        }

        // 降级：通过 Worker 执行
        log.info("[SupervisorAgent] 降级到 Worker 执行: {}", skillName);

        if ("execute_standard_query".equals(skillName)) {
            Worker sqlWorker = workers.get("sql");
            if (sqlWorker == null) {
                return "{\"success\":false,\"error\":\"sql Worker未注册\"}";
            }

            // 构造最简单的 QueryPlan
            QueryPlan plan = buildSimplePlan(userMessage, datasourceId);

            Worker.WorkerContext context = new Worker.WorkerContext();
            context.setPlan(plan);
            context.setDatasourceId(datasourceId);
            context.setUserId(userId);
            context.setUsername(username);
            context.setUserMessage(userMessage);

            Worker.WorkerResult result = sqlWorker.execute(context);
            if (!result.isSuccess()) {
                return "{\"success\":false,\"error\":\"" + result.getErrorMessage() + "\"}";
            }

            try {
                return objectMapper.writeValueAsString(result.getData());
            } catch (Exception e) {
                log.error("[SupervisorAgent] SQL Worker结果序列化失败", e);
                return "{\"success\":true,\"raw\":\"" + result.getRawOutput() + "\"}";
            }
        }

        if ("summarize_result".equals(skillName)) {
            return executeDirectSkill(skillName, datasourceId, userId, username, userMessage);
        }

        return "{\"success\":false,\"error\":\"未知的Skill: " + skillName + "\"}";
    }

    /**
     * 直接执行 skill（不走 SKILL.md workflow，已有上下文数据）
     */
    private String executeDirectSkill(String skillName, Long datasourceId,
                                       Long userId, String username, String userMessage) {
        if ("summarize_result".equals(skillName)) {
            if (toolRegistry.hasTool("summarize_result")) {
                try {
                    Map<String, Object> args = new LinkedHashMap<>();
                    args.put("userQuery", userMessage);
                    
                    String sql = "";
                    if (sessionContextManager != null) {
                        sql = sessionContextManager.getCurrentSQL();
                        log.info("[SupervisorAgent] 从上下文获取 SQL: {}", 
                            sql != null ? sql.substring(0, Math.min(50, sql.length())) : "null");
                    }
                    args.put("sql", sql != null ? sql : "");
                    
                    String dataJson = "";
                    if (queryCacheService != null && sql != null && !sql.trim().isEmpty()) {
                        try {
                            com.nl2sql.core.cache.QueryCacheService.CachedResult cached = 
                                queryCacheService.getFromCache(sql);
                            if (cached != null && cached.getData() != null && !cached.getData().isEmpty()) {
                                dataJson = objectMapper.writeValueAsString(cached.getData());
                                log.info("[SupervisorAgent] 从缓存获取数据: {} rows, {} chars", 
                                    cached.getData().size(), dataJson.length());
                            } else {
                                log.warn("[SupervisorAgent] 缓存未命中: sql={}", sql.substring(0, Math.min(50, sql.length())));
                            }
                        } catch (Exception e) {
                            log.warn("[SupervisorAgent] 从缓存读取数据失败", e);
                        }
                    }
                    args.put("dataJson", dataJson);
                    
                    Object result = toolRegistry.callTool("summarize_result", args);
                    if (result instanceof String) {
                        return (String) result;
                    }
                    return objectMapper.writeValueAsString(result);
                } catch (Exception e) {
                    log.error("[SupervisorAgent] summarize_result Tool调用失败", e);
                    return "{\"success\":false,\"error\":\"总结生成失败: " + e.getMessage() + "\"}";
                }
            }
            return "{\"success\":false,\"error\":\"summarize_result Tool未注册\"}";
        }

        if ("generate_chart".equals(skillName)) {
            if (toolRegistry.hasTool("detectAndGenerateChart")) {
                try {
                    Map<String, Object> args = new LinkedHashMap<>();
                    args.put("question", userMessage);
                    
                    String sql = "";
                    if (sessionContextManager != null) {
                        sql = sessionContextManager.getCurrentSQL();
                        log.info("[SupervisorAgent] 从上下文获取 SQL: {}", 
                            sql != null ? sql.substring(0, Math.min(50, sql.length())) : "null");
                    }
                    
                    List<Map<String, Object>> data = new ArrayList<>();
                    if (queryCacheService != null && sql != null && !sql.trim().isEmpty()) {
                        try {
                            com.nl2sql.core.cache.QueryCacheService.CachedResult cached = 
                                queryCacheService.getFromCache(sql);
                            if (cached != null && cached.getData() != null) {
                                data = cached.getData();
                                log.info("[SupervisorAgent] 从缓存获取图表数据: {} rows", data.size());
                            }
                        } catch (Exception e) {
                            log.warn("[SupervisorAgent] 从缓存读取图表数据失败", e);
                        }
                    }
                    args.put("data", data);
                    
                    Object result = toolRegistry.callTool("detectAndGenerateChart", args);
                    if (result instanceof String) {
                        return (String) result;
                    }
                    return objectMapper.writeValueAsString(result);
                } catch (Exception e) {
                    log.error("[SupervisorAgent] generate_chart Tool调用失败", e);
                    return "{\"success\":false,\"error\":\"图表生成失败: " + e.getMessage() + "\"}";
                }
            }
            return "{\"success\":false,\"error\":\"generate_chart Tool未注册\"}";
        }

        return "{\"success\":false,\"error\":\"未知的Skill: " + skillName + "\"}";
    }

    /**
     * 使用 Plan 执行（中等/复杂查询）
     * ✅ 前置条件: datasourceId 已在 execute() 中确定
     */
    private String executeWithPlan(String userMessage, Long datasourceId,
                                    Long userId, String username,
                                    RoutingResult routing) {
        log.info("[SupervisorAgent] 生成 QueryPlan...");

        // 生成 Plan
        QueryPlan plan;
        try {
            plan = plannerAgent.plan(userMessage, datasourceId);
        } catch (Exception e) {
            log.error("[SupervisorAgent] Plan 生成失败，降级到直接执行", e);
            return executeDirect("execute_standard_query", datasourceId, userId, username, userMessage);
        }

        // 简单 Plan：优先尝试 SKILL.md workflow，降级到 Worker
        if (plan.getComplexity() == QueryPlan.ComplexityLevel.SIMPLE) {
            log.info("[SupervisorAgent] Plan 评估为 SIMPLE，优先尝试 SKILL.md workflow");
            return executeDirect("execute_standard_query", datasourceId, userId, username, userMessage);
        }

        // 复杂 Plan 交给 WorkflowEngine
        log.info("[SupervisorAgent] Plan 评估为 {}，使用 WorkflowEngine", plan.getComplexity());
        return workflowEngine.execute(plan, datasourceId, userId, username, userMessage);
    }

    /**
     * 构造简单查询的 QueryPlan
     */
    private QueryPlan buildSimplePlan(String userMessage, Long datasourceId) {
        QueryPlan plan = new QueryPlan();
        plan.setUserQuestion(userMessage);
        plan.setComplexity(QueryPlan.ComplexityLevel.SIMPLE);
        plan.setNeedSummary(false);

        if (datasourceId != null) {
            QueryPlan.DatasourceInfo ds = new QueryPlan.DatasourceInfo();
            ds.setId(datasourceId);
            plan.setDatasource(ds);
        }

        QueryPlan.SqlStep step = new QueryPlan.SqlStep();
        step.setOrder(1);
        step.setDescription(userMessage);
        plan.setSqlSteps(List.of(step));

        QueryPlan.ChartPlan chart = new QueryPlan.ChartPlan();
        chart.setNeeded(false);
        plan.setChart(chart);

        return plan;
    }
}
