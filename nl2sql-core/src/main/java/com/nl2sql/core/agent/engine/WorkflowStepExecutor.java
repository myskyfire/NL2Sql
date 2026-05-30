package com.nl2sql.core.agent.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nl2sql.core.agent.tools.ToolRegistry;
import com.nl2sql.core.agent.worker.Worker;
import com.nl2sql.core.tracing.TraceSpan;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;

import java.util.*;
import java.util.concurrent.*;

@Slf4j
public class WorkflowStepExecutor {

    private final Map<String, Worker> workers;
    private final ToolRegistry toolRegistry;
    private final ObjectMapper jsonMapper;
    private final WorkflowExpressionResolver expressionResolver;
    private final WorkflowResultAssembler resultAssembler;
    private final ApplicationEventPublisher eventPublisher;

    public WorkflowStepExecutor(
            Map<String, Worker> workers,
            ToolRegistry toolRegistry,
            ObjectMapper jsonMapper,
            WorkflowExpressionResolver expressionResolver,
            WorkflowResultAssembler resultAssembler,
            ApplicationEventPublisher eventPublisher) {
        this.workers = workers;
        this.toolRegistry = toolRegistry;
        this.jsonMapper = jsonMapper;
        this.expressionResolver = expressionResolver;
        this.resultAssembler = resultAssembler;
        this.eventPublisher = eventPublisher;
    }

    public String executeStep(WorkflowStep step, WorkflowContext ctx,
                              Map<String, WorkflowStep> stepMap,
                              WorkflowTracingHelper tracingHelper) {
        TraceSpan stepRun = tracingHelper.startStepTrace(step);
        try {
            String result = doExecuteStep(step, ctx, stepMap);
            tracingHelper.endStepTrace(stepRun, result, null);
            return result;
        } catch (Exception e) {
            tracingHelper.endStepTrace(stepRun, null, e.getMessage());
            throw e;
        }
    }

    private String doExecuteStep(WorkflowStep step, WorkflowContext ctx,
                                 Map<String, WorkflowStep> stepMap) {
        String action = step.getAction();
        String type = step.getType();

        if ("call_tool".equals(action) || "call_tool".equals(type)) {
            return executeCallToolStep(step, ctx);
        }

        if ("respond".equals(action) || "respond".equals(type)) {
            if (step.getCondition() != null) {
                boolean conditionMet = expressionResolver.evaluateCondition(step.getCondition(), ctx);
                if (!conditionMet) {
                    log.info("[WorkflowStepExecutor] respond 步骤 {} 条件不满足，跳过", step.getId());
                    if (step.getOnConditionFalse() != null && !step.getOnConditionFalse().isEmpty()) {
                        return step.getOnConditionFalse();
                    }
                    return resolveNextStep(step, ctx);
                }
            }
            executeRespondStep(step, ctx);
            return null;
        }

        switch (type != null ? type : "") {
            case "skip":
                return resolveNextStep(step, ctx);

            case "worker":
                return executeWorkerStep(step, ctx, stepMap);

            case "condition":
                return executeConditionStep(step, ctx);

            case "assemble":
                executeAssembleStep(step, ctx);
                return null;

            case "parallel":
                return executeParallelStep(step, ctx, stepMap);

            case "call_tool":
                return executeCallToolStep(step, ctx);

            case "call_workflow":
                return executeCallWorkflowStep(step, ctx);

            case "respond":
                if (step.getCondition() != null) {
                    boolean conditionMet = expressionResolver.evaluateCondition(step.getCondition(), ctx);
                    if (!conditionMet) {
                        log.info("[WorkflowStepExecutor] respond 步骤 {} 条件不满足，跳过", step.getId());
                        if (step.getOnConditionFalse() != null && !step.getOnConditionFalse().isEmpty()) {
                            return step.getOnConditionFalse();
                        }
                        return resolveNextStep(step, ctx);
                    }
                }
                executeRespondStep(step, ctx);
                return null;

            default:
                log.warn("[WorkflowStepExecutor] 未知步骤类型: {}, action: {}", type, action);
                return resolveNextStep(step, ctx);
        }
    }

    private String executeWorkerStep(WorkflowStep step, WorkflowContext ctx,
                                     Map<String, WorkflowStep> stepMap) {
        Worker worker = workers.get(step.getWorker());
        if (worker == null) {
            log.warn("[WorkflowStepExecutor] 未找到 Worker: {}，跳过", step.getWorker());
            return resolveNextStep(step, ctx);
        }

        Worker.WorkerContext workerContext = buildWorkerContext(ctx);

        if (step.getInput() != null) {
            injectInputToContext(step.getInput(), workerContext, ctx);
        }

        Worker.WorkerResult result = executeWithRetry(worker, workerContext, step);
        ctx.workerResults.put(step.getId(), result);
        ctx.previousResults.put(step.getId(), result);
        ctx.previousResults.put(worker.getWorkerType(), result);

        if (result.isWaitingForApproval()) {
            log.info("[WorkflowStepExecutor] ⚠️ Worker {} 需要人工确认", worker.getWorkerType());
            return result.getRawOutput();
        }

        if (!result.isSuccess() && !result.isSkipped()) {
            log.error("[WorkflowStepExecutor] 步骤 {} 执行失败: {}", step.getId(), result.getErrorMessage());
            if (step.isOnErrorFail()) {
                return null;
            }
        }

        return resolveNextStep(step, ctx);
    }

    private String executeConditionStep(WorkflowStep step, WorkflowContext ctx) {
        boolean condition = expressionResolver.evaluateCondition(step.getCondition(), ctx);

        if (condition) {
            if (step.getOnTrue() != null) {
                return step.getOnTrue();
            }
            return resolveNextStep(step, ctx);
        } else {
            if (step.getOnFalse() != null) {
                return step.getOnFalse();
            }
            return resolveNextStep(step, ctx);
        }
    }

    private void executeAssembleStep(WorkflowStep step, WorkflowContext ctx) {
        try {
            Map<String, Object> response;
            if (!ctx.toolResults.isEmpty()) {
                response = resultAssembler.buildFrontendCompatibleResponseFromTools(ctx.toolResults, ctx.contextVars);
            } else {
                response = resultAssembler.buildFrontendCompatibleResponse(ctx.workerResults);
            }
            ctx.assembleResult = jsonMapper.writeValueAsString(response);
        } catch (Exception e) {
            log.error("[WorkflowStepExecutor] 组装结果失败", e);
            ctx.assembleResult = "{\"success\":false,\"error\":\"结果组装失败\"}";
        }
    }

    @SuppressWarnings("unchecked")
    private String executeCallToolStep(WorkflowStep step, WorkflowContext ctx) {
        String toolName = step.getTool();
        if (toolName == null || toolName.isEmpty()) {
            log.error("[WorkflowStepExecutor] call_tool 步骤缺少 tool 字段: {}", step.getId());
            return resolveNextStep(step, ctx);
        }

        if (!toolRegistry.hasTool(toolName)) {
            log.error("[WorkflowStepExecutor] Tool 未注册: {}", toolName);
            ctx.toolResults.put(step.getId(), Map.of("success", false, "error", "Tool未注册: " + toolName));
            if (step.getOutputVar() != null) {
                ctx.contextVars.put(step.getOutputVar(), Map.of("success", false, "error", "Tool未注册: " + toolName));
            }
            return resolveNextStep(step, ctx);
        }

        if (step.getCondition() != null) {
            boolean conditionMet = expressionResolver.evaluateCondition(step.getCondition(), ctx);
            if (!conditionMet) {
                log.info("[WorkflowStepExecutor] call_tool 步骤 {} 条件不满足，跳过", step.getId());
                if (step.getOnConditionFalse() != null && !step.getOnConditionFalse().isEmpty()) {
                    return step.getOnConditionFalse();
                }
                return resolveNextStep(step, ctx);
            }
        }

        Map<String, Object> resolvedInput = resolveToolInput(step.getToolInput(), ctx);

        log.info("[WorkflowStepExecutor] 调用 Tool: {} (步骤: {}), 参数: {}", toolName, step.getId(), resolvedInput.keySet());
        log.debug("[WorkflowStepExecutor] Tool 参数详情: {}", resolvedInput);

        publishProgress(ctx, step.getId(), getProgressMessage(step.getId()));

        try {
            Object result = toolRegistry.callTool(toolName, resolvedInput);

            Object parsedResult = result;
            if (result instanceof String) {
                try {
                    parsedResult = jsonMapper.readValue((String) result, Map.class);
                } catch (Exception e) {
                    parsedResult = Map.of("raw", result);
                }
            } else if (!(result instanceof Map)) {
                try {
                    String json = jsonMapper.writeValueAsString(result);
                    parsedResult = jsonMapper.readValue(json, Map.class);
                } catch (Exception e) {
                    parsedResult = Map.of("raw", String.valueOf(result));
                }
            }

            if (parsedResult instanceof Map) {
                Map<String, Object> resultMap = (Map<String, Object>) parsedResult;
                if ("human_approval_required".equals(resultMap.get("type"))) {
                    log.info("[WorkflowStepExecutor] ⚠️ Tool {} 返回高风险SQL需要人工确认", toolName);
                    ctx.assembleResult = result instanceof String ? (String) result : jsonMapper.writeValueAsString(resultMap);
                    return null;
                }
            }

            ctx.toolResults.put(step.getId(), parsedResult);
            if (step.getOutputVar() != null) {
                ctx.contextVars.put(step.getOutputVar(), parsedResult);
                log.debug("[WorkflowStepExecutor] output_var '{}' 已设置", step.getOutputVar());
            }

            log.info("[WorkflowStepExecutor] Tool {} 调用完成", toolName);

        } catch (Exception e) {
            log.error("[WorkflowStepExecutor] Tool {} 调用失败", toolName, e);
            Map<String, Object> errorResult = new LinkedHashMap<>();
            errorResult.put("success", false);
            errorResult.put("error", e.getMessage());
            ctx.toolResults.put(step.getId(), errorResult);
            if (step.getOutputVar() != null) {
                ctx.contextVars.put(step.getOutputVar(), errorResult);
            }
        }

        return resolveNextStep(step, ctx);
    }

    @SuppressWarnings("unchecked")
    public String executeCallWorkflowStep(WorkflowStep step, WorkflowContext ctx) {
        String skillName = step.getSkill();
        if (skillName == null || skillName.isEmpty()) {
            log.error("[WorkflowStepExecutor] call_workflow 步骤缺少 skill 字段: {}", step.getId());
            Map<String, Object> errorResult = Map.of("success", false, "error", "call_workflow步骤缺少skill字段");
            ctx.toolResults.put(step.getId(), errorResult);
            if (step.getOutputVar() != null) {
                ctx.contextVars.put(step.getOutputVar(), errorResult);
            }
            return resolveNextStep(step, ctx);
        }

        log.info("[WorkflowStepExecutor] 调用嵌套 workflow: {} (步骤: {})", skillName, step.getId());

        String savedAssembleResult = ctx.assembleResult;

        try {
            Map<String, Object> resolvedInput = resolveToolInput(step.getToolInput(), ctx);

            String result = executeFromSkillWorkflow(skillName, resolvedInput);

            ctx.assembleResult = savedAssembleResult;

            Object parsedResult;
            try {
                parsedResult = jsonMapper.readValue(result, Map.class);
            } catch (Exception e) {
                parsedResult = Map.of("raw", result);
            }

            ctx.toolResults.put(step.getId(), parsedResult);
            if (step.getOutputVar() != null) {
                ctx.contextVars.put(step.getOutputVar(), parsedResult);
                log.debug("[WorkflowStepExecutor] output_var '{}' 已设置", step.getOutputVar());
            }

            log.info("[WorkflowStepExecutor] 嵌套 workflow {} 调用完成", skillName);

        } catch (Exception e) {
            log.error("[WorkflowStepExecutor] 嵌套 workflow {} 调用失败", skillName, e);
            Map<String, Object> errorResult = new LinkedHashMap<>();
            errorResult.put("success", false);
            errorResult.put("error", e.getMessage());
            ctx.toolResults.put(step.getId(), errorResult);
            if (step.getOutputVar() != null) {
                ctx.contextVars.put(step.getOutputVar(), errorResult);
            }
        }

        return resolveNextStep(step, ctx);
    }

    @SuppressWarnings("unchecked")
    private void executeRespondStep(WorkflowStep step, WorkflowContext ctx) {
        Map<String, Object> output = step.getOutput();
        if (output == null) {
            output = new LinkedHashMap<>();
        }

        Map<String, Object> resolvedOutput = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : output.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String) {
                String resolved = expressionResolver.resolveMustacheAndExpr((String) value, ctx);
                try {
                    resolvedOutput.put(entry.getKey(), jsonMapper.readValue(resolved, Map.class));
                } catch (Exception e) {
                    resolvedOutput.put(entry.getKey(), resolved);
                }
            } else {
                resolvedOutput.put(entry.getKey(), value);
            }
        }

        try {
            ctx.assembleResult = jsonMapper.writeValueAsString(resolvedOutput);
        } catch (Exception e) {
            log.error("[WorkflowStepExecutor] respond 步骤序列化失败", e);
            ctx.assembleResult = "{\"success\":false,\"error\":\"respond序列化失败\"}";
        }

        log.info("[WorkflowStepExecutor] respond 步骤完成: {}", resolvedOutput.keySet());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> resolveToolInput(Map<String, Object> input, WorkflowContext ctx) {
        if (input == null) return new LinkedHashMap<>();

        Map<String, Object> resolved = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : input.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String) {
                String strValue = (String) value;
                String resolvedStr = expressionResolver.resolveMustacheAndExpr(strValue, ctx);

                if (resolvedStr.equals(strValue) && !strValue.contains("{{") && !strValue.contains("${")) {
                    resolved.put(entry.getKey(), expressionResolver.tryParseValue(strValue));
                } else {
                    resolved.put(entry.getKey(), expressionResolver.tryParseValue(resolvedStr));
                }
            } else if (value instanceof Map) {
                resolved.put(entry.getKey(), resolveToolInput((Map<String, Object>) value, ctx));
            } else {
                resolved.put(entry.getKey(), value);
            }
        }
        return resolved;
    }

    private String executeParallelStep(WorkflowStep step, WorkflowContext ctx,
                                       Map<String, WorkflowStep> stepMap) {
        List<String> branchIds = step.getBranches();
        if (branchIds == null || branchIds.isEmpty()) {
            log.warn("[WorkflowStepExecutor] parallel 步骤缺少 branches 定义");
            return resolveNextStep(step, ctx);
        }

        ExecutorService executor = Executors.newFixedThreadPool(Math.min(branchIds.size(), 4));
        List<Future<Worker.WorkerResult>> futures = new ArrayList<>();
        List<String> branchWorkerTypes = new ArrayList<>();
        List<String> submittedBranchIds = new ArrayList<>();

        for (String branchId : branchIds) {
            WorkflowStep branchStep = stepMap.get(branchId);
            if (branchStep == null || !"worker".equals(branchStep.getType())) {
                log.warn("[WorkflowStepExecutor] 并行分支 {} 不是 worker 类型，跳过", branchId);
                continue;
            }

            Worker worker = workers.get(branchStep.getWorker());
            if (worker == null) {
                log.warn("[WorkflowStepExecutor] 并行分支 Worker 未找到: {}", branchStep.getWorker());
                continue;
            }

            submittedBranchIds.add(branchId);
            branchWorkerTypes.add(branchStep.getWorker());
            Worker.WorkerContext workerContext = buildWorkerContext(ctx);

            if (branchStep.getInput() != null) {
                injectInputToContext(branchStep.getInput(), workerContext, ctx);
            }

            futures.add(executor.submit(() -> {
                try {
                    return executeWithRetry(worker, workerContext, branchStep);
                } catch (Exception e) {
                    log.error("[WorkflowStepExecutor] 并行分支 {} 执行异常", branchId, e);
                    return Worker.WorkerResult.failure(branchStep.getWorker(), e.getMessage());
                }
            }));
        }

        for (int i = 0; i < futures.size(); i++) {
            try {
                Worker.WorkerResult result = futures.get(i).get(60, TimeUnit.SECONDS);
                String branchId = submittedBranchIds.get(i);
                String workerType = branchWorkerTypes.get(i);
                ctx.workerResults.put(branchId, result);
                ctx.previousResults.put(branchId, result);
                ctx.previousResults.put(workerType, result);
            } catch (TimeoutException e) {
                log.error("[WorkflowStepExecutor] 并行分支执行超时");
            } catch (Exception e) {
                log.error("[WorkflowStepExecutor] 并行分支获取结果失败", e);
            }
        }

        executor.shutdown();

        return resolveNextStep(step, ctx);
    }

    private String resolveNextStep(WorkflowStep step, WorkflowContext ctx) {
        if (step.getOnNext() != null && !step.getOnNext().isEmpty()) {
            String[] nextSteps = step.getOnNext().split(",");
            if (nextSteps.length > 1) {
                return "parallel:" + step.getOnNext();
            }
            return nextSteps[0].trim();
        }

        if (step.getCondition() != null) {
            boolean condition = expressionResolver.evaluateCondition(step.getCondition(), ctx);
            if (condition && step.getOnConditionTrue() != null) {
                return step.getOnConditionTrue();
            }
            if (!condition && step.getOnConditionFalse() != null) {
                return step.getOnConditionFalse();
            }
        }

        return null;
    }

    private Worker.WorkerContext buildWorkerContext(WorkflowContext ctx) {
        Worker.WorkerContext workerContext = new Worker.WorkerContext();
        workerContext.setPlan(ctx.plan);
        workerContext.setDatasourceId(ctx.datasourceId);
        workerContext.setUserId(ctx.userId);
        workerContext.setUsername(ctx.username);
        workerContext.setUserMessage(ctx.userMessage);
        workerContext.setPreviousResults(new HashMap<>(ctx.previousResults));
        return workerContext;
    }

    private void injectInputToContext(String inputExpr, Worker.WorkerContext workerContext,
                                       WorkflowContext ctx) {
        if (inputExpr == null || inputExpr.isEmpty()) return;

        String[] parts = inputExpr.split(",");
        for (String part : parts) {
            String resolved = expressionResolver.resolveExpression(part.trim(), ctx);
            if (resolved != null) {
                try {
                    Map<String, Object> data = jsonMapper.readValue(resolved, Map.class);
                    if (workerContext.getPreviousResults() == null) {
                        workerContext.setPreviousResults(new HashMap<>());
                    }
                    for (Map.Entry<String, Object> entry : data.entrySet()) {
                        workerContext.getPreviousResults().put(entry.getKey(),
                            Worker.WorkerResult.success("injected", Map.of(entry.getKey(), entry.getValue())));
                    }
                } catch (Exception e) {
                    log.debug("[WorkflowStepExecutor] input 注入非JSON数据，跳过: {}", part.trim());
                }
            }
        }
    }

    private Worker.WorkerResult executeWithRetry(Worker worker, Worker.WorkerContext context, WorkflowStep step) {
        int maxAttempts = step.getRetryMaxAttempts() > 0 ? step.getRetryMaxAttempts() : 1;

        Worker.WorkerResult lastResult = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            lastResult = worker.execute(context);

            if (lastResult.isSuccess() || lastResult.isSkipped()) {
                return lastResult;
            }

            if (attempt < maxAttempts) {
                log.warn("[WorkflowStepExecutor] 步骤 {} 第 {} 次尝试失败，准备重试", step.getId(), attempt);
            }
        }

        return lastResult;
    }

    private void publishProgress(WorkflowContext ctx, String stepId, String message) {
        if (eventPublisher == null || ctx.sessionId == null) {
            return;
        }
        try {
            eventPublisher.publishEvent(new com.nl2sql.common.event.StreamProgressEvent(this, ctx.sessionId, stepId, message, null));
            log.debug("[WorkflowStepExecutor] 发布进度事件: step={}, message={}", stepId, message);
        } catch (Exception e) {
            log.debug("[WorkflowStepExecutor] 发布进度事件失败: {}", e.getMessage());
        }
    }

    private String getProgressMessage(String stepId) {
        switch (stepId) {
            case "validate_params": return "正在校验参数...";
            case "detect_chart": return "正在检测图表需求...";
            case "retrieve_schema": return "正在检索相关表结构...";
            case "generate_sql": return "正在生成SQL查询...";
            case "check_sql_valid": return "正在分析SQL风险...";
            case "execute_sql": return "正在执行查询...";
            case "post_process": return "正在处理查询结果...";
            default: return "正在执行: " + stepId;
        }
    }

    public String executeFromSkillWorkflow(String skillName, Map<String, Object> params) {
        throw new UnsupportedOperationException("executeFromSkillWorkflow 应由 WorkflowEngine 实现");
    }
}
