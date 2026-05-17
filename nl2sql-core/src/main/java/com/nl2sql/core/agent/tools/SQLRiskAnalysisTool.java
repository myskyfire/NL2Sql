package com.nl2sql.core.agent.tools;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nl2sql.core.executor.SQLRiskAnalyzer;
import com.nl2sql.core.llm.LLMService;
import com.nl2sql.core.service.NL2SQLService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Slf4j
@Component
public class SQLRiskAnalysisTool {

    @Autowired
    private SQLRiskAnalyzer riskAnalyzer;

    @Autowired(required = false)
    private LLMService llmService;

    @Autowired(required = false)
    private NL2SQLService nl2sqlService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Tool("在执行复杂SQL之前，先分析其执行计划和潜在风险。支持三层评估：快速判断→EXPLAIN分析→LLM辅助评估。高风险时自动尝试LLM重新生成SQL。返回风险等级(LOW/MEDIUM/HIGH)、风险点、优化建议和优化后的SQL。")
    public String analyzeSQLRisk(
        @P("待分析的SQL语句") String sql,
        @P("数据源ID") Long datasourceId,
        @P("用户的原始问题（用于LLM上下文理解）") String question
    ) {
        try {
            log.info("[SQLRiskAnalysisTool] 开始分析SQL风险: datasourceId={}, hasQuestion={}",
                datasourceId, question != null && !question.isEmpty());

            if (datasourceId == null) {
                return "{\"success\":false,\"error\":\"缺少数据源ID，无法执行风险分析\"}";
            }

            if (sql == null || sql.trim().isEmpty()) {
                return "{\"success\":false,\"error\":\"SQL不能为空\"}";
            }

            if (sql.startsWith("CLARIFICATION") || sql.startsWith("CLARIFY_")
                || sql.startsWith("错误：") || sql.startsWith("ERROR:")) {
                log.info("[SQLRiskAnalysisTool] SQL不是有效查询，跳过风险评估");
                return buildSimpleResponse("LOW", "非有效SQL，无需风险评估", null);
            }

            if (riskAnalyzer == null) {
                log.warn("[SQLRiskAnalysisTool] SQLRiskAnalyzer未注入，跳过风险评估");
                return buildSimpleResponse("LOW", "SQLRiskAnalyzer未配置，默认低风险", null);
            }

            if (isSimpleQuery(sql)) {
                log.info("[SQLRiskAnalysisTool] 快速判断：简单查询，直接放行（跳过EXPLAIN）");
                return buildSimpleResponse("LOW", "简单查询，无需EXPLAIN", null);
            }

            SQLRiskAnalyzer.RiskAnalysisResult explainResult = riskAnalyzer.analyzeRisk(sql, datasourceId);
            String riskLevel = explainResult.getRiskLevel();
            log.info("[SQLRiskAnalysisTool] EXPLAIN分析结果: riskLevel={}", riskLevel);

            if ("LOW".equals(riskLevel)) {
                return buildExplainResponse(explainResult);
            }

            if ("MEDIUM".equals(riskLevel)) {
                log.info("[SQLRiskAnalysisTool] 中风险，调用LLM获取优化建议");
                Map<String, Object> llmSuggestion = null;
                if (llmService != null && question != null && !question.isEmpty()) {
                    llmSuggestion = getLLMOptimizationSuggestion(sql, question, explainResult);
                }
                return buildMediumRiskResponse(explainResult, llmSuggestion);
            }

            log.info("[SQLRiskAnalysisTool] 高风险，调用LLM尝试重新生成SQL");
            String optimizedSql = null;
            if (llmService != null && nl2sqlService != null && question != null && !question.isEmpty()) {
                optimizedSql = regenerateSQLWithLLM(sql, question, explainResult);
            }

            if (optimizedSql != null && !optimizedSql.trim().isEmpty() && !optimizedSql.equals(sql)) {
                log.info("[SQLRiskAnalysisTool] LLM生成了优化SQL，重新EXPLAIN验证");
                try {
                    SQLRiskAnalyzer.RiskAnalysisResult reExplainResult =
                        riskAnalyzer.analyzeRisk(optimizedSql, datasourceId);
                    String newRiskLevel = reExplainResult.getRiskLevel();

                    if (!"HIGH".equals(newRiskLevel)) {
                        log.info("[SQLRiskAnalysisTool] 优化后风险降为{}，使用新SQL", newRiskLevel);
                        return buildOptimizedResponse(explainResult, optimizedSql, newRiskLevel, reExplainResult);
                    }
                } catch (Exception e) {
                    log.warn("[SQLRiskAnalysisTool] 重新EXPLAIN失败: {}", e.getMessage());
                }
            }

            log.info("[SQLRiskAnalysisTool] 优化后仍为高风险，需要人工确认");
            return buildHighRiskResponse(explainResult, optimizedSql);

        } catch (Exception e) {
            log.error("[SQLRiskAnalysisTool] 分析失败", e);
            return "{\"success\":false,\"error\":\"分析失败: " + e.getMessage() + "\"}";
        }
    }

    private boolean isSimpleQuery(String sql) {
        if (sql == null || sql.isEmpty()) {
            return false;
        }

        String upperSql = sql.toUpperCase().trim();

        if (!upperSql.startsWith("SELECT")) {
            return false;
        }

        if (upperSql.contains(" JOIN ")) {
            return false;
        }

        int selectCount = 0;
        for (int i = 0; i < upperSql.length(); i++) {
            if (upperSql.substring(i).startsWith("SELECT")) {
                selectCount++;
            }
        }
        if (selectCount > 1) {
            return false;
        }

        if (upperSql.contains("COUNT(") || upperSql.contains("SUM(")
            || upperSql.contains("AVG(") || upperSql.contains("MAX(")
            || upperSql.contains("MIN(") || upperSql.contains("GROUP BY")) {
            return false;
        }

        if (upperSql.contains("ORDER BY") || upperSql.contains("DISTINCT")) {
            return false;
        }

        Pattern primaryKeyPattern = Pattern.compile("WHERE\\s+\\w*_?id\\s*=\\s*\\d+", Pattern.CASE_INSENSITIVE);
        if (!primaryKeyPattern.matcher(sql).find()) {
            return false;
        }

        return true;
    }

    private Map<String, Object> getLLMOptimizationSuggestion(String sql, String question,
                                                               SQLRiskAnalyzer.RiskAnalysisResult explainResult) {
        try {
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
            sb.append("\n请提供优化建议（不要重新生成 SQL，只给建议）：\n");
            sb.append("1. 指出主要性能瓶颈\n");
            sb.append("2. 给出具体的优化建议（如添加索引、改写 WHERE 条件等）\n");
            sb.append("3. 说明预期优化效果\n\n");
            sb.append("返回JSON格式：\n");
            sb.append("{\n");
            sb.append("  \"bottleneck\": \"主要性能瓶颈\",\n");
            sb.append("  \"suggestion\": \"具体优化建议\",\n");
            sb.append("  \"expected_improvement\": \"预期优化效果\"\n");
            sb.append("}");

            String response = llmService.generateAnswer(sb.toString());
            return parseJsonResponse(response);
        } catch (Exception e) {
            log.warn("[SQLRiskAnalysisTool] LLM优化建议获取失败: {}", e.getMessage());
            return null;
        }
    }

    private String regenerateSQLWithLLM(String originalSql, String question,
                                         SQLRiskAnalyzer.RiskAnalysisResult explainResult) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("你是一个数据库专家。之前生成的SQL存在高风险，请重新生成一个更优化的SQL。\n\n");
            sb.append("用户问题：").append(question).append("\n\n");
            sb.append("原始 SQL（有高风险）：\n").append(originalSql).append("\n\n");
            sb.append("EXPLAIN 分析发现的风险：\n");
            if (explainResult.getRisks() != null && !explainResult.getRisks().isEmpty()) {
                for (String risk : explainResult.getRisks()) {
                    sb.append("  - ").append(risk).append("\n");
                }
            }
            if (explainResult.getSuggestions() != null && !explainResult.getSuggestions().isEmpty()) {
                sb.append("\n优化建议：\n");
                for (String suggestion : explainResult.getSuggestions()) {
                    sb.append("  - ").append(suggestion).append("\n");
                }
            }
            sb.append("\n任务：重新生成一个SQL，要求：\n");
            sb.append("1. 避免上述风险（如全表扫描、缺少索引等）\n");
            sb.append("2. 保持查询语义不变\n");
            sb.append("3. 如果无法优化，请说明原因并返回原 SQL\n");
            sb.append("4. 只输出 SQL 语句，不要包含其他内容\n\n");
            sb.append("新 SQL：");

            String response = llmService.generateSQL(sb.toString());
            return extractSQLFromResponse(response);
        } catch (Exception e) {
            log.warn("[SQLRiskAnalysisTool] LLM重新生成SQL失败: {}", e.getMessage());
            return null;
        }
    }

    private String extractSQLFromResponse(String response) {
        if (response == null || response.isEmpty()) {
            return null;
        }

        String cleaned = response.trim();
        cleaned = cleaned.replaceAll("```sql\\s*", "");
        cleaned = cleaned.replaceAll("```\\s*$", "");
        cleaned = cleaned.trim();

        if (cleaned.contains("\n")) {
            String[] lines = cleaned.split("\n");
            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.toUpperCase().startsWith("SELECT")
                    || trimmed.toUpperCase().startsWith("WITH")
                    || trimmed.toUpperCase().startsWith("INSERT")
                    || trimmed.toUpperCase().startsWith("UPDATE")
                    || trimmed.toUpperCase().startsWith("DELETE")) {
                    return trimmed;
                }
            }
        }

        return cleaned;
    }

    private Map<String, Object> parseJsonResponse(String response) {
        try {
            int jsonStart = response.indexOf("{");
            int jsonEnd = response.lastIndexOf("}");
            if (jsonStart >= 0 && jsonEnd > jsonStart) {
                String jsonStr = response.substring(jsonStart, jsonEnd + 1);
                return objectMapper.readValue(jsonStr, new TypeReference<Map<String, Object>>() {});
            }
        } catch (Exception e) {
            log.warn("[SQLRiskAnalysisTool] JSON解析失败: {}", e.getMessage());
        }
        return null;
    }

    private String buildSimpleResponse(String riskLevel, String reason, String optimizedSql) {
        try {
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", true);
            response.put("riskLevel", riskLevel);
            response.put("reason", reason);
            response.put("needsHumanApproval", false);
            if (optimizedSql != null) {
                response.put("optimizedSql", optimizedSql);
            }
            return objectMapper.writeValueAsString(response);
        } catch (Exception e) {
            return "{\"success\":true,\"riskLevel\":\"" + riskLevel + "\"}";
        }
    }

    private String buildExplainResponse(SQLRiskAnalyzer.RiskAnalysisResult result) {
        try {
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", true);
            response.put("riskLevel", result.getRiskLevel());
            response.put("needsHumanApproval", false);

            if (result.getRisks() != null && !result.getRisks().isEmpty()) {
                response.put("risks", result.getRisks());
            }
            // ✅ 只在有实际建议时才添加 optimizationSuggestion
            if (result.getSuggestions() != null && !result.getSuggestions().isEmpty()) {
                response.put("suggestions", result.getSuggestions());
                response.put("optimizationSuggestion", String.join("; ", result.getSuggestions()));
            }

            return objectMapper.writeValueAsString(response);
        } catch (Exception e) {
            return "{\"success\":true,\"riskLevel\":\"" + result.getRiskLevel() + "\"}";
        }
    }

    private String buildMediumRiskResponse(SQLRiskAnalyzer.RiskAnalysisResult explainResult,
                                            Map<String, Object> llmSuggestion) {
        try {
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", true);
            response.put("riskLevel", "MEDIUM");
            response.put("needsHumanApproval", false);

            if (explainResult.getRisks() != null && !explainResult.getRisks().isEmpty()) {
                response.put("risks", explainResult.getRisks());
            }

            StringBuilder optimizationSuggestion = new StringBuilder();
            if (explainResult.getSuggestions() != null && !explainResult.getSuggestions().isEmpty()) {
                optimizationSuggestion.append(String.join("; ", explainResult.getSuggestions()));
            }

            if (llmSuggestion != null) {
                response.put("llmSuggestion", llmSuggestion);
                String bottleneck = (String) llmSuggestion.get("bottleneck");
                String suggestion = (String) llmSuggestion.get("suggestion");
                String expectedImprovement = (String) llmSuggestion.get("expected_improvement");

                if (optimizationSuggestion.length() > 0) {
                    optimizationSuggestion.append("\n\n");
                }
                optimizationSuggestion.append("LLM优化建议：\n");
                if (bottleneck != null && !bottleneck.isEmpty()) {
                    optimizationSuggestion.append("性能瓶颈：").append(bottleneck).append("\n");
                }
                if (suggestion != null && !suggestion.isEmpty()) {
                    optimizationSuggestion.append("优化建议：").append(suggestion).append("\n");
                }
                if (expectedImprovement != null && !expectedImprovement.isEmpty()) {
                    optimizationSuggestion.append("预期效果：").append(expectedImprovement);
                }
            }

            if (optimizationSuggestion.length() > 0) {
                response.put("optimizationSuggestion", optimizationSuggestion.toString());
            }

            return objectMapper.writeValueAsString(response);
        } catch (Exception e) {
            return "{\"success\":true,\"riskLevel\":\"MEDIUM\"}";
        }
    }

    private String buildOptimizedResponse(SQLRiskAnalyzer.RiskAnalysisResult originalResult,
                                           String optimizedSql, String newRiskLevel,
                                           SQLRiskAnalyzer.RiskAnalysisResult reExplainResult) {
        try {
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", true);
            response.put("riskLevel", newRiskLevel);
            response.put("needsHumanApproval", false);
            response.put("optimizedSql", optimizedSql);
            response.put("originalRiskLevel", originalResult.getRiskLevel());
            response.put("optimizationApplied", true);

            if (reExplainResult.getRisks() != null && !reExplainResult.getRisks().isEmpty()) {
                response.put("risks", reExplainResult.getRisks());
            }
            if (reExplainResult.getSuggestions() != null && !reExplainResult.getSuggestions().isEmpty()) {
                response.put("suggestions", reExplainResult.getSuggestions());
                response.put("optimizationSuggestion", String.join("; ", reExplainResult.getSuggestions()));
            }

            return objectMapper.writeValueAsString(response);
        } catch (Exception e) {
            return "{\"success\":true,\"riskLevel\":\"" + newRiskLevel + "\",\"optimizedSql\":\"" + escapeJson(optimizedSql) + "\"}";
        }
    }

    private String buildHighRiskResponse(SQLRiskAnalyzer.RiskAnalysisResult explainResult,
                                          String optimizedSql) {
        try {
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", true);
            response.put("riskLevel", "HIGH");
            response.put("needsHumanApproval", true);

            if (explainResult.getRisks() != null && !explainResult.getRisks().isEmpty()) {
                response.put("risks", explainResult.getRisks());
            }

            StringBuilder reason = new StringBuilder();
            if (explainResult.getRisks() != null && !explainResult.getRisks().isEmpty()) {
                reason.append(String.join("; ", explainResult.getRisks()));
            }

            if (optimizedSql != null && !optimizedSql.trim().isEmpty()) {
                response.put("optimizedSql", optimizedSql);
                reason.append("\nLLM已尝试优化，生成新SQL，但仍需人工审核。");
            }

            response.put("reason", reason.toString());

            if (explainResult.getSuggestions() != null && !explainResult.getSuggestions().isEmpty()) {
                response.put("optimizationSuggestion", String.join("; ", explainResult.getSuggestions()));
            }

            return objectMapper.writeValueAsString(response);
        } catch (Exception e) {
            return "{\"success\":true,\"riskLevel\":\"HIGH\",\"needsHumanApproval\":true}";
        }
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }
}