package com.nl2sql.core.agent.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nl2sql.common.event.StreamProgressEvent;
import com.nl2sql.core.agent.planner.QueryPlan;
import com.nl2sql.core.agent.tools.CorrectSqlTool;
import com.nl2sql.core.agent.tools.ExecuteSQLTool;
import com.nl2sql.core.agent.tools.RetrieveTableSchemaTool;
import com.nl2sql.core.agent.tools.SQLExecutionTool;
import com.nl2sql.core.agent.tools.ValidateSQLTool;
import com.nl2sql.core.executor.SQLRiskAnalyzer;
import com.nl2sql.core.llm.LLMService;
import com.nl2sql.core.llm.extension.IndustryConceptExtension;
import com.nl2sql.core.service.NL2SQLService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
public class SqlWorker implements Worker {

    @Autowired
    private NL2SQLService nl2sqlService;

    @Autowired(required = false)
    private RetrieveTableSchemaTool retrieveTableSchemaTool;

    @Autowired(required = false)
    private ValidateSQLTool validateSQLTool;

    @Autowired(required = false)
    private ExecuteSQLTool executeSQLTool;

    @Autowired(required = false)
    private SQLExecutionTool sqlExecutionTool;

    @Autowired(required = false)
    private CorrectSqlTool correctSqlTool;

    @Autowired(required = false)
    private SQLRiskAnalyzer riskAnalyzer;

    @Autowired
    private LLMService llmService;

    @Autowired(required = false)
    private ApplicationEventPublisher eventPublisher;

    @Autowired(required = false)
    private List<IndustryConceptExtension> conceptExtensions;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final int MAX_RETRY = 2;

    private static final Pattern TABLE_PREFERENCE_PATTERN =
        Pattern.compile("(?:使用|用|从|基于)(\\w+)表");

    private static final Pattern CHART_TYPE_PATTERN =
        Pattern.compile("(柱状图|bar\\s*chart|bar|折线图|line\\s*chart|line|饼图|pie\\s*chart|pie|面积图|area\\s*chart|area)", Pattern.CASE_INSENSITIVE);

    private static final Pattern CHART_DESC_PATTERN =
        Pattern.compile("并生成[柱状折线饼面积]*图|并画出[柱状折线饼面积]*图|并展示[柱状折线饼面积]*图|生成[柱状折线饼面积]*图|画出[柱状折线饼面积]*图|展示[柱状折线饼面积]*图");

    private static final Pattern PRIMARY_KEY_PATTERN =
        Pattern.compile("WHERE\\s+\\w*_?id\\s*=\\s*\\d+", Pattern.CASE_INSENSITIVE);

    @Override
    public String getWorkerType() {
        return "sql";
    }

    @Override
    public Worker.WorkerResult execute(Worker.WorkerContext context) {
        QueryPlan plan = context.getPlan();
        Long datasourceId = context.getDatasourceId();
        String userQuestion = context.getUserMessage();
        String sessionId = resolveSessionId(context);

        log.info("[SqlWorker] 开始执行，SQL步骤数: {}, datasourceId: {}",
            plan.getSqlSteps().size(), datasourceId);

        publishProgress(sessionId, "generating_sql", "🤖 AI生成SQL...");

        Map<String, Object> allResults = new LinkedHashMap<>();
        List<Map<String, Object>> stepResults = new ArrayList<>();
        String mediumRiskSuggestion = null;

        for (QueryPlan.SqlStep step : plan.getSqlSteps()) {
            log.info("[SqlWorker] 执行步骤 {}: {}", step.getOrder(), step.getDescription());

            try {
                String enhancedQuestion = enhanceQuestion(userQuestion, datasourceId);

                String sql = generateSql(step.getDescription(), plan, datasourceId, enhancedQuestion);
                if (sql == null || sql.trim().isEmpty() || sql.startsWith("错误：") || sql.startsWith("ERROR:")) {
                    publishProgress(sessionId, "sql_generation_failed", "⚠️ SQL生成失败，尝试修正...");
                    return Worker.WorkerResult.failure("sql",
                        "SQL 生成失败: " + (sql != null ? sql : "返回为空"));
                }

                if (sql.startsWith("CLARIFY_") || sql.startsWith("CLARIFICATION")) {
                    publishProgress(sessionId, "clarification_needed", "⚠️ " + sql);
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("type", "clarification");
                    data.put("needsClarification", true);
                    data.put("clarificationMessage", sql);
                    return Worker.WorkerResult.success("sql", data);
                }

                sql = optimizeSQL(sql);
                step.setGeneratedSql(sql);
                publishProgress(sessionId, "sql_generated", "✅ SQL生成完成");
                log.info("[SqlWorker] SQL 生成成功: {}", sql.substring(0, Math.min(100, sql.length())));

                String validationError = validateSql(sql, datasourceId);
                if (validationError != null) {
                    log.warn("[SqlWorker] SQL 校验失败: {}", validationError);
                    step.setLastError(validationError);
                    String correctedSql = retryWithCorrection(sql, validationError, userQuestion, datasourceId);
                    if (correctedSql != null) {
                        step.setValidatedSql(correctedSql);
                        step.setRequiresRetry(true);
                        sql = correctedSql;
                    } else {
                        return Worker.WorkerResult.failure("sql", "SQL 纠错失败: " + validationError);
                    }
                } else {
                    step.setValidatedSql(sql);
                }

                RiskAssessment riskAssessment = assessSqlRisk(sql, datasourceId, userQuestion, sessionId);
                if (riskAssessment.blocked) {
                    log.info("[SqlWorker] ⚠️ 高风险SQL需要人工确认");
                    Map<String, Object> approvalData = new LinkedHashMap<>();
                    approvalData.put("type", "human_approval_required");
                    approvalData.put("approvalId", "sql_" + System.currentTimeMillis());
                    approvalData.put("riskLevel", "HIGH");
                    approvalData.put("riskReason", riskAssessment.reason);
                    approvalData.put("sql", riskAssessment.optimizedSql != null ? riskAssessment.optimizedSql : sql);
                    approvalData.put("message", "该SQL存在高风险，请审核后再决定是否执行");
                    if (riskAssessment.optimizedSql != null) {
                        approvalData.put("optimizationSuggestion",
                            "⚠️ 高风险SQL，已阻断执行\n\n📋 风险原因：\n" + riskAssessment.reason +
                            "\n\n💡 LLM 已尝试优化，生成新 SQL：\n" + riskAssessment.optimizedSql +
                            "\n\n👉 请人工审核上述优化后的 SQL，确认安全后再执行。");
                    }
                    Worker.WorkerResult result = Worker.WorkerResult.success("sql", approvalData);
                    result.setWaitingForApproval(true);
                    result.setRawOutput(serialize(approvalData));
                    return result;
                }
                if (riskAssessment.mediumRisk) {
                    mediumRiskSuggestion = riskAssessment.suggestion;
                }
                if (riskAssessment.optimizedSql != null && !riskAssessment.blocked) {
                    sql = riskAssessment.optimizedSql;
                    step.setValidatedSql(sql);
                    log.info("[SqlWorker] ✅ 使用LLM优化后的SQL: {}", sql.substring(0, Math.min(100, sql.length())));
                }

                publishProgress(sessionId, "executing_sql", "⚙️ 执行SQL查询...");
                SQLExecutionTool.ExecutionResult execResult = executeSqlWithAutoFix(
                    sql, datasourceId, context.getUserId(), context.getUsername(),
                    userQuestion, sessionId);

                if (!execResult.isSuccess()) {
                    publishProgress(sessionId, "execution_failed", "❌ 执行失败: " + execResult.getError());
                    Map<String, Object> failData = new LinkedHashMap<>();
                    failData.put("type", "error");
                    failData.put("error", execResult.getError());
                    return Worker.WorkerResult.failure("sql", execResult.getError());
                }

                publishProgress(sessionId, "query_completed", "✅ 查询完成，共 " + execResult.getRowCount() + " 条结果");

                Map<String, Object> stepData = new LinkedHashMap<>();
                stepData.put("order", step.getOrder());
                stepData.put("description", step.getDescription());
                stepData.put("sql", execResult.getSql() != null ? execResult.getSql() : step.getValidatedSql());
                stepData.put("data", execResult.getData());
                stepData.put("rowCount", execResult.getRowCount());
                stepData.put("executionTime", execResult.getExecutionTime());
                stepResults.add(stepData);

            } catch (Exception e) {
                log.error("[SqlWorker] 步骤 {} 执行异常", step.getOrder(), e);
                publishProgress(sessionId, "error_occurred", "❌ 执行异常: " + e.getMessage());
                return Worker.WorkerResult.failure("sql", e.getMessage());
            }
        }

        allResults.put("steps", stepResults);
        if (!stepResults.isEmpty()) {
            Map<String, Object> lastStep = stepResults.get(stepResults.size() - 1);
            allResults.put("data", lastStep.get("data"));
            allResults.put("sql", lastStep.get("sql"));
            allResults.put("rowCount", lastStep.get("rowCount"));
            allResults.put("executionTime", lastStep.get("executionTime"));
        }
        allResults.put("datasourceId", datasourceId);

        // ✅ 只在有实际值时才添加 optimizationSuggestion
        if (mediumRiskSuggestion != null && !mediumRiskSuggestion.trim().isEmpty()) {
            allResults.put("optimizationSuggestion", mediumRiskSuggestion);
        }

        List<Map<String, String>> followUpSuggestions = generateFollowUpSuggestions(
            extractData(allResults), extractInt(allResults, "rowCount"), extractSql(allResults));
        if (!followUpSuggestions.isEmpty()) {
            allResults.put("followUpSuggestions", followUpSuggestions);
        }

        return Worker.WorkerResult.success("sql", allResults);
    }

    private String generateSql(String description, QueryPlan plan, Long datasourceId, String userQuestion) {
        try {
            if (retrieveTableSchemaTool != null && !plan.getTables().isEmpty()) {
                String schema = retrieveTableSchemaTool.execute(userQuestion, datasourceId);
                return nl2sqlService.generateSQLWithSchema(userQuestion, schema, datasourceId);
            } else {
                return nl2sqlService.generateSQL(userQuestion, datasourceId);
            }
        } catch (Exception e) {
            log.error("[SqlWorker] SQL 生成异常", e);
            return "错误：" + e.getMessage();
        }
    }

    private String validateSql(String sql, Long datasourceId) {
        if (validateSQLTool == null) return null;
        try {
            String validationResult = validateSQLTool.validateSQL(sql, datasourceId);
            if (validationResult != null && validationResult.contains("\"valid\":false")) {
                return extractErrorMessage(validationResult);
            }
            return null;
        } catch (Exception e) {
            log.warn("[SqlWorker] SQL 校验异常，跳过", e);
            return null;
        }
    }

    private String retryWithCorrection(String failedSql, String error, String question, Long datasourceId) {
        if (correctSqlTool != null) {
            String corrected = correctSqlTool.correctSql(failedSql, error, question, datasourceId, MAX_RETRY);
            if (corrected != null && !corrected.trim().isEmpty()) {
                return corrected;
            }
        }
        try {
            return nl2sqlService.autoFixSQL(failedSql, error, datasourceId);
        } catch (Exception e) {
            log.warn("[SqlWorker] LLM降级纠错失败", e);
            return null;
        }
    }

    private SQLExecutionTool.ExecutionResult executeSqlWithAutoFix(
            String sql, Long datasourceId, Long userId, String username,
            String userQuestion, String sessionId) {

        String currentSql = sql;

        for (int attempt = 0; attempt <= MAX_RETRY; attempt++) {
            try {
                if (attempt > 0) {
                    publishProgress(sessionId, "retrying_sql", "🔄 第" + attempt + "次重试...");
                }

                if (sqlExecutionTool != null) {
                    String jsonResult = sqlExecutionTool.executeSQL(currentSql, datasourceId, userId, username);
                    SQLExecutionTool.ExecutionResult result = objectMapper.readValue(jsonResult, SQLExecutionTool.ExecutionResult.class);
                    if (result.isSuccess()) {
                        return result;
                    }

                    if (attempt < MAX_RETRY) {
                        publishProgress(sessionId, "correcting_sql", "🔧 自动修正SQL...");
                        currentSql = correctSql(currentSql, result.getError(), userQuestion, datasourceId);
                    }
                } else if (executeSQLTool != null) {
                    String jsonResult = executeSQLTool.executeSQL(currentSql, datasourceId);
                    Map<String, Object> parsed = parseJson(jsonResult);
                    boolean success = !"error".equals(parsed.get("status"));
                    if (success) {
                        SQLExecutionTool.ExecutionResult execResult = new SQLExecutionTool.ExecutionResult(
                            true, extractRowsFromParsed(parsed), extractIntFromMap(parsed, "rowCount"),
                            extractDoubleFromMap(parsed, "executionTimeMs"), null, currentSql);
                        return execResult;
                    }
                    if (attempt < MAX_RETRY) {
                        publishProgress(sessionId, "correcting_sql", "🔧 自动修正SQL...");
                        String errorMsg = (String) parsed.getOrDefault("message", jsonResult);
                        currentSql = correctSql(currentSql, errorMsg, userQuestion, datasourceId);
                    }
                } else {
                    return new SQLExecutionTool.ExecutionResult(false, null, 0, 0.0, "SQL执行工具未注册");
                }

            } catch (Exception e) {
                if (isOfflineError(e)) {
                    log.warn("[SqlWorker] 离线模式：无法连接数据库，返回SQL");
                    publishProgress(sessionId, "offline_mode", "⚠️ 离线模式：无法连接数据库，已生成SQL供手动执行");
                    return new SQLExecutionTool.ExecutionResult(true, Collections.emptyList(), 0, 0.0, null, currentSql);
                }

                if (attempt < MAX_RETRY) {
                    publishProgress(sessionId, "correcting_error", "🔧 修正执行错误...");
                    currentSql = correctSql(currentSql, e.getMessage(), userQuestion, datasourceId);
                } else {
                    return new SQLExecutionTool.ExecutionResult(false, null, 0, 0.0, e.getMessage());
                }
            }
        }

        return new SQLExecutionTool.ExecutionResult(false, null, 0, 0.0, "SQL执行失败，已尝试" + MAX_RETRY + "次修正");
    }

    private String correctSql(String failedSql, String error, String question, Long datasourceId) {
        if (correctSqlTool != null) {
            try {
                String corrected = correctSqlTool.correctSql(failedSql, error, question, datasourceId, 1);
                if (corrected != null && !corrected.trim().isEmpty()) {
                    return corrected;
                }
            } catch (Exception e) {
                log.warn("[SqlWorker] CorrectSqlTool纠错失败，降级LLM", e);
            }
        }
        try {
            return nl2sqlService.autoFixSQL(failedSql, error, datasourceId);
        } catch (Exception e) {
            log.warn("[SqlWorker] LLM降级纠错也失败", e);
            return failedSql;
        }
    }

    private RiskAssessment assessSqlRisk(String sql, Long datasourceId, String userQuestion, String sessionId) {
        RiskAssessment assessment = new RiskAssessment();

        if (riskAnalyzer == null) {
            return assessment;
        }

        try {
            if (sql.startsWith("CLARIFICATION") || sql.startsWith("CLARIFY_") ||
                sql.startsWith("错误：") || sql.startsWith("ERROR:")) {
                return assessment;
            }

            if (isSimpleQuery(sql)) {
                log.info("[SqlWorker] ✅ 简单查询，跳过EXPLAIN");
                return assessment;
            }

            publishProgress(sessionId, "assessing_risk", "🔍 评估SQL风险...");
            SQLRiskAnalyzer.RiskAnalysisResult explainResult = riskAnalyzer.analyzeRisk(sql, datasourceId);
            String riskLevel = explainResult.getRiskLevel();

            if ("LOW".equals(riskLevel)) {
                publishProgress(sessionId, "risk_low", "✅ 风险评估通过");
                return assessment;
            }

            if ("MEDIUM".equals(riskLevel)) {
                publishProgress(sessionId, "risk_medium", "⚠️ 中风险SQL，继续执行");
                assessment.mediumRisk = true;
                assessment.reason = String.join("; ", explainResult.getRisks());

                try {
                    String suggestionPrompt = buildOptimizationSuggestionPrompt(sql, userQuestion, explainResult);
                    String llmResponse = llmService.generateAnswer(suggestionPrompt);
                    if (llmResponse != null) {
                        assessment.suggestion = "⚠️ 中风险SQL，继续执行但请注意以下优化建议：\n\n" + extractOptimizationFromLlm(llmResponse);
                    } else {
                        assessment.suggestion = "⚠️ 中风险SQL，继续执行\n\n📋 风险点：\n" + assessment.reason;
                    }
                } catch (Exception e) {
                    log.warn("[SqlWorker] LLM优化建议获取失败", e);
                    assessment.suggestion = "⚠️ 中风险SQL，继续执行\n\n📋 风险点：\n" + assessment.reason;
                }
                return assessment;
            }

            if ("HIGH".equals(riskLevel)) {
                publishProgress(sessionId, "risk_blocked", "⚠️ 高风险SQL，尝试LLM优化...");
                try {
                    String regeneratePrompt = buildRegenerateSQLPrompt(sql, userQuestion, explainResult);
                    String regeneratedSql = llmService.generateSQL(regeneratePrompt);
                    regeneratedSql = extractSQLFromResponse(regeneratedSql);

                    if (regeneratedSql != null && !regeneratedSql.trim().isEmpty()) {
                        SQLRiskAnalyzer.RiskAnalysisResult optimizedExplain =
                            riskAnalyzer.analyzeRisk(regeneratedSql, datasourceId);

                        if (!"HIGH".equals(optimizedExplain.getRiskLevel())) {
                            log.info("[SqlWorker] ✅ LLM优化成功，风险从HIGH降到{}", optimizedExplain.getRiskLevel());
                            assessment.optimizedSql = regeneratedSql;
                            if ("MEDIUM".equals(optimizedExplain.getRiskLevel())) {
                                assessment.mediumRisk = true;
                                assessment.reason = String.join("; ", optimizedExplain.getRisks());
                            }
                            return assessment;
                        } else {
                            log.warn("[SqlWorker] ⚠️ LLM优化后仍为高风险，阻断执行");
                            assessment.blocked = true;
                            assessment.reason = "LLM优化后仍为高风险，需要人工介入审核。原始风险：" +
                                String.join("; ", explainResult.getRisks());
                            assessment.optimizedSql = regeneratedSql;
                            return assessment;
                        }
                    }
                } catch (Exception e) {
                    log.warn("[SqlWorker] LLM重新生成SQL失败", e);
                }

                assessment.blocked = true;
                assessment.reason = String.join("; ", explainResult.getRisks());
                return assessment;
            }

        } catch (Exception e) {
            log.error("[SqlWorker] 风险评估异常，降级为静态校验", e);
            return staticRiskAssessment(sql);
        }

        return assessment;
    }

    private RiskAssessment staticRiskAssessment(String sql) {
        RiskAssessment assessment = new RiskAssessment();
        if (sql == null || sql.isEmpty()) return assessment;

        String upperSql = sql.toUpperCase().trim();
        List<String> risks = new ArrayList<>();
        String riskLevel = "LOW";

        if (upperSql.startsWith("SELECT") && !upperSql.contains("WHERE") && !upperSql.contains("LIMIT")) {
            risks.add("⚠️ 无WHERE条件且无LIMIT，可能导致全表扫描");
            riskLevel = "MEDIUM";
        }

        int joinCount = upperSql.split(" JOIN ").length - 1;
        if (joinCount >= 3) {
            risks.add("🔴 多表JOIN（" + joinCount + "个），性能风险高");
            riskLevel = "HIGH";
        } else if (joinCount >= 2) {
            risks.add("⚠️ 多表JOIN（" + joinCount + "个），建议优化");
            if ("LOW".equals(riskLevel)) riskLevel = "MEDIUM";
        }

        int selectCount = countOccurrences(upperSql, "SELECT");
        if (selectCount >= 3) {
            risks.add("🔴 多层子查询嵌套（" + selectCount + "层），性能差");
            riskLevel = "HIGH";
        } else if (selectCount == 2) {
            risks.add("⚠️ 包含子查询，建议优化为JOIN");
            if ("LOW".equals(riskLevel)) riskLevel = "MEDIUM";
        }

        if (upperSql.contains("DROP ") || upperSql.contains("TRUNCATE ") ||
            upperSql.contains("DELETE FROM") || upperSql.contains("UPDATE ")) {
            risks.add("🔴 包含数据修改/删除操作，禁止执行");
            riskLevel = "HIGH";
        }

        if ((joinCount >= 2 || selectCount >= 2) && !upperSql.contains("LIMIT")) {
            risks.add("⚠️ 复杂查询无LIMIT，可能返回大量数据");
            if ("LOW".equals(riskLevel)) riskLevel = "MEDIUM";
        }

        if ("HIGH".equals(riskLevel)) {
            assessment.blocked = true;
            assessment.reason = String.join("; ", risks);
        } else if ("MEDIUM".equals(riskLevel)) {
            assessment.mediumRisk = true;
            assessment.suggestion = "⚠️ 中风险SQL，继续执行但请注意：\n\n" + String.join("\n", risks);
            assessment.reason = String.join("; ", risks);
        }

        return assessment;
    }

    private boolean isSimpleQuery(String sql) {
        if (sql == null || sql.isEmpty()) return false;
        String upperSql = sql.toUpperCase().trim();
        if (!upperSql.startsWith("SELECT")) return false;
        if (upperSql.contains(" JOIN ")) return false;
        if (countOccurrences(upperSql, "SELECT") > 1) return false;
        if (upperSql.contains("COUNT(") || upperSql.contains("SUM(") ||
            upperSql.contains("AVG(") || upperSql.contains("MAX(") ||
            upperSql.contains("MIN(") || upperSql.contains("GROUP BY")) return false;
        if (upperSql.contains("ORDER BY") || upperSql.contains("DISTINCT")) return false;
        return PRIMARY_KEY_PATTERN.matcher(sql).find();
    }

    private String optimizeSQL(String sql) {
        if (sql == null || !sql.contains(" IN (") || !sql.contains("SELECT")) return sql;
        if (sql.matches("(?s).*JOIN.*ON.*\\bIN\\s*\\(\\s*SELECT.*")) {
            log.warn("[SqlWorker] 检测到ON条件中使用IN子查询，建议改为直接JOIN");
        }
        return sql;
    }

    private String enhanceQuestion(String question, Long datasourceId) {
        String enhanced = question;

        String tableHint = extractTablePreference(question);
        if (tableHint != null) {
            log.info("[SqlWorker] 检测到用户指定表: {}", tableHint);
            enhanced = question + " [优先使用表: " + tableHint + "]";
        }

        if (conceptExtensions != null && !conceptExtensions.isEmpty()) {
            for (IndustryConceptExtension extension : conceptExtensions) {
                try {
                    String enhancedPrompt = extension.enhancePromptBeforeGeneration(null, question, datasourceId);
                    if (enhancedPrompt != null && !enhancedPrompt.trim().isEmpty()) {
                        log.info("[SqlWorker] 行业扩展点增强Prompt: {}", extension.getClass().getSimpleName());
                        enhanced = enhanced + "\n\n" + enhancedPrompt;
                        break;
                    }
                } catch (Exception e) {
                    log.warn("[SqlWorker] 行业扩展点执行失败: {}", e.getMessage());
                }
            }
        }

        return enhanced;
    }

    public static String extractChartTypeFromQuestion(String question) {
        if (question == null || question.isEmpty()) return null;
        Matcher matcher = CHART_TYPE_PATTERN.matcher(question);
        if (matcher.find()) {
            String match = matcher.group(1).toLowerCase();
            if (match.contains("柱状") || match.equals("bar")) return "bar";
            if (match.contains("折线") || match.equals("line")) return "line";
            if (match.contains("饼") || match.equals("pie")) return "pie";
            if (match.contains("面积") || match.equals("area")) return "area";
        }
        return null;
    }

    public static String removeChartDescription(String question) {
        if (question == null || question.isEmpty()) return question;
        return CHART_DESC_PATTERN.matcher(question).replaceAll("").trim();
    }

    private String extractTablePreference(String question) {
        if (question == null) return null;
        Matcher matcher = TABLE_PREFERENCE_PATTERN.matcher(question);
        return matcher.find() ? matcher.group(1) : null;
    }

    private List<Map<String, String>> generateFollowUpSuggestions(
            List<Map<String, Object>> data, int rowCount, String sql) {
        List<Map<String, String>> suggestions = new ArrayList<>();

        if (data != null && !data.isEmpty() && isStatisticalData(sql)) {
            suggestions.add(Map.of("text", "🤖 AI 总结", "action", "generate_summary"));
        }
        if (data != null && rowCount >= 2 && hasNumericColumn(data) && isStatisticalData(sql)) {
            suggestions.add(Map.of("text", "📊 生成图表", "action", "generate_chart"));
        }
        if (data != null && !data.isEmpty()) {
            suggestions.add(Map.of("text", "💾 下载 Excel", "action", "export_excel"));
        }
        return suggestions;
    }

    private boolean isStatisticalData(String sql) {
        if (sql == null || sql.isEmpty()) return false;
        String upperSql = sql.toUpperCase();
        return upperSql.contains("GROUP BY") || upperSql.contains("COUNT(") ||
               upperSql.contains("SUM(") || upperSql.contains("AVG(") ||
               upperSql.contains("MAX(") || upperSql.contains("MIN(");
    }

    private boolean hasNumericColumn(List<Map<String, Object>> data) {
        if (data == null || data.isEmpty()) return false;
        for (Object value : data.get(0).values()) {
            if (value instanceof Number) return true;
            if (value instanceof String) {
                try { Double.parseDouble((String) value); return true; }
                catch (NumberFormatException ignored) {}
            }
        }
        return false;
    }

    private String buildOptimizationSuggestionPrompt(String sql, String question,
                                                      SQLRiskAnalyzer.RiskAnalysisResult explainResult) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个数据库优化专家。以下SQL存在中等风险，请提供优化建议。\n\n");
        sb.append("用户问题：").append(question).append("\n\n");
        sb.append("原始 SQL：\n").append(sql).append("\n\n");
        sb.append("EXPLAIN 分析结果：\n");
        sb.append("- 风险等级：").append(explainResult.getRiskLevel()).append("\n");
        if (explainResult.getRisks() != null && !explainResult.getRisks().isEmpty()) {
            sb.append("- 发现的风险点：\n");
            for (String risk : explainResult.getRisks()) {
                sb.append("  - ").append(risk).append("\n");
            }
        }
        sb.append("\n请提供优化建议（不要重新生成SQL，只给建议）：\n");
        sb.append("1. 指出主要性能瓶颈\n");
        sb.append("2. 给出具体的优化建议\n");
        sb.append("3. 说明预期优化效果\n\n");
        sb.append("返回JSON格式：\n");
        sb.append("{\"bottleneck\":\"主要性能瓶颈\",\"suggestion\":\"具体优化建议\",\"expected_improvement\":\"预期优化效果\"}");
        return sb.toString();
    }

    private String buildRegenerateSQLPrompt(String originalSql, String question,
                                             SQLRiskAnalyzer.RiskAnalysisResult explainResult) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个数据库专家。之前生成的SQL存在高风险，请重新生成一个更优化的SQL。\n\n");
        sb.append("用户问题：").append(question).append("\n\n");
        sb.append("❌ 原始 SQL（有高风险）：\n").append(originalSql).append("\n\n");
        sb.append("⚠️ EXPLAIN 分析发现的风险：\n");
        if (explainResult.getRisks() != null) {
            for (String risk : explainResult.getRisks()) {
                sb.append("  - ").append(risk).append("\n");
            }
        }
        if (explainResult.getSuggestions() != null && !explainResult.getSuggestions().isEmpty()) {
            sb.append("\n💡 优化建议：\n");
            for (String suggestion : explainResult.getSuggestions()) {
                sb.append("  - ").append(suggestion).append("\n");
            }
        }
        sb.append("\n🎯 任务：重新生成一个SQL，要求：\n");
        sb.append("1. 避免上述风险\n2. 保持查询语义不变\n3. 只输出SQL语句，不要包含其他内容\n\n新 SQL：");
        return sb.toString();
    }

    private String extractSQLFromResponse(String response) {
        if (response == null || response.isEmpty()) return null;
        String cleaned = response.trim()
            .replaceAll("```sql\\s*", "").replaceAll("```\\s*$", "").trim();
        if (cleaned.contains("\n")) {
            for (String line : cleaned.split("\n")) {
                String trimmed = line.trim();
                if (trimmed.toUpperCase().startsWith("SELECT") ||
                    trimmed.toUpperCase().startsWith("WITH")) {
                    return trimmed;
                }
            }
        }
        return cleaned;
    }

    private String extractOptimizationFromLlm(String llmResponse) {
        try {
            int jsonStart = llmResponse.indexOf("{");
            int jsonEnd = llmResponse.lastIndexOf("}");
            if (jsonStart >= 0 && jsonEnd > jsonStart) {
                String jsonStr = llmResponse.substring(jsonStart, jsonEnd + 1);
                Map<String, Object> json = objectMapper.readValue(jsonStr, Map.class);
                StringBuilder sb = new StringBuilder();
                if (json.get("bottleneck") != null) {
                    sb.append("🔍 性能瓶颈：\n").append(json.get("bottleneck")).append("\n\n");
                }
                if (json.get("suggestion") != null) {
                    sb.append("💡 优化建议：\n").append(json.get("suggestion")).append("\n\n");
                }
                if (json.get("expected_improvement") != null) {
                    sb.append("📈 预期效果：\n").append(json.get("expected_improvement"));
                }
                return sb.length() > 0 ? sb.toString() : llmResponse;
            }
        } catch (Exception e) {
            log.debug("[SqlWorker] 解析LLM优化建议JSON失败，返回原文");
        }
        return llmResponse;
    }

    private boolean isOfflineError(Exception e) {
        String msg = e.getMessage();
        if (msg == null) return false;
        return msg.contains("Communications link failure") ||
               msg.contains("Connection refused") ||
               msg.contains("Connect timed out") ||
               msg.contains("Unknown host") ||
               msg.contains("Cannot create PoolableConnectionFactory");
    }

    private void publishProgress(String sessionId, String step, String message) {
        if (eventPublisher == null || sessionId == null) return;
        try {
            eventPublisher.publishEvent(new StreamProgressEvent(this, sessionId, step, message, null));
        } catch (Exception e) {
            log.debug("[SqlWorker] 发布进度事件失败: {}", e.getMessage());
        }
    }

    private String resolveSessionId(Worker.WorkerContext context) {
        try {
            Object sessionId = context.getPreviousResultData("_meta", "sessionId");
            return sessionId != null ? sessionId.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private String extractErrorMessage(String json) {
        try {
            Map<String, Object> map = objectMapper.readValue(json, Map.class);
            return (String) map.getOrDefault("error", "未知校验错误");
        } catch (Exception e) {
            return json;
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractRowsFromParsed(Map<String, Object> parsed) {
        Object dataObj = parsed.get("data");
        if (dataObj instanceof Map) {
            Object rows = ((Map<String, Object>) dataObj).get("rows");
            if (rows instanceof List) return (List<Map<String, Object>>) rows;
        }
        return Collections.emptyList();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractData(Map<String, Object> allResults) {
        Object data = allResults.get("data");
        if (data instanceof List) return (List<Map<String, Object>>) data;
        return null;
    }

    private String extractSql(Map<String, Object> allResults) {
        Object sql = allResults.get("sql");
        return sql != null ? sql.toString() : null;
    }

    private int extractInt(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val instanceof Number) return ((Number) val).intValue();
        return 0;
    }

    private int extractIntFromMap(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val instanceof Number) return ((Number) val).intValue();
        return 0;
    }

    private Double extractDoubleFromMap(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val instanceof Number) return ((Number) val).doubleValue();
        return 0.0;
    }

    private Map<String, Object> parseJson(String json) {
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            return Map.of("rawResult", json != null ? json : "");
        }
    }

    private String serialize(Map<String, Object> data) {
        try {
            return objectMapper.writeValueAsString(data);
        } catch (Exception e) {
            return "{}";
        }
    }

    private int countOccurrences(String str, String sub) {
        int count = 0;
        int idx = 0;
        while ((idx = str.indexOf(sub, idx)) != -1) {
            count++;
            idx += sub.length();
        }
        return count;
    }

    private static class RiskAssessment {
        boolean blocked = false;
        boolean mediumRisk = false;
        String reason;
        String suggestion;
        String optimizedSql;
    }
}
