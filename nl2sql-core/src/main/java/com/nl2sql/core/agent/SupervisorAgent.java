package com.nl2sql.core.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nl2sql.core.agent.engine.WorkflowEngine;
import com.nl2sql.core.agent.planner.PlanValidator;
import com.nl2sql.core.agent.planner.QueryPlan;
import com.nl2sql.core.agent.planner.PlannerAgent;
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
    private DataExplorationAgent explorationAgent;
    private PlanExecutor planExecutor;

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

        this.explorationAgent = new DataExplorationAgent(llmService, toolRegistry);
        this.planExecutor = new PlanExecutor(toolRegistry);

        log.info("[SupervisorAgent] initialized with 3 execution modes: DIRECT(Workflow), PLAN_AND_EXECUTE(PlanExecutor), REACT(Explore)");
        log.info("[SupervisorAgent] registered Workers: {}", this.workers.keySet());
    }

    public String execute(String userMessage, Long datasourceId,
                         List<Map<String, Object>> historyMessages) {
        Long userId = com.nl2sql.common.context.UserContext.getUserId();
        String username = com.nl2sql.common.context.UserContext.getUsername();

        log.info("[SupervisorAgent] execute: message={}, datasourceId={}, userId={}",
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
            log.info("[SupervisorAgent] datasourceId is null, clarifying");
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
                            log.info("[SupervisorAgent] auto-selected datasource: ID={}, re-executing", recommendedDsId);
                            return execute(userMessage, recommendedDsId, historyMessages);
                        }
                    }
                } catch (Exception e) {
                    log.warn("[SupervisorAgent] failed to parse clarification response: {}", e.getMessage());
                }
                
                return clarificationResult;
            }
            return endTrace(rootRun, "{\"success\":false,\"error\":\"datasourceId is null and clarify_datasource tool not registered\"}");
        }

        RoutingResult routing = skillRouter.route(userMessage, datasourceId);
        log.info("[SupervisorAgent] routing result: strategy={}, skills={}, reason={}",
            routing.getStrategy(), routing.getRecommendedSkills(), routing.getReason());

        String result;
        switch (routing.getStrategy()) {
            case DIRECT:
                result = executeDirect(routing.getRecommendedSkills().get(0),
                    datasourceId, userId, username, userMessage);
                break;
            case PLAN_AND_EXECUTE:
                result = executeWithPlan(userMessage, datasourceId, userId, username, routing);
                break;
            case REACT:
                result = executeWithReAct(userMessage, datasourceId);
                break;
            default:
                log.info("[SupervisorAgent] unknown strategy, fallback to DIRECT");
                result = executeDirect("execute_standard_query",
                    datasourceId, userId, username, userMessage);
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
            
            if (result != null) {
                try {
                    Map<String, Object> resultMap = objectMapper.readValue(result, Map.class);
                    resultMap.put("runId", rootRun.getId().toString());
                    result = objectMapper.writeValueAsString(resultMap);
                } catch (Exception e) {
                    log.debug("[SupervisorAgent] failed to inject runId: {}", e.getMessage());
                }
            }
        }
        return result;
    }

    /**
     * Mode 1: DIRECT - SKILL.md Workflow (deterministic orchestration)
     */
    private String executeDirect(String skillName, Long datasourceId,
                                  Long userId, String username, String userMessage) {
        log.info("[SupervisorAgent] [DIRECT] executing skill: {}", skillName);

        if ("clarify_datasource".equals(skillName)) {
            if (datasourceClarificationTool != null) {
                return datasourceClarificationTool.clarifyDatasource(userMessage);
            }
            return "{\"success\":false,\"error\":\"clarify_datasource tool not registered\"}";
        }

        if ("summarize_result".equals(skillName) || "generate_chart".equals(skillName)) {
            return executeDirectSkill(skillName, datasourceId, userId, username, userMessage);
        }

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("question", userMessage);
        params.put("datasourceId", datasourceId);
        params.put("userId", userId);
        params.put("username", username);
        params.put("sessionId", com.nl2sql.common.context.UserContext.getSessionId());

        try {
            String result = workflowEngine.executeFromSkillWorkflow(skillName, params);
            if (result != null && !result.contains("\"error\":\"未找到Skill的workflow定义")) {
                log.info("[SupervisorAgent] [DIRECT] SKILL.md workflow success: {}", skillName);
                return result;
            }
        } catch (Exception e) {
            log.warn("[SupervisorAgent] [DIRECT] SKILL.md workflow failed, falling back to Worker: {}", e.getMessage());
        }

        log.info("[SupervisorAgent] [DIRECT] falling back to Worker: {}", skillName);

        if ("execute_standard_query".equals(skillName)) {
            Worker sqlWorker = workers.get("sql");
            if (sqlWorker == null) {
                return "{\"success\":false,\"error\":\"sql Worker not registered\"}";
            }

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
                log.error("[SupervisorAgent] SQL Worker result serialization failed", e);
                return "{\"success\":true,\"raw\":\"" + result.getRawOutput() + "\"}";
            }
        }

        return executeDirectSkill(skillName, datasourceId, userId, username, userMessage);
    }

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
                        log.info("[SupervisorAgent] got SQL from context: {}", 
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
                                log.info("[SupervisorAgent] got data from cache: {} rows, {} chars", 
                                    cached.getData().size(), dataJson.length());
                            }
                        } catch (Exception e) {
                            log.warn("[SupervisorAgent] failed to read data from cache", e);
                        }
                    }
                    args.put("data", dataJson);
                    
                    Object result = toolRegistry.callTool("summarize_result", args);
                    if (result instanceof String) {
                        return (String) result;
                    }
                    return objectMapper.writeValueAsString(result);
                } catch (Exception e) {
                    log.error("[SupervisorAgent] summarize_result tool call failed", e);
                    return "{\"success\":false,\"error\":\"Summary generation failed: " + e.getMessage() + "\"}";
                }
            }
            return "{\"success\":false,\"error\":\"summarize_result tool not registered\"}";
        }

        if ("generate_chart".equals(skillName)) {
            if (toolRegistry.hasTool("detectAndGenerateChart")) {
                try {
                    Map<String, Object> args = new LinkedHashMap<>();
                    args.put("question", userMessage);
                    
                    String sql = "";
                    if (sessionContextManager != null) {
                        sql = sessionContextManager.getCurrentSQL();
                    }
                    
                    List<Map<String, Object>> data = new ArrayList<>();
                    if (queryCacheService != null && sql != null && !sql.trim().isEmpty()) {
                        try {
                            com.nl2sql.core.cache.QueryCacheService.CachedResult cached = 
                                queryCacheService.getFromCache(sql);
                            if (cached != null && cached.getData() != null) {
                                data = cached.getData();
                            }
                        } catch (Exception e) {
                            log.warn("[SupervisorAgent] failed to read chart data from cache", e);
                        }
                    }
                    args.put("data", data);
                    
                    Object result = toolRegistry.callTool("detectAndGenerateChart", args);
                    if (result instanceof String) {
                        return (String) result;
                    }
                    return objectMapper.writeValueAsString(result);
                } catch (Exception e) {
                    log.error("[SupervisorAgent] generate_chart tool call failed", e);
                    return "{\"success\":false,\"error\":\"Chart generation failed: " + e.getMessage() + "\"}";
                }
            }
            return "{\"success\":false,\"error\":\"generate_chart tool not registered\"}";
        }

        return "{\"success\":false,\"error\":\"Unknown skill: " + skillName + "\"}";
    }

    /**
     * Mode 2: PLAN_AND_EXECUTE - LLM generates plan, PlanExecutor dynamically orchestrates tools
     *
     * Key differences from DIRECT (SKILL.md Workflow):
     * - Uses QueryPlan's structured output to guide execution
     * - Plan-aware: table hints, chart type, summary needs from plan
     * - Multi-step SQL execution for COMPLEX queries
     * - Dynamic tool orchestration based on plan content
     */
    private String executeWithPlan(String userMessage, Long datasourceId,
                                    Long userId, String username,
                                    RoutingResult routing) {
        log.info("[SupervisorAgent] [PLAN_AND_EXECUTE] generating QueryPlan...");

        QueryPlan plan;
        try {
            plan = plannerAgent.plan(userMessage, datasourceId);
        } catch (Exception e) {
            log.error("[SupervisorAgent] [PLAN_AND_EXECUTE] plan generation failed, falling back to DIRECT", e);
            return executeDirect("execute_standard_query", datasourceId, userId, username, userMessage);
        }

        if (plan.getComplexity() == QueryPlan.ComplexityLevel.SIMPLE) {
            log.info("[SupervisorAgent] [PLAN_AND_EXECUTE] plan is SIMPLE, falling back to DIRECT");
            return executeDirect("execute_standard_query", datasourceId, userId, username, userMessage);
        }

        PlanValidator planValidator = new PlanValidator(toolRegistry);
        PlanValidator.ValidationResult planValidation = planValidator.validate(plan, datasourceId);
        if (!planValidation.isValid()) {
            log.warn("[SupervisorAgent] [PLAN_AND_EXECUTE] Plan validation REJECTED: {}, falling back to DIRECT",
                planValidation.getRejectReason());
            return executeDirect("execute_standard_query", datasourceId, userId, username, userMessage);
        }
        if (planValidation.isModified()) {
            log.info("[SupervisorAgent] [PLAN_AND_EXECUTE] Plan auto-corrected: {}", planValidation.getCorrections());
        }
        if (!planValidation.getWarnings().isEmpty()) {
            log.warn("[SupervisorAgent] [PLAN_AND_EXECUTE] Plan validation warnings: {}", planValidation.getWarnings());
        }

        log.info("[SupervisorAgent] [PLAN_AND_EXECUTE] plan complexity={}, tables={}, steps={}, using PlanExecutor",
            plan.getComplexity(),
            plan.getTables() != null ? plan.getTables().size() : 0,
            plan.getSqlSteps() != null ? plan.getSqlSteps().size() : 0);

        return planExecutor.execute(plan, datasourceId, userId, username, userMessage);
    }

    /**
     * Mode 3: REACT - DataExplorationAgent handles open-ended exploration
     */
    private String executeWithReAct(String userMessage, Long datasourceId) {
        log.info("[SupervisorAgent] [REACT] starting data exploration");
        return explorationAgent.explore(userMessage, datasourceId);
    }

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
