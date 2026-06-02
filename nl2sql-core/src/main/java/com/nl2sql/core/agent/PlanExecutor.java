package com.nl2sql.core.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nl2sql.core.agent.planner.PlanValidator;
import com.nl2sql.core.agent.planner.QueryPlan;
import com.nl2sql.core.agent.planner.StepReflector;
import com.nl2sql.core.agent.planner.StepReflector.Decision;
import com.nl2sql.core.agent.planner.StepReflector.ReflectionResult;
import com.nl2sql.core.agent.tools.ToolRegistry;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

@Slf4j
public class PlanExecutor {

    private final ToolRegistry toolRegistry;
    private final PlanValidator planValidator;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PlanExecutor(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
        this.planValidator = new PlanValidator(toolRegistry);
        log.info("[PlanExecutor] initialized with ToolRegistry + PlanValidator + StepReflector");
    }

    public String execute(QueryPlan plan, Long datasourceId, Long userId,
                          String username, String userMessage) {
        log.info("[PlanExecutor] executing plan: complexity={}, steps={}, chartNeeded={}, needSummary={}",
            plan.getComplexity(),
            plan.getSqlSteps() != null ? plan.getSqlSteps().size() : 0,
            plan.getChart() != null && plan.getChart().isNeeded(),
            plan.isNeedSummary());

        PlanValidator.ValidationResult validation = planValidator.validate(plan, datasourceId);
        if (!validation.isValid()) {
            log.warn("[PlanExecutor] Plan validation REJECTED: {}", validation.getRejectReason());
            return buildErrorResult("Plan校验失败: " + validation.getRejectReason());
        }
        if (validation.isModified()) {
            log.info("[PlanExecutor] Plan auto-corrected: {}", validation.getCorrections());
        }
        if (!validation.getWarnings().isEmpty()) {
            log.warn("[PlanExecutor] Plan validation warnings: {}", validation.getWarnings());
        }

        String sessionId = com.nl2sql.common.context.UserContext.getSessionId();

        try {
            Map<String, Object> paramValidation = callTool("validateParams", Map.of(
                "question", userMessage,
                "datasourceId", datasourceId,
                "userId", userId,
                "username", username != null ? username : "",
                "sqlOnly", false
            ));

            if (!isTrue(paramValidation, "validated")) {
                return toJson(paramValidation);
            }

            Map<String, Object> chartIntent = callTool("detectChartIntent", Map.of(
                "question", userMessage
            ));

            String cleanedQuestion = getString(chartIntent, "data.cleanedQuestion");
            if (cleanedQuestion == null || cleanedQuestion.isEmpty()) {
                cleanedQuestion = userMessage;
            }
            boolean chartDetected = isTrue(chartIntent, "data.chartDetected");
            String chartType = getString(chartIntent, "data.chartType");

            if (plan.getChart() != null && plan.getChart().isNeeded() && !chartDetected) {
                chartDetected = true;
                chartType = planChartTypeToString(plan.getChart().getType());
                log.info("[PlanExecutor] plan overrides chart detection: chartNeeded=true, type={}", chartType);
            }

            Map<String, Object> tablePref = callTool("extractTablePreference", Map.of(
                "question", cleanedQuestion
            ));
            String tableHint = getString(tablePref, "data.tablePreference");

            if (tableHint == null && plan.getTables() != null && !plan.getTables().isEmpty()) {
                tableHint = plan.getTables().get(0).getTableName();
                log.info("[PlanExecutor] using plan table hint: {}", tableHint);
            }

            Map<String, Object> conceptResult = callTool("injectIndustryConcept", Map.of(
                "question", cleanedQuestion,
                "datasourceId", datasourceId
            ));
            String enhancedQuestion = getString(conceptResult, "data.enhancedQuestion");
            if (enhancedQuestion == null || enhancedQuestion.isEmpty()) {
                enhancedQuestion = cleanedQuestion;
            }

            Map<String, Object> schemaResult = callTool("retrieveSchema", Map.of(
                "question", enhancedQuestion,
                "datasourceId", datasourceId
            ));
            String schemaInfo = getString(schemaResult, "data.schema");
            if (schemaInfo == null) {
                schemaInfo = toJson(schemaResult);
            }

            String finalSql = null;
            Map<String, Object> execData = null;
            String executedSql = null;
            boolean autoFixed = false;

            List<QueryPlan.SqlStep> sqlSteps = plan.getSqlSteps();
            if (sqlSteps == null || sqlSteps.isEmpty()) {
                QueryPlan.SqlStep defaultStep = new QueryPlan.SqlStep();
                defaultStep.setOrder(1);
                defaultStep.setDescription(userMessage);
                sqlSteps = List.of(defaultStep);
            }

            StepReflector reflector = new StepReflector();

            for (int stepIdx = 0; stepIdx < sqlSteps.size(); stepIdx++) {
                QueryPlan.SqlStep step = sqlSteps.get(stepIdx);
                String stepQuestion = step.getDescription() != null ? step.getDescription() : userMessage;

                log.info("[PlanExecutor] executing SQL step {}/{}: {}", stepIdx + 1, sqlSteps.size(), stepQuestion);

                Map<String, Object> sqlResult = callTool("generateSQL", Map.of(
                    "question", stepQuestion,
                    "datasourceId", datasourceId,
                    "schemaInfo", schemaInfo,
                    "tableHint", tableHint != null ? tableHint : ""
                ));

                if (!isTrue(sqlResult, "success")) {
                    log.warn("[PlanExecutor] SQL generation failed for step {}", stepIdx + 1);
                    if (stepIdx == 0) {
                        return buildErrorResult("SQL生成失败");
                    }
                    continue;
                }

                String generatedSql = getString(sqlResult, "data.sql");
                if (generatedSql == null || generatedSql.isEmpty()) {
                    log.warn("[PlanExecutor] generated SQL is empty for step {}", stepIdx + 1);
                    if (stepIdx == 0) {
                        return buildErrorResult("生成的SQL为空");
                    }
                    continue;
                }

                String currentSql = generatedSql;

                Map<String, Object> riskCheck = callTool("quickRiskCheck", Map.of(
                    "sql", currentSql
                ));

                boolean isSimple = isTrue(riskCheck, "data.isSimple");
                String riskLevel = getString(riskCheck, "data.riskLevel");

                if (!isSimple) {
                    log.info("[PlanExecutor] non-simple SQL, running EXPLAIN analysis");
                    Map<String, Object> explainResult = callTool("analyzeQueryPlan", Map.of(
                        "sql", currentSql,
                        "datasourceId", datasourceId
                    ));

                    String explainRiskLevel = getString(explainResult, "data.riskLevel");
                    if ("HIGH".equals(explainRiskLevel)) {
                        log.info("[PlanExecutor] HIGH risk detected, attempting LLM regeneration");
                        Map<String, Object> regenResult = callTool("regenerateSQLWithLLM", Map.of(
                            "originalSql", currentSql,
                            "question", stepQuestion,
                            "explainRisks", getString(explainResult, "data.risks"),
                            "explainSuggestions", getString(explainResult, "data.suggestions") != null
                                ? getString(explainResult, "data.suggestions") : ""
                        ));

                        if (isTrue(regenResult, "data.regenerationSuccess")) {
                            String optimizedSql = getString(regenResult, "data.optimizedSql");
                            if (optimizedSql != null && !optimizedSql.isEmpty()) {
                                Map<String, Object> reExplain = callTool("analyzeQueryPlan", Map.of(
                                    "sql", optimizedSql,
                                    "datasourceId", datasourceId
                                ));
                                String reRiskLevel = getString(reExplain, "data.riskLevel");
                                if (!"HIGH".equals(reRiskLevel)) {
                                    currentSql = optimizedSql;
                                    log.info("[PlanExecutor] optimized SQL passed re-check");
                                } else {
                                    log.warn("[PlanExecutor] optimized SQL still HIGH risk, using original");
                                }
                            }
                        }
                    } else if ("MEDIUM".equals(explainRiskLevel)) {
                        log.info("[PlanExecutor] MEDIUM risk, getting optimization suggestion");
                        callTool("getLLMOptimizationSuggestion", Map.of(
                            "sql", currentSql,
                            "question", stepQuestion,
                            "explainRisks", getString(explainResult, "data.risks") != null
                                ? getString(explainResult, "data.risks") : ""
                        ));
                    }
                }

                Map<String, Object> execResult = callTool("executeRawSQL", Map.of(
                    "sql", currentSql,
                    "datasourceId", datasourceId,
                    "userId", userId,
                    "username", username != null ? username : ""
                ));

                boolean execSuccess = isTrue(execResult, "data.success");

                if (!execSuccess) {
                    String errorMsg = getString(execResult, "data.error");
                    log.info("[PlanExecutor] execution failed, attempting auto-fix: {}", errorMsg);

                    Map<String, Object> fixResult = callTool("autoFixSQL", Map.of(
                        "failedSql", currentSql,
                        "errorMessage", errorMsg != null ? errorMsg : "Unknown error"
                    ));

                    String fixedSql = getString(fixResult, "data.fixedSql");
                    if (fixedSql != null && !fixedSql.isEmpty()) {
                        Map<String, Object> retryResult = callTool("executeRawSQL", Map.of(
                            "sql", fixedSql,
                            "datasourceId", datasourceId,
                            "userId", userId,
                            "username", username != null ? username : ""
                        ));

                        if (isTrue(retryResult, "data.success")) {
                            execResult = retryResult;
                            currentSql = fixedSql;
                            autoFixed = true;
                            execSuccess = true;
                            log.info("[PlanExecutor] auto-fix succeeded");
                        }
                    }
                }

                if (execSuccess) {
                    callTool("ragLearnFromExecution", Map.of(
                        "question", stepQuestion,
                        "sql", currentSql,
                        "rowCount", getInt(execResult, "data.rowCount"),
                        "executionTimeMs", getLong(execResult, "data.executionTime")
                    ));
                }

                ReflectionResult reflection = reflector.reflect(step, stepIdx, sqlSteps.size(), execResult, plan);
                log.info("[PlanExecutor] [Reflection] step {}: decision={}, reason={}",
                    stepIdx + 1, reflection.getDecision(), reflection.getReason());

                switch (reflection.getDecision()) {
                    case CONTINUE:
                        break;
                    case REPLAN:
                        if (reflection.getSuggestions() != null && !reflection.getSuggestions().isEmpty()) {
                            log.info("[PlanExecutor] [Reflection] REPLAN suggestions: {}", reflection.getSuggestions());
                            if (reflection.getSuggestions().stream().anyMatch(s -> s.contains("chart") || s.contains("PIE") || s.contains("BAR"))) {
                                if (plan.getChart() != null) {
                                    plan.getChart().setType(QueryPlan.ChartPlan.ChartType.BAR);
                                    chartType = "bar";
                                    log.info("[PlanExecutor] [Reflection] chart type corrected to BAR");
                                }
                            }
                        }
                        if (stepIdx == 0 && sqlSteps.size() > 1) {
                            log.info("[PlanExecutor] [Reflection] first step triggered REPLAN, simplifying to single step");
                            QueryPlan.SqlStep simplifiedStep = new QueryPlan.SqlStep();
                            simplifiedStep.setOrder(1);
                            simplifiedStep.setDescription(userMessage);
                            sqlSteps = List.of(simplifiedStep);
                            stepIdx = -1;
                            reflector.resetReplanCount();
                            continue;
                        }
                        break;
                    case TERMINATE:
                        log.info("[PlanExecutor] [Reflection] TERMINATE: {}", reflection.getReason());
                        if (execData != null) {
                            break;
                        }
                        return buildErrorResult("Plan执行终止: " + reflection.getReason());
                }

                finalSql = currentSql;
                executedSql = currentSql;
                execData = execResult;

                step.setGeneratedSql(currentSql);
                step.setValidatedSql(currentSql);
            }

            if (execData == null) {
                return buildErrorResult("所有SQL步骤执行失败");
            }

            Object dataRows = getNestedValue(execData, "data.data");
            int rowCount = getInt(execData, "data.rowCount");
            double executionTime = getDouble(execData, "data.executionTime");
            String dataJson = dataRows != null ? toJson(dataRows) : "[]";

            Map<String, Object> followUpResult = callTool("generateFollowUpSuggestions", Map.of(
                "sql", finalSql,
                "rowCount", rowCount,
                "dataJson", dataJson
            ));

            String chartConfigJson = null;
            if (chartDetected && chartType != null) {
                log.info("[PlanExecutor] generating chart config: type={}", chartType);
                Map<String, Object> chartResult = callTool("generateChartConfig", Map.of(
                    "chartType", chartType,
                    "dataJson", dataJson
                ));
                chartConfigJson = getString(chartResult, "data.echartsConfig");
            }

            String summaryText = null;
            if (plan.isNeedSummary()) {
                log.info("[PlanExecutor] generating AI summary");
                Map<String, Object> summaryResult = callTool("summarize_result", Map.of(
                    "userQuery", userMessage,
                    "sql", finalSql,
                    "data", dataJson
                ));
                if (summaryResult != null) {
                    Object msgObj = summaryResult.get("message");
                    if (msgObj == null) msgObj = getNestedValue(summaryResult, "data.message");
                    summaryText = msgObj != null ? msgObj.toString() : null;
                }
            }

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", true);
            response.put("type", "data");
            response.put("data", dataRows);
            response.put("rowCount", rowCount);
            response.put("executionTime", executionTime);
            response.put("sql", executedSql);
            response.put("datasourceId", datasourceId);

            if (autoFixed) {
                response.put("autoFixed", true);
                response.put("originalSql", plan.getSqlSteps().get(0).getGeneratedSql());
            }

            Object followUpSuggestions = getNestedValue(followUpResult, "data.followUpSuggestions");
            if (followUpSuggestions != null) {
                response.put("followUpSuggestions", followUpSuggestions);
            }

            if (chartConfigJson != null) {
                response.put("chartConfig", chartConfigJson);
            }

            if (summaryText != null) {
                response.put("aiSummary", summaryText);
            }

            if (plan.getComplexity() != null) {
                response.put("planComplexity", plan.getComplexity().name());
            }

            response.put("reflectionReplanCount", reflector.getReplanCount());

            log.info("[PlanExecutor] plan execution completed: rowCount={}, autoFixed={}, chart={}, summary={}, replans={}",
                rowCount, autoFixed, chartConfigJson != null, summaryText != null, reflector.getReplanCount());

            return toJson(response);

        } catch (Exception e) {
            log.error("[PlanExecutor] execution failed", e);
            return buildErrorResult("Plan执行失败: " + e.getMessage());
        }
    }

    private Map<String, Object> callTool(String toolName, Map<String, Object> args) {
        try {
            Map<String, Object> cleanArgs = new HashMap<>(args);
            cleanArgs.entrySet().removeIf(e -> e.getValue() == null);

            Object result = toolRegistry.callTool(toolName, cleanArgs);
            if (result instanceof String) {
                return objectMapper.readValue((String) result, Map.class);
            } else if (result instanceof Map) {
                return (Map<String, Object>) result;
            }
            return Map.of("success", false);
        } catch (Exception e) {
            log.warn("[PlanExecutor] tool {} call failed: {}", toolName, e.getMessage());
            return Map.of("success", false, "error", e.getMessage());
        }
    }

    private boolean isTrue(Map<String, Object> map, String path) {
        Object val = getNestedValue(map, path);
        if (val == null) return false;
        if (val instanceof Boolean) return (Boolean) val;
        if (val instanceof String) return "true".equalsIgnoreCase((String) val);
        return false;
    }

    private String getString(Map<String, Object> map, String path) {
        Object val = getNestedValue(map, path);
        return val != null ? val.toString() : null;
    }

    private int getInt(Map<String, Object> map, String path) {
        Object val = getNestedValue(map, path);
        if (val instanceof Number) return ((Number) val).intValue();
        if (val instanceof String) {
            try { return Integer.parseInt((String) val); } catch (Exception e) { return 0; }
        }
        return 0;
    }

    private long getLong(Map<String, Object> map, String path) {
        Object val = getNestedValue(map, path);
        if (val instanceof Number) return ((Number) val).longValue();
        if (val instanceof String) {
            try { return Long.parseLong((String) val); } catch (Exception e) { return 0L; }
        }
        return 0L;
    }

    private double getDouble(Map<String, Object> map, String path) {
        Object val = getNestedValue(map, path);
        if (val instanceof Number) return ((Number) val).doubleValue();
        if (val instanceof String) {
            try { return Double.parseDouble((String) val); } catch (Exception e) { return 0.0; }
        }
        return 0.0;
    }

    @SuppressWarnings("unchecked")
    private Object getNestedValue(Map<String, Object> map, String path) {
        if (map == null || path == null) return null;
        String[] parts = path.split("\\.");
        Object current = map;
        for (String part : parts) {
            if (current instanceof Map) {
                current = ((Map<String, Object>) current).get(part);
            } else {
                return null;
            }
        }
        return current;
    }

    private String planChartTypeToString(QueryPlan.ChartPlan.ChartType type) {
        if (type == null) return null;
        return switch (type) {
            case BAR -> "bar";
            case LINE -> "line";
            case PIE -> "pie";
            case AREA -> "area";
            case SCATTER -> "scatter";
            case TABLE -> "table";
            default -> null;
        };
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return "{\"success\":false,\"error\":\"JSON serialization failed\"}";
        }
    }

    private String buildErrorResult(String error) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", false);
        result.put("type", "error");
        result.put("error", error);
        return toJson(result);
    }
}
