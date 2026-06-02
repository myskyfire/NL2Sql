package com.nl2sql.core.agent.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nl2sql.core.agent.planner.QueryPlan;
import com.nl2sql.core.agent.tools.ToolRegistry;
import com.nl2sql.core.agent.worker.Worker;
import com.nl2sql.core.tracing.TraceSpan;
import com.nl2sql.core.tracing.TracingContext;
import com.nl2sql.core.tracing.TracingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.*;

@Slf4j
@Component
public class WorkflowEngine {

    private final Map<String, Worker> workers;
    private final ToolRegistry toolRegistry;
    private final ObjectMapper jsonMapper = new ObjectMapper();

    @Autowired(required = false)
    private TracingService tracingService;

    @Autowired(required = false)
    private ApplicationEventPublisher eventPublisher;

    private WorkflowLoader workflowLoader;
    private WorkflowExpressionResolver expressionResolver;
    private WorkflowResultAssembler resultAssembler;
    private WorkflowTracingHelper tracingHelper;
    private WorkflowStepExecutor stepExecutor;

    public WorkflowEngine(List<Worker> workers, ToolRegistry toolRegistry) {
        this.workers = new HashMap<>();
        for (Worker w : workers) {
            this.workers.put(w.getWorkerType(), w);
            log.info("[WorkflowEngine] 注册 Worker: {}", w.getWorkerType());
        }
        this.toolRegistry = toolRegistry;
        log.info("[WorkflowEngine] ToolRegistry 已注入，可用 Tool 数: {}", toolRegistry.getToolNames().size());

        initializeComponents();
    }

    private void initializeComponents() {
        this.workflowLoader = new WorkflowLoader(jsonMapper);
        this.expressionResolver = new WorkflowExpressionResolver(jsonMapper);
        this.resultAssembler = new WorkflowResultAssembler(jsonMapper);
        this.tracingHelper = new WorkflowTracingHelper(tracingService);
        this.stepExecutor = new WorkflowStepExecutor(
                workers,
                toolRegistry,
                jsonMapper,
                expressionResolver,
                resultAssembler,
                eventPublisher
        ) {
            @Override
            public String executeFromSkillWorkflow(String skillName, Map<String, Object> params) {
                return WorkflowEngine.this.executeFromSkillWorkflow(skillName, params);
            }
        };
    }

    public String execute(QueryPlan plan, Long datasourceId, Long userId, String username, String userMessage) {
        log.info("[WorkflowEngine] 开始执行工作流，复杂度: {}", plan.getComplexity());

        TraceSpan workflowRun = tracingHelper.startWorkflowTrace("WorkflowEngine.execute",
                Map.of("complexity", plan.getComplexity() != null ? plan.getComplexity().name() : "unknown",
                       "datasourceId", datasourceId != null ? datasourceId.toString() : "null"),
                TracingContext.currentRunId());

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("question", userMessage);
        params.put("datasourceId", datasourceId);
        params.put("userId", userId);
        params.put("username", username);
        params.put("sessionId", com.nl2sql.common.context.UserContext.getSessionId());

        if (plan != null) {
            params.put("planComplexity", plan.getComplexity() != null ? plan.getComplexity().name() : "UNKNOWN");
            params.put("planChartNeeded", plan.getChart() != null && plan.getChart().isNeeded());
            params.put("planNeedSummary", plan.isNeedSummary());
            if (plan.getTables() != null && !plan.getTables().isEmpty()) {
                params.put("planTables", plan.getTables().stream()
                        .map(t -> t.getTableName())
                        .filter(Objects::nonNull)
                        .toList());
            }
            if (plan.getChart() != null) {
                params.put("planChartType", plan.getChart().getType());
            }
        }

        WorkflowDefinition workflow = workflowLoader.loadSkillWorkflow("execute_standard_query");
        if (workflow == null) {
            log.warn("[WorkflowEngine] 未找到 SKILL.md workflow，降级到简单流程");
            return executeSimplePipeline(plan, datasourceId, userId, username, userMessage);
        }

        log.info("[WorkflowEngine] 使用 SKILL.md workflow: {}", workflow.getName());

        WorkflowContext ctx = new WorkflowContext();
        ctx.setPlan(plan);
        ctx.setDatasourceId(datasourceId);
        ctx.setUserId(userId);
        ctx.setUsername(username);
        ctx.setUserMessage(userMessage);
        ctx.setSessionId(com.nl2sql.common.context.UserContext.getSessionId());
        ctx.getContextVars().putAll(params);

        return executeWorkflowDefinition(workflow, ctx, workflowRun);
    }

    public String executeFromSkillWorkflow(String skillName, Map<String, Object> params) {
        log.info("[WorkflowEngine] 从 Skill workflow 执行: {}", skillName);

        WorkflowDefinition workflow = workflowLoader.loadSkillWorkflow(skillName);
        if (workflow == null) {
            log.warn("[WorkflowEngine] 未找到 Skill {} 的 workflow 定义", skillName);
            return "{\"success\":false,\"error\":\"未找到Skill的workflow定义: " + skillName + "\"}";
        }

        log.info("[WorkflowEngine] 使用 Skill workflow: {}", workflow.getName());

        WorkflowContext ctx = new WorkflowContext();
        ctx.setPlan(null);
        ctx.setDatasourceId(params.get("datasourceId") != null ? ((Number) params.get("datasourceId")).longValue() : null);
        ctx.setUserId(params.get("userId") != null ? ((Number) params.get("userId")).longValue() : null);
        ctx.setUsername(params.get("username") != null ? params.get("username").toString() : null);
        ctx.setUserMessage(params.get("question") != null ? params.get("question").toString() : null);
        ctx.setSessionId(params.get("sessionId") != null ? params.get("sessionId").toString() : null);

        ctx.getContextVars().putAll(params);

        return executeWorkflowDefinition(workflow, ctx, null);
    }

    private String executeWorkflowDefinition(WorkflowDefinition workflow, WorkflowContext ctx,
                                              TraceSpan workflowRun) {
        Map<String, WorkflowStep> stepMap = new LinkedHashMap<>();
        for (WorkflowStep step : workflow.getSteps()) {
            stepMap.put(step.getId(), step);
        }

        try {
            String currentStepId = workflow.getSteps().get(0).getId();
            int maxSteps = workflow.getSteps().size() * 3;
            int stepCount = 0;

            while (currentStepId != null && stepCount < maxSteps) {
                stepCount++;
                WorkflowStep step = stepMap.get(currentStepId);

                if (step == null) {
                    log.error("[WorkflowEngine] 未找到步骤: {}", currentStepId);
                    return tracingHelper.endWorkflowTrace(workflowRun, "{\"success\":false,\"error\":\"工作流步骤未找到: " + currentStepId + "\"}");
                }

                log.info("[WorkflowEngine] 执行步骤: {} (type={}, action={})", step.getId(), step.getType(), step.getAction());

                String nextStepId = stepExecutor.executeStep(step, ctx, stepMap, tracingHelper);

                if (nextStepId == null && ("assemble".equals(step.getType()) || "respond".equals(step.getAction()) || "respond".equals(step.getType()))) {
                    return tracingHelper.endWorkflowTrace(workflowRun, ctx.getAssembleResult());
                }

                if (nextStepId == null && "fail".equals(step.getId())) {
                    return tracingHelper.endWorkflowTrace(workflowRun, "{\"success\":false,\"error\":\"工作流执行失败\"}");
                }

                if (nextStepId != null && nextStepId.startsWith("parallel:")) {
                    String[] branchIds = nextStepId.substring("parallel:".length()).split(",");
                    List<String> trimmedBranches = new ArrayList<>();
                    for (String b : branchIds) {
                        trimmedBranches.add(b.trim());
                    }
                    WorkflowStep parallelStep = new WorkflowStep();
                    parallelStep.setId("parallel_from_" + step.getId());
                    parallelStep.setType("parallel");
                    parallelStep.setBranches(trimmedBranches);
                    parallelStep.setOnNext(null);
                    String afterParallel = executeParallelStep(parallelStep, ctx, stepMap);
                    currentStepId = afterParallel;
                    continue;
                }

                currentStepId = nextStepId;
            }

            if (stepCount >= maxSteps) {
                log.error("[WorkflowEngine] 步骤执行次数超限，可能存在循环");
                return tracingHelper.endWorkflowTrace(workflowRun, "{\"success\":false,\"error\":\"工作流步骤执行次数超限\"}");
            }

            return tracingHelper.endWorkflowTrace(workflowRun, resultAssembler.assembleDefaultResult(ctx.getWorkerResults()));

        } catch (Exception e) {
            log.error("[WorkflowEngine] 工作流执行异常", e);
            tracingHelper.endWorkflowTraceWithError(workflowRun, e.getMessage());
            return "{\"success\":false,\"error\":\"工作流执行异常: " + e.getMessage() + "\"}";
        }
    }

    private String executeSimplePipeline(QueryPlan plan, Long datasourceId, Long userId, String username, String userMessage) {
        WorkflowContext ctx = new WorkflowContext();
        ctx.setPlan(plan);
        ctx.setDatasourceId(datasourceId);
        ctx.setUserId(userId);
        ctx.setUsername(username);
        ctx.setUserMessage(userMessage);

        Worker sqlWorker = workers.get("sql");
        if (sqlWorker != null) {
            Worker.WorkerContext workerContext = new Worker.WorkerContext();
            workerContext.setPlan(plan);
            workerContext.setDatasourceId(datasourceId);
            workerContext.setUserId(userId);
            workerContext.setUsername(username);
            workerContext.setUserMessage(userMessage);

            Worker.WorkerResult result = sqlWorker.execute(workerContext);
            ctx.getWorkerResults().put("sql", result);
            ctx.getPreviousResults().put("sql", result);

            return resultAssembler.assembleDefaultResult(ctx.getWorkerResults());
        }

        return "{\"success\":false,\"error\":\"未找到SQL Worker\"}";
    }

    private String executeParallelStep(WorkflowStep step, WorkflowContext ctx,
                                       Map<String, WorkflowStep> stepMap) {
        return stepExecutor.executeStep(step, ctx, stepMap, tracingHelper);
    }
}
