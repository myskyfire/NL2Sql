package com.nl2sql.core.agent.engine;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.nl2sql.common.event.StreamProgressEvent;
import com.nl2sql.core.agent.planner.QueryPlan;
import com.nl2sql.core.agent.tools.ToolRegistry;
import com.nl2sql.core.agent.worker.Worker;
import com.nl2sql.core.tracing.LangSmithRun;
import com.nl2sql.core.tracing.LangSmithTracingService;
import com.nl2sql.core.tracing.TracingContext;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
public class WorkflowEngine {

    private final Map<String, Worker> workers;
    private final ToolRegistry toolRegistry;
    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
    private final ObjectMapper jsonMapper = new ObjectMapper();

    @Autowired(required = false)
    private LangSmithTracingService tracingService;

    @Autowired(required = false)
    private ApplicationEventPublisher eventPublisher;

    private static final Pattern EXPR_PATTERN = Pattern.compile("\\$\\{(.+?)}");
    private static final Pattern MUSTACHE_PATTERN = Pattern.compile("\\{\\{(.+?)}}");

    public WorkflowEngine(List<Worker> workers, ToolRegistry toolRegistry) {
        this.workers = new HashMap<>();
        for (Worker w : workers) {
            this.workers.put(w.getWorkerType(), w);
            log.info("[WorkflowEngine] 注册 Worker: {}", w.getWorkerType());
        }
        this.toolRegistry = toolRegistry;
        log.info("[WorkflowEngine] ToolRegistry 已注入，可用 Tool 数: {}", toolRegistry.getToolNames().size());
    }

    public String execute(QueryPlan plan, Long datasourceId, Long userId, String username, String userMessage) {
        log.info("[WorkflowEngine] 开始执行工作流，复杂度: {}", plan.getComplexity());

        LangSmithRun workflowRun = null;
        if (tracingService != null && tracingService.isEnabled()) {
            workflowRun = tracingService.traceChain("WorkflowEngine.execute",
                Map.of("complexity", plan.getComplexity() != null ? plan.getComplexity() : "unknown",
                       "datasourceId", datasourceId != null ? datasourceId.toString() : "null"),
                TracingContext.currentRunId());
        }

        WorkflowDefinition workflow = loadWorkflow(plan.getComplexity());
        if (workflow == null) {
            log.warn("[WorkflowEngine] 未找到对应的工作流，使用默认简单流程");
            return executeSimplePipeline(plan, datasourceId, userId, username, userMessage);
        }

        log.info("[WorkflowEngine] 使用工作流: {}", workflow.getName());

        WorkflowExecutionContext ctx = new WorkflowExecutionContext();
        ctx.plan = plan;
        ctx.datasourceId = datasourceId;
        ctx.userId = userId;
        ctx.username = username;
        ctx.userMessage = userMessage;

        return executeWorkflowDefinition(workflow, ctx, workflowRun);
    }

    public String executeFromSkillWorkflow(String skillName, Map<String, Object> params) {
        log.info("[WorkflowEngine] 从 Skill workflow 执行: {}", skillName);

        WorkflowDefinition workflow = loadSkillWorkflow(skillName);
        if (workflow == null) {
            log.warn("[WorkflowEngine] 未找到 Skill {} 的 workflow 定义", skillName);
            return "{\"success\":false,\"error\":\"未找到Skill的workflow定义: " + skillName + "\"}";
        }

        log.info("[WorkflowEngine] 使用 Skill workflow: {}", workflow.getName());

        WorkflowExecutionContext ctx = new WorkflowExecutionContext();
        ctx.plan = null;
        ctx.datasourceId = params.get("datasourceId") != null ? ((Number) params.get("datasourceId")).longValue() : null;
        ctx.userId = params.get("userId") != null ? ((Number) params.get("userId")).longValue() : null;
        ctx.username = params.get("username") != null ? params.get("username").toString() : null;
        ctx.userMessage = params.get("question") != null ? params.get("question").toString() : null;
        ctx.sessionId = params.get("sessionId") != null ? params.get("sessionId").toString() : null;

        ctx.contextVars.putAll(params);

        return executeWorkflowDefinition(workflow, ctx, null);
    }

    private String executeWorkflowDefinition(WorkflowDefinition workflow, WorkflowExecutionContext ctx,
                                              LangSmithRun workflowRun) {
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
                    return endWorkflowTrace(workflowRun, "{\"success\":false,\"error\":\"工作流步骤未找到: " + currentStepId + "\"}");
                }

                log.info("[WorkflowEngine] 执行步骤: {} (type={}, action={})", step.getId(), step.getType(), step.getAction());

                String nextStepId = executeStep(step, ctx, stepMap);

                if (nextStepId == null && ("assemble".equals(step.getType()) || "respond".equals(step.getAction()) || "respond".equals(step.getType()))) {
                    return endWorkflowTrace(workflowRun, ctx.assembleResult);
                }

                if (nextStepId == null && "fail".equals(step.getId())) {
                    return endWorkflowTrace(workflowRun, "{\"success\":false,\"error\":\"工作流执行失败\"}");
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
                return endWorkflowTrace(workflowRun, "{\"success\":false,\"error\":\"工作流步骤执行次数超限\"}");
            }

            return endWorkflowTrace(workflowRun, assembleDefaultResult(ctx.workerResults));

        } catch (Exception e) {
            log.error("[WorkflowEngine] 工作流执行异常", e);
            if (tracingService != null && workflowRun != null) {
                tracingService.endRun(workflowRun, null, e.getMessage());
            }
            return "{\"success\":false,\"error\":\"工作流执行异常: " + e.getMessage() + "\"}";
        }
    }

    private String endWorkflowTrace(LangSmithRun run, String result) {
        if (tracingService != null && run != null) {
            Map<String, Object> outputs = new LinkedHashMap<>();
            outputs.put("resultLength", result != null ? result.length() : 0);
            tracingService.endRun(run, outputs, null);
        }
        return result;
    }

    private String executeStep(WorkflowStep step, WorkflowExecutionContext ctx,
                               Map<String, WorkflowStep> stepMap) {
        String action = step.getAction();
        String type = step.getType();

        if ("call_tool".equals(action) || "call_tool".equals(type)) {
            return executeCallToolStep(step, ctx);
        }

        if ("respond".equals(action) || "respond".equals(type)) {
            if (step.getCondition() != null) {
                boolean conditionMet = evaluateCondition(step.getCondition(), ctx);
                if (!conditionMet) {
                    log.info("[WorkflowEngine] respond 步骤 {} 条件不满足，跳过", step.getId());
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

            case "respond":
                if (step.getCondition() != null) {
                    boolean conditionMet = evaluateCondition(step.getCondition(), ctx);
                    if (!conditionMet) {
                        log.info("[WorkflowEngine] respond 步骤 {} 条件不满足，跳过", step.getId());
                        if (step.getOnConditionFalse() != null && !step.getOnConditionFalse().isEmpty()) {
                            return step.getOnConditionFalse();
                        }
                        return resolveNextStep(step, ctx);
                    }
                }
                executeRespondStep(step, ctx);
                return null;

            default:
                log.warn("[WorkflowEngine] 未知步骤类型: {}, action: {}", type, action);
                return resolveNextStep(step, ctx);
        }
    }

    private String executeWorkerStep(WorkflowStep step, WorkflowExecutionContext ctx,
                                     Map<String, WorkflowStep> stepMap) {
        Worker worker = workers.get(step.getWorker());
        if (worker == null) {
            log.warn("[WorkflowEngine] 未找到 Worker: {}，跳过", step.getWorker());
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

        // ✅ 人机协同：检测到等待确认状态，立即返回
        if (result.isWaitingForApproval()) {
            log.info("[WorkflowEngine] ⚠️ Worker {} 需要人工确认", worker.getWorkerType());
            return result.getRawOutput();  // 直接返回确认请求JSON
        }

        if (!result.isSuccess() && !result.isSkipped()) {
            log.error("[WorkflowEngine] 步骤 {} 执行失败: {}", step.getId(), result.getErrorMessage());
            if (step.isOnErrorFail()) {
                return null;
            }
        }

        return resolveNextStep(step, ctx);
    }

    private String executeConditionStep(WorkflowStep step, WorkflowExecutionContext ctx) {
        boolean condition = evaluateCondition(step.getCondition(), ctx);

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

    private void executeAssembleStep(WorkflowStep step, WorkflowExecutionContext ctx) {
        try {
            Map<String, Object> response = buildFrontendCompatibleResponse(ctx.workerResults);
            ctx.assembleResult = jsonMapper.writeValueAsString(response);
        } catch (Exception e) {
            log.error("[WorkflowEngine] 组装结果失败", e);
            ctx.assembleResult = "{\"success\":false,\"error\":\"结果组装失败\"}";
        }
    }

    @SuppressWarnings("unchecked")
    private String executeCallToolStep(WorkflowStep step, WorkflowExecutionContext ctx) {
        String toolName = step.getTool();
        if (toolName == null || toolName.isEmpty()) {
            log.error("[WorkflowEngine] call_tool 步骤缺少 tool 字段: {}", step.getId());
            return resolveNextStep(step, ctx);
        }

        if (!toolRegistry.hasTool(toolName)) {
            log.error("[WorkflowEngine] Tool 未注册: {}", toolName);
            ctx.toolResults.put(step.getId(), Map.of("success", false, "error", "Tool未注册: " + toolName));
            if (step.getOutputVar() != null) {
                ctx.contextVars.put(step.getOutputVar(), Map.of("success", false, "error", "Tool未注册: " + toolName));
            }
            return resolveNextStep(step, ctx);
        }

        if (step.getCondition() != null) {
            boolean conditionMet = evaluateCondition(step.getCondition(), ctx);
            if (!conditionMet) {
                log.info("[WorkflowEngine] call_tool 步骤 {} 条件不满足，跳过", step.getId());
                if (step.getOnConditionFalse() != null && !step.getOnConditionFalse().isEmpty()) {
                    return step.getOnConditionFalse();
                }
                return resolveNextStep(step, ctx);
            }
        }

        Map<String, Object> resolvedInput = resolveToolInput(step.getToolInput(), ctx);

        log.info("[WorkflowEngine] 调用 Tool: {} (步骤: {}), 参数: {}", toolName, step.getId(), resolvedInput.keySet());
        log.debug("[WorkflowEngine] Tool 参数详情: {}", resolvedInput);

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

            // ✅ 人机协同：检测高风险SQL需要人工确认
            if (parsedResult instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> resultMap = (Map<String, Object>) parsedResult;
                if ("human_approval_required".equals(resultMap.get("type"))) {
                    log.info("[WorkflowEngine] ⚠️ Tool {} 返回高风险SQL需要人工确认", toolName);
                    ctx.assembleResult = result instanceof String ? (String) result : jsonMapper.writeValueAsString(resultMap);
                    return null;  // 终止workflow，直接返回确认请求
                }
            }

            ctx.toolResults.put(step.getId(), parsedResult);
            if (step.getOutputVar() != null) {
                ctx.contextVars.put(step.getOutputVar(), parsedResult);
                log.debug("[WorkflowEngine] output_var '{}' 已设置", step.getOutputVar());
            }

            log.info("[WorkflowEngine] Tool {} 调用完成", toolName);

        } catch (Exception e) {
            log.error("[WorkflowEngine] Tool {} 调用失败", toolName, e);
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
    private void executeRespondStep(WorkflowStep step, WorkflowExecutionContext ctx) {
        Map<String, Object> output = step.getOutput();
        if (output == null) {
            output = new LinkedHashMap<>();
        }

        Map<String, Object> resolvedOutput = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : output.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String) {
                String resolved = resolveMustacheAndExpr((String) value, ctx);
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
            log.error("[WorkflowEngine] respond 步骤序列化失败", e);
            ctx.assembleResult = "{\"success\":false,\"error\":\"respond序列化失败\"}";
        }

        log.info("[WorkflowEngine] respond 步骤完成: {}", resolvedOutput.keySet());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> resolveToolInput(Map<String, Object> input, WorkflowExecutionContext ctx) {
        if (input == null) return new LinkedHashMap<>();

        Map<String, Object> resolved = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : input.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String) {
                String strValue = (String) value;
                String resolvedStr = resolveMustacheAndExpr(strValue, ctx);

                if (resolvedStr.equals(strValue) && !strValue.contains("{{") && !strValue.contains("${")) {
                    resolved.put(entry.getKey(), tryParseValue(strValue));
                } else {
                    resolved.put(entry.getKey(), tryParseValue(resolvedStr));
                }
            } else if (value instanceof Map) {
                resolved.put(entry.getKey(), resolveToolInput((Map<String, Object>) value, ctx));
            } else {
                resolved.put(entry.getKey(), value);
            }
        }
        return resolved;
    }

    private String resolveMustacheAndExpr(String template, WorkflowExecutionContext ctx) {
        String result = template;

        Matcher mustacheMatcher = MUSTACHE_PATTERN.matcher(result);
        StringBuffer sb = new StringBuffer();
        while (mustacheMatcher.find()) {
            String path = mustacheMatcher.group(1).trim();
            String value = resolvePath(path, ctx);
            mustacheMatcher.appendReplacement(sb, value != null ? Matcher.quoteReplacement(value) : "");
        }
        mustacheMatcher.appendTail(sb);
        result = sb.toString();

        Matcher exprMatcher = EXPR_PATTERN.matcher(result);
        sb = new StringBuffer();
        while (exprMatcher.find()) {
            String path = exprMatcher.group(1).trim();
            String value = resolvePath(path, ctx);
            exprMatcher.appendReplacement(sb, value != null ? Matcher.quoteReplacement(value) : "");
        }
        exprMatcher.appendTail(sb);

        return sb.toString();
    }

    private Object tryParseValue(String value) {
        if (value == null) return null;
        if ("true".equalsIgnoreCase(value)) return true;
        if ("false".equalsIgnoreCase(value)) return false;
        if ("null".equalsIgnoreCase(value)) return null;
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {}
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException ignored) {}
        try {
            return jsonMapper.readValue(value, Map.class);
        } catch (Exception ignored) {}
        return value;
    }

    private String executeParallelStep(WorkflowStep step, WorkflowExecutionContext ctx,
                                       Map<String, WorkflowStep> stepMap) {
        List<String> branchIds = step.getBranches();
        if (branchIds == null || branchIds.isEmpty()) {
            log.warn("[WorkflowEngine] parallel 步骤缺少 branches 定义");
            return resolveNextStep(step, ctx);
        }

        ExecutorService executor = Executors.newFixedThreadPool(Math.min(branchIds.size(), 4));
        List<Future<Worker.WorkerResult>> futures = new ArrayList<>();
        List<String> branchWorkerTypes = new ArrayList<>();

        for (String branchId : branchIds) {
            WorkflowStep branchStep = stepMap.get(branchId);
            if (branchStep == null || !"worker".equals(branchStep.getType())) {
                log.warn("[WorkflowEngine] 并行分支 {} 不是 worker 类型，跳过", branchId);
                continue;
            }

            Worker worker = workers.get(branchStep.getWorker());
            if (worker == null) {
                log.warn("[WorkflowEngine] 并行分支 Worker 未找到: {}", branchStep.getWorker());
                continue;
            }

            branchWorkerTypes.add(branchStep.getWorker());
            Worker.WorkerContext workerContext = buildWorkerContext(ctx);

            if (branchStep.getInput() != null) {
                injectInputToContext(branchStep.getInput(), workerContext, ctx);
            }

            futures.add(executor.submit(() -> {
                try {
                    return executeWithRetry(worker, workerContext, branchStep);
                } catch (Exception e) {
                    log.error("[WorkflowEngine] 并行分支 {} 执行异常", branchId, e);
                    return Worker.WorkerResult.failure(branchStep.getWorker(), e.getMessage());
                }
            }));
        }

        for (int i = 0; i < futures.size(); i++) {
            try {
                Worker.WorkerResult result = futures.get(i).get(60, TimeUnit.SECONDS);
                String branchId = branchIds.get(i);
                String workerType = branchWorkerTypes.get(i);
                ctx.workerResults.put(branchId, result);
                ctx.previousResults.put(branchId, result);
                ctx.previousResults.put(workerType, result);
            } catch (TimeoutException e) {
                log.error("[WorkflowEngine] 并行分支执行超时");
            } catch (Exception e) {
                log.error("[WorkflowEngine] 并行分支获取结果失败", e);
            }
        }

        executor.shutdown();

        return resolveNextStep(step, ctx);
    }

    private String resolveNextStep(WorkflowStep step, WorkflowExecutionContext ctx) {
        if (step.getOnNext() != null && !step.getOnNext().isEmpty()) {
            String[] nextSteps = step.getOnNext().split(",");
            if (nextSteps.length > 1) {
                return "parallel:" + step.getOnNext();
            }
            return nextSteps[0].trim();
        }

        if (step.getCondition() != null) {
            boolean condition = evaluateCondition(step.getCondition(), ctx);
            if (condition && step.getOnConditionTrue() != null) {
                return step.getOnConditionTrue();
            }
            if (!condition && step.getOnConditionFalse() != null) {
                return step.getOnConditionFalse();
            }
        }

        return null;
    }

    private Worker.WorkerContext buildWorkerContext(WorkflowExecutionContext ctx) {
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
                                       WorkflowExecutionContext ctx) {
        if (inputExpr == null || inputExpr.isEmpty()) return;

        String[] parts = inputExpr.split(",");
        for (String part : parts) {
            String resolved = resolveExpression(part.trim(), ctx);
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
                    log.debug("[WorkflowEngine] input 注入非JSON数据，跳过: {}", part.trim());
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
                log.warn("[WorkflowEngine] 步骤 {} 第 {} 次尝试失败，准备重试", step.getId(), attempt);
            }
        }

        return lastResult;
    }

    private String executeSimplePipeline(QueryPlan plan, Long datasourceId, Long userId,
                                          String username, String userMessage) {
        Worker.WorkerContext context = new Worker.WorkerContext();
        context.setPlan(plan);
        context.setDatasourceId(datasourceId);
        context.setUserId(userId);
        context.setUsername(username);
        context.setUserMessage(userMessage);

        Map<String, Worker.WorkerResult> results = new LinkedHashMap<>();

        Worker sqlWorker = workers.get("sql");
        if (sqlWorker != null) {
            Worker.WorkerResult sqlResult = sqlWorker.execute(context);
            results.put("sql", sqlResult);
            context.getPreviousResults().put("sql", sqlResult);

            if (!sqlResult.isSuccess()) {
                return "{\"success\":false,\"error\":\"SQL执行失败: " + sqlResult.getErrorMessage() + "\"}";
            }
        }

        if (plan.getChart() != null && plan.getChart().isNeeded()) {
            Worker chartWorker = workers.get("chart");
            if (chartWorker != null) {
                Worker.WorkerResult chartResult = chartWorker.execute(context);
                results.put("chart", chartResult);
                context.getPreviousResults().put("chart", chartResult);
            }
        }

        if (plan.isNeedSummary()) {
            Worker summaryWorker = workers.get("summary");
            if (summaryWorker != null) {
                Worker.WorkerResult summaryResult = summaryWorker.execute(context);
                results.put("summary", summaryResult);
                context.getPreviousResults().put("summary", summaryResult);
            }
        }

        return assembleDefaultResult(results);
    }

    // ==================== 表达式解析 ====================

    private boolean evaluateCondition(String condition, WorkflowExecutionContext ctx) {
        if (condition == null) return true;

        String resolved = resolveExpression(condition, ctx);

        if ("true".equalsIgnoreCase(resolved) || "1".equals(resolved)) return true;
        if ("false".equalsIgnoreCase(resolved) || "0".equals(resolved) || "null".equalsIgnoreCase(resolved)) return false;

        if (condition.contains("==")) {
            String[] parts = condition.split("==");
            if (parts.length == 2) {
                String left = resolveExpression(parts[0].trim(), ctx);
                String right = resolveExpression(parts[1].trim(), ctx);
                return Objects.equals(left, right);
            }
        }

        if (condition.contains("!=")) {
            String[] parts = condition.split("!=");
            if (parts.length == 2) {
                String left = resolveExpression(parts[0].trim(), ctx);
                String right = resolveExpression(parts[1].trim(), ctx);
                return !Objects.equals(left, right);
            }
        }

        return true;
    }

    private String resolveExpression(String expr, WorkflowExecutionContext ctx) {
        if (expr == null) return null;

        Matcher matcher = EXPR_PATTERN.matcher(expr);
        StringBuffer sb = new StringBuffer();

        while (matcher.find()) {
            String path = matcher.group(1).trim();
            String value = resolvePath(path, ctx);
            matcher.appendReplacement(sb, value != null ? Matcher.quoteReplacement(value) : "");
        }
        matcher.appendTail(sb);

        String result = sb.toString();
        return result.isEmpty() ? expr : result;
    }

    private String resolvePath(String path, WorkflowExecutionContext ctx) {
        if (path.startsWith("queryPlan.") || path.startsWith("plan.")) {
            String fieldPath = path.startsWith("queryPlan.") ? path.substring("queryPlan.".length()) : path.substring("plan.".length());
            return resolveQueryPlanPath(fieldPath, ctx.plan);
        }

        if ("datasourceId".equals(path)) {
            return ctx.datasourceId != null ? ctx.datasourceId.toString() : "null";
        }

        if ("userId".equals(path)) {
            return ctx.userId != null ? ctx.userId.toString() : "null";
        }

        if ("username".equals(path)) {
            return ctx.username != null ? ctx.username : "null";
        }

        if ("sessionId".equals(path)) {
            return ctx.sessionId != null ? ctx.sessionId : "null";
        }

        if ("userMessage".equals(path) || "question".equals(path)) {
            return ctx.userMessage != null ? ctx.userMessage : "null";
        }

        if (ctx.contextVars.containsKey(path)) {
            Object varValue = ctx.contextVars.get(path);
            if (varValue instanceof String) return (String) varValue;
            // ✅ 关键修复：Map/List 直接序列化为 JSON，但调用方需特殊处理
            try {
                return jsonMapper.writeValueAsString(varValue);
            } catch (Exception e) {
                return String.valueOf(varValue);
            }
        }

        if (path.contains(".")) {
            String[] parts = path.split("\\.", 2);
            String rootKey = parts[0];
            String subPath = parts.length > 1 ? parts[1] : null;

            Object rootValue = ctx.contextVars.get(rootKey);
            if (rootValue == null) {
                rootValue = ctx.toolResults.get(rootKey);
            }
            if (rootValue == null) {
                Worker.WorkerResult workerResult = ctx.previousResults.get(rootKey);
                if (workerResult != null) {
                    if (subPath != null && (subPath.startsWith("result") || subPath.startsWith("data"))) {
                        try {
                            return jsonMapper.writeValueAsString(workerResult.getData());
                        } catch (Exception e) {
                            return workerResult.getRawOutput();
                        }
                    }
                    if (subPath != null) {
                        try {
                            String json = jsonMapper.writeValueAsString(workerResult.getData());
                            Map<String, Object> data = jsonMapper.readValue(json, Map.class);
                            return resolveSubPath(data, subPath);
                        } catch (Exception e) {
                            return null;
                        }
                    }
                    try {
                        return jsonMapper.writeValueAsString(workerResult.getData());
                    } catch (Exception e) {
                        return workerResult.getRawOutput();
                    }
                }
                return null;
            }

            if (subPath == null) {
                if (rootValue instanceof String) return (String) rootValue;
                try {
                    return jsonMapper.writeValueAsString(rootValue);
                } catch (Exception e) {
                    return String.valueOf(rootValue);
                }
            }

            if (rootValue instanceof Map) {
                return resolveSubPath((Map<String, Object>) rootValue, subPath);
            }

            return null;
        }

        if (ctx.previousResults.containsKey(path)) {
            Worker.WorkerResult result = ctx.previousResults.get(path);
            try {
                return jsonMapper.writeValueAsString(result.getData());
            } catch (Exception e) {
                return result.getRawOutput();
            }
        }

        return null;
    }

    @SuppressWarnings("unchecked")
    private String resolveSubPath(Map<String, Object> data, String subPath) {
        String[] parts = subPath.split("\\.", 2);
        String key = parts[0];
        Object value = data.get(key);

        if (value == null) return null;

        if (parts.length == 1) {
            if (value instanceof String) return (String) value;
            if (value instanceof Number) return String.valueOf(value);
            if (value instanceof Boolean) return String.valueOf(value);
            try {
                return jsonMapper.writeValueAsString(value);
            } catch (Exception e) {
                return String.valueOf(value);
            }
        }

        if (value instanceof Map) {
            return resolveSubPath((Map<String, Object>) value, parts[1]);
        }

        return null;
    }

    private String resolveQueryPlanPath(String fieldPath, QueryPlan plan) {
        if (plan == null) return null;

        if (fieldPath.startsWith("chart.")) {
            String chartField = fieldPath.substring("chart.".length());
            if (plan.getChart() == null) return "false";
            switch (chartField) {
                case "needed": return String.valueOf(plan.getChart().isNeeded());
                case "type": return plan.getChart().getType() != null ? plan.getChart().getType().name() : null;
                case "xField": return plan.getChart().getXField();
                case "yField": return plan.getChart().getYField();
                case "title": return plan.getChart().getTitle();
                default: return null;
            }
        }

        if (fieldPath.startsWith("datasource.")) {
            String dsField = fieldPath.substring("datasource.".length());
            if (plan.getDatasource() == null) return null;
            switch (dsField) {
                case "id": return plan.getDatasource().getId() != null ? plan.getDatasource().getId().toString() : null;
                case "name": return plan.getDatasource().getName();
                default: return null;
            }
        }

        switch (fieldPath) {
            case "needSummary": return String.valueOf(plan.isNeedSummary());
            case "complexity": return plan.getComplexity() != null ? plan.getComplexity().name() : null;
            case "userQuestion": return plan.getUserQuestion();
            default: return null;
        }
    }

    // ==================== 结果组装 ====================

    private String assembleDefaultResult(Map<String, Worker.WorkerResult> results) {
        try {
            Map<String, Object> response = buildFrontendCompatibleResponse(results);
            return jsonMapper.writeValueAsString(response);
        } catch (Exception e) {
            log.error("[WorkflowEngine] 默认组装失败", e);
            return "{\"success\":false,\"error\":\"结果组装失败\"}";
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> buildFrontendCompatibleResponse(Map<String, Worker.WorkerResult> results) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);

        Worker.WorkerResult sqlResult = results.get("sql");
        if (sqlResult == null) {
            for (Map.Entry<String, Worker.WorkerResult> entry : results.entrySet()) {
                if (entry.getValue() != null && entry.getValue().getData() != null
                    && entry.getValue().getData().containsKey("sql")) {
                    sqlResult = entry.getValue();
                    break;
                }
            }
        }

        if (sqlResult != null && sqlResult.isSuccess() && sqlResult.getData() != null) {
            Map<String, Object> sqlData = sqlResult.getData();

            if (sqlData.containsKey("type")) {
                String type = sqlData.get("type") != null ? sqlData.get("type").toString() : "data";
                response.put("type", type);
            } else {
                response.put("type", "data");
            }

            if (sqlData.containsKey("data")) {
                response.put("data", sqlData.get("data"));
            }
            if (sqlData.containsKey("rowCount")) {
                response.put("rowCount", sqlData.get("rowCount"));
            }
            if (sqlData.containsKey("executionTime")) {
                response.put("executionTime", sqlData.get("executionTime"));
            }
            if (sqlData.containsKey("sql")) {
                response.put("sql", sqlData.get("sql"));
            }
            if (sqlData.containsKey("datasourceId")) {
                response.put("datasourceId", sqlData.get("datasourceId"));
            }
            // ✅ 只在有实际值时才添加 optimizationSuggestion
            if (sqlData.containsKey("optimizationSuggestion")) {
                Object optSuggestion = sqlData.get("optimizationSuggestion");
                if (optSuggestion != null && !optSuggestion.toString().trim().isEmpty()) {
                    response.put("optimizationSuggestion", optSuggestion);
                }
            }
            if (sqlData.containsKey("followUpSuggestions")) {
                response.put("followUpSuggestions", sqlData.get("followUpSuggestions"));
            }
            if (sqlData.containsKey("steps")) {
                response.put("steps", sqlData.get("steps"));
            }

            if ("clarification".equals(response.get("type"))) {
                response.put("needsClarification", true);
                response.put("clarificationMessage", sqlData.get("clarificationMessage"));
            }

            if ("human_approval_required".equals(response.get("type"))) {
                response.put("approvalId", sqlData.get("approvalId"));
                response.put("riskLevel", sqlData.get("riskLevel"));
                response.put("riskReason", sqlData.get("riskReason"));
                response.put("message", sqlData.get("message"));
            }

            if ("error".equals(response.get("type"))) {
                response.put("success", false);
                response.put("error", sqlData.get("error"));
            }
        }

        Worker.WorkerResult chartResult = results.get("chart");
        if (chartResult != null) {
            for (Map.Entry<String, Worker.WorkerResult> entry : results.entrySet()) {
                if (entry.getValue() != null && entry.getValue().getData() != null
                    && entry.getValue().getData().containsKey("echartsConfig")) {
                    chartResult = entry.getValue();
                    break;
                }
            }
        }
        if (chartResult != null && chartResult.isSuccess() && chartResult.getData() != null) {
            Map<String, Object> chartData = chartResult.getData();
            if (chartData.containsKey("echartsConfig")) {
                response.put("echartsConfig", chartData.get("echartsConfig"));
                response.put("chartType", chartData.getOrDefault("chartType", "bar"));
                response.put("type", "chart");
            }
        }

        Worker.WorkerResult summaryResult = results.get("summary");
        if (summaryResult != null) {
            for (Map.Entry<String, Worker.WorkerResult> entry : results.entrySet()) {
                if (entry.getValue() != null && entry.getValue().getData() != null
                    && entry.getValue().getData().containsKey("summary")) {
                    summaryResult = entry.getValue();
                    break;
                }
            }
        }
        if (summaryResult != null && summaryResult.isSuccess() && summaryResult.getData() != null) {
            Map<String, Object> summaryData = summaryResult.getData();
            if (summaryData.containsKey("summary")) {
                response.put("summary", summaryData.get("summary"));
            }
        }

        return response;
    }

    // ==================== YAML 加载 ====================

    private WorkflowDefinition loadWorkflow(QueryPlan.ComplexityLevel complexity) {
        String resourcePath;
        switch (complexity) {
            case COMPLEX:
                resourcePath = "workflow/complex-query.yml";
                break;
            case MODERATE:
                resourcePath = "workflow/moderate-query.yml";
                break;
            case SIMPLE:
            default:
                return null;
        }

        try {
            ClassPathResource resource = new ClassPathResource(resourcePath);
            if (!resource.exists()) {
                log.warn("[WorkflowEngine] 工作流配置文件不存在: {}", resourcePath);
                return null;
            }

            try (InputStream is = resource.getInputStream()) {
                return yamlMapper.readValue(is, WorkflowDefinition.class);
            }
        } catch (Exception e) {
            log.error("[WorkflowEngine] 加载工作流配置失败: {}", resourcePath, e);
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private WorkflowDefinition loadSkillWorkflow(String skillName) {
        String skillDir = resolveSkillDir(skillName);
        if (skillDir == null) {
            log.warn("[WorkflowEngine] 未找到 Skill 目录: {}", skillName);
            return null;
        }

        String yamlPath = "skills/" + skillDir + "/SKILL.md";
        try {
            ClassPathResource resource = new ClassPathResource(yamlPath);
            if (!resource.exists()) {
                log.warn("[WorkflowEngine] SKILL.md 不存在: {}", yamlPath);
                return null;
            }

            String content;
            try (InputStream is = resource.getInputStream()) {
                content = new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }

            String yamlContent = extractYamlFrontMatter(content);
            if (yamlContent == null) {
                log.warn("[WorkflowEngine] SKILL.md 无 YAML front matter: {}", yamlPath);
                return null;
            }

            Map<String, Object> frontMatter = yamlMapper.readValue(yamlContent, Map.class);
            Map<String, Object> workflowMap = (Map<String, Object>) frontMatter.get("workflow");
            if (workflowMap == null) {
                log.warn("[WorkflowEngine] SKILL.md 无 workflow 定义: {}", yamlPath);
                return null;
            }

            List<Map<String, Object>> stepsList = (List<Map<String, Object>>) workflowMap.get("steps");
            if (stepsList == null || stepsList.isEmpty()) {
                log.warn("[WorkflowEngine] SKILL.md workflow 无 steps: {}", yamlPath);
                return null;
            }

            WorkflowDefinition definition = new WorkflowDefinition();
            definition.setName(skillName);
            definition.setDescription((String) workflowMap.getOrDefault("description", "Skill workflow: " + skillName));

            List<WorkflowStep> steps = new ArrayList<>();
            for (Map<String, Object> stepMap : stepsList) {
                WorkflowStep step = new WorkflowStep();
                step.setId((String) stepMap.get("id"));

                String action = (String) stepMap.get("action");
                step.setAction(action);
                if (action != null) {
                    step.setType(action);
                } else {
                    step.setType((String) stepMap.getOrDefault("type", "skip"));
                }

                String toolValue = (String) stepMap.get("tool");
                step.setTool(toolValue);
                log.debug("[WorkflowEngine] 解析步骤 {} 的 tool 字段: {}", step.getId(), toolValue);
                step.setOutputVar((String) stepMap.get("output_var"));

                Map<String, Object> input = (Map<String, Object>) stepMap.get("input");
                step.setToolInput(input);

                Map<String, Object> output = (Map<String, Object>) stepMap.get("output");
                step.setOutput(output);

                String condition = (String) stepMap.get("condition");
                step.setCondition(condition);

                String onNext = (String) stepMap.get("onNext");
                if (onNext == null) {
                    onNext = (String) stepMap.get("on_next");
                }
                step.setOnNext(onNext);

                String onConditionTrue = (String) stepMap.get("on_condition_true");
                if (onConditionTrue == null) {
                    onConditionTrue = (String) stepMap.get("onConditionTrue");
                }
                step.setOnConditionTrue(onConditionTrue);

                String onConditionFalse = (String) stepMap.get("on_condition_false");
                if (onConditionFalse == null) {
                    onConditionFalse = (String) stepMap.get("onConditionFalse");
                }
                step.setOnConditionFalse(onConditionFalse);

                steps.add(step);
            }
            definition.setSteps(steps);

            log.info("[WorkflowEngine] 从 SKILL.md 加载 workflow: {}, 步骤数: {}", skillName, steps.size());
            return definition;

        } catch (Exception e) {
            log.error("[WorkflowEngine] 加载 Skill workflow 失败: {}", yamlPath, e);
            return null;
        }
    }

    private String resolveSkillDir(String skillName) {
        switch (skillName) {
            case "execute_standard_query": return "standard-query";
            case "sql_validate_execute": return "sql-validate-execute";
            case "sql_performance_analysis": return "sql-performance-analysis";
            case "generate_report_with_insights": return "report-with-insights";
            case "summarize_result": return "summarize-result";
            case "hybrid-example": return "hybrid-example";
            default: return skillName;
        }
    }

    private String extractYamlFrontMatter(String content) {
        if (content == null || !content.startsWith("---")) return null;

        int endIndex = content.indexOf("---", 3);
        if (endIndex == -1) return null;

        return content.substring(3, endIndex).trim();
    }

    // ==================== 内部数据类 ====================

    private void publishProgress(WorkflowExecutionContext ctx, String stepId, String message) {
        if (eventPublisher == null || ctx.sessionId == null) {
            return;
        }
        try {
            eventPublisher.publishEvent(new StreamProgressEvent(this, ctx.sessionId, stepId, message, null));
            log.debug("[WorkflowEngine] 发布进度事件: step={}, message={}", stepId, message);
        } catch (Exception e) {
            log.debug("[WorkflowEngine] 发布进度事件失败: {}", e.getMessage());
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

    @Data
    static class WorkflowExecutionContext {
        QueryPlan plan;
        Long datasourceId;
        Long userId;
        String username;
        String userMessage;
        String sessionId;
        Map<String, Worker.WorkerResult> workerResults = new LinkedHashMap<>();
        Map<String, Worker.WorkerResult> previousResults = new HashMap<>();
        Map<String, Object> toolResults = new LinkedHashMap<>();
        Map<String, Object> contextVars = new LinkedHashMap<>();
        String assembleResult;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class WorkflowDefinition {
        private String name;
        private String description;
        private List<WorkflowStep> steps;
    }

    @Data
    static class WorkflowStep {
        private String id;
        private String type;
        private String action;
        private String tool;
        private Map<String, Object> toolInput;
        private String outputVar;
        private String worker;
        private String condition;
        private String input;
        private String onNext;
        private String onTrue;
        private String onFalse;
        private String onConditionTrue;
        private String onConditionFalse;
        private List<String> branches;
        private Map<String, Object> output;
        private RetryConfig retry;
        private boolean onErrorFail;

        public int getRetryMaxAttempts() {
            return retry != null ? retry.getMaxAttempts() : 0;
        }
    }

    @Data
    static class RetryConfig {
        private int maxAttempts;
        private List<String> onErrors;
        private String tool;
    }
}
