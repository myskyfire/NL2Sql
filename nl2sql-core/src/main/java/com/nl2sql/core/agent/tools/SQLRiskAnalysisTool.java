package com.nl2sql.core.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nl2sql.core.executor.SQLRiskAnalyzer;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Component
public class SQLRiskAnalysisTool {

    @Autowired
    private SQLRiskAnalyzer riskAnalyzer;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Deprecated
    @Tool("在执行复杂SQL之前，先分析其执行计划和潜在风险。支持三层评估：快速判断→EXPLAIN分析→LLM辅助评估。高风险时自动尝试LLM重新生成SQL。返回风险等级(LOW/MEDIUM/HIGH)、风险点、优化建议和优化后的SQL。")
    public String analyzeSQLRisk(
        @P("待分析的SQL语句") String sql,
        @P("数据源ID") Long datasourceId,
        @P("用户的原始问题（用于LLM上下文理解）") String question
    ) {
        try {
            log.info("[SQLRiskAnalysisTool] @Deprecated - 建议使用 quickRiskCheck + analyzeQueryPlan + getLLMOptimizationSuggestion / regenerateSQLWithLLM 组合");
            log.info("[SQLRiskAnalysisTool] 开始分析SQL风险: datasourceId={}, hasQuestion={}",
                datasourceId, question != null && !question.isEmpty());

            if (datasourceId == null) {
                return buildSimpleResponse("LOW", "缺少数据源ID，无法执行风险分析", null);
            }

            if (sql == null || sql.trim().isEmpty()) {
                return buildSimpleResponse("LOW", "SQL不能为空", null);
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

            SQLRiskAnalyzer.RiskAnalysisResult explainResult = riskAnalyzer.analyzeRisk(sql, datasourceId);
            String riskLevel = explainResult.getRiskLevel();
            log.info("[SQLRiskAnalysisTool] EXPLAIN分析结果: riskLevel={}", riskLevel);

            if ("LOW".equals(riskLevel)) {
                return buildExplainResponse(explainResult);
            }

            if ("MEDIUM".equals(riskLevel)) {
                return buildMediumRiskResponse(explainResult);
            }

            return buildHighRiskResponse(explainResult);

        } catch (Exception e) {
            log.error("[SQLRiskAnalysisTool] 分析失败", e);
            return "{\"success\":false,\"error\":\"分析失败: " + e.getMessage() + "\"}";
        }
    }

    @Deprecated
    public String staticRiskAssessment(String sql) {
        log.info("[SQLRiskAnalysisTool] @Deprecated - 建议使用 StaticRiskAssessmentTool");
        try {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("deprecated", true);
            data.put("message", "请使用 StaticRiskAssessmentTool 替代");
            return objectMapper.writeValueAsString(data);
        } catch (Exception e) {
            return "{\"deprecated\":true,\"message\":\"请使用 StaticRiskAssessmentTool 替代\"}";
        }
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
            if (result.getSuggestions() != null && !result.getSuggestions().isEmpty()) {
                response.put("suggestions", result.getSuggestions());
                response.put("optimizationSuggestion", String.join("; ", result.getSuggestions()));
            }

            return objectMapper.writeValueAsString(response);
        } catch (Exception e) {
            return "{\"success\":true,\"riskLevel\":\"" + result.getRiskLevel() + "\"}";
        }
    }

    private String buildMediumRiskResponse(SQLRiskAnalyzer.RiskAnalysisResult explainResult) {
        try {
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", true);
            response.put("riskLevel", "MEDIUM");
            response.put("needsHumanApproval", false);

            if (explainResult.getRisks() != null && !explainResult.getRisks().isEmpty()) {
                response.put("risks", explainResult.getRisks());
            }

            if (explainResult.getSuggestions() != null && !explainResult.getSuggestions().isEmpty()) {
                response.put("optimizationSuggestion", String.join("; ", explainResult.getSuggestions()));
            }

            return objectMapper.writeValueAsString(response);
        } catch (Exception e) {
            return "{\"success\":true,\"riskLevel\":\"MEDIUM\"}";
        }
    }

    private String buildHighRiskResponse(SQLRiskAnalyzer.RiskAnalysisResult explainResult) {
        try {
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", true);
            response.put("riskLevel", "HIGH");
            response.put("needsHumanApproval", true);
            response.put("approvalId", "risk_" + System.currentTimeMillis());

            if (explainResult.getRisks() != null && !explainResult.getRisks().isEmpty()) {
                response.put("risks", explainResult.getRisks());
            }

            StringBuilder reason = new StringBuilder();
            if (explainResult.getRisks() != null && !explainResult.getRisks().isEmpty()) {
                reason.append(String.join("; ", explainResult.getRisks()));
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
}
