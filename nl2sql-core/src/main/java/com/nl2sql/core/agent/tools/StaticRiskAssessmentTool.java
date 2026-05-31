package com.nl2sql.core.agent.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class StaticRiskAssessmentTool {

    @Tool("对SQL进行静态风险评估（无需连库，适用于离线模式/sqlOnly模式）。基于规则判断风险等级，返回riskLevel/risks/suggestions")
    public String staticRiskAssessment(
        @P("待评估的SQL语句") String sql
    ) {
        try {
            log.info("[StaticRiskAssessmentTool] 静态风险评估: sql={}", sql != null ? sql.substring(0, Math.min(80, sql.length())) : "null");

            if (sql == null || sql.trim().isEmpty()) {
                Map<String, Object> data = new HashMap<>();
                data.put("riskLevel", "LOW");
                data.put("risks", new ArrayList<>());
                data.put("suggestions", new ArrayList<>());
                data.put("reason", "空SQL");
                return ToolResponseBuilder.success("data")
                    .withData(data)
                    .addMetadata("toolName", "static_risk_assessment")
                    .build();
            }

            String upperSql = sql.toUpperCase().trim();
            List<String> risks = new ArrayList<>();
            List<String> suggestions = new ArrayList<>();
            String riskLevel = "LOW";

            if (!upperSql.startsWith("SELECT")) {
                Map<String, Object> data = new HashMap<>();
                data.put("riskLevel", "HIGH");
                risks.add("非SELECT语句，存在写操作风险");
                data.put("risks", risks);
                data.put("suggestions", suggestions);
                data.put("reason", String.join("; ", risks));
                return ToolResponseBuilder.success("data")
                    .withData(data)
                    .addMetadata("toolName", "static_risk_assessment")
                    .build();
            }

            if (upperSql.contains(" JOIN ")) {
                int joinCount = upperSql.split(" JOIN ").length - 1;
                if (joinCount >= 3) {
                    risks.add("多表JOIN（" + joinCount + "个），性能风险高");
                    suggestions.add("减少JOIN数量或拆分为多个查询");
                    riskLevel = "HIGH";
                } else if (joinCount >= 2) {
                    risks.add("多表JOIN（" + joinCount + "个），需关注性能");
                    suggestions.add("确保JOIN字段有索引");
                    if (!"HIGH".equals(riskLevel)) riskLevel = "MEDIUM";
                }
            }

            if (!upperSql.contains("WHERE")) {
                if (!upperSql.contains("LIMIT")) {
                    risks.add("无WHERE条件且无LIMIT，可能导致全表扫描");
                    suggestions.add("添加WHERE条件或LIMIT限制");
                    if (!"HIGH".equals(riskLevel)) riskLevel = "MEDIUM";
                } else {
                    risks.add("无WHERE条件，扫描范围可能较大");
                    suggestions.add("建议添加WHERE条件缩小范围");
                }
            }

            if (upperSql.contains("SUBQUERY") || countKeyword(upperSql, "SELECT") > 1) {
                risks.add("包含子查询，可能影响性能");
                suggestions.add("考虑将子查询改写为JOIN");
                if (!"HIGH".equals(riskLevel)) riskLevel = "MEDIUM";
            }

            if (upperSql.contains("LIKE") && upperSql.contains("%")) {
                risks.add("使用LIKE模糊查询，可能导致索引失效");
                suggestions.add("如非必要，避免前缀模糊查询");
                if (!"HIGH".equals(riskLevel) && !"MEDIUM".equals(riskLevel)) riskLevel = "LOW";
            }

            if (upperSql.contains("ORDER BY") && !upperSql.contains("LIMIT")) {
                risks.add("ORDER BY无LIMIT，可能排序大量数据");
                suggestions.add("添加LIMIT限制排序结果集");
                if (!"HIGH".equals(riskLevel) && !"MEDIUM".equals(riskLevel)) riskLevel = "MEDIUM";
            }

            if (upperSql.contains("GROUP BY") && !upperSql.contains("WHERE")) {
                risks.add("GROUP BY无WHERE条件，全表分组");
                suggestions.add("添加WHERE条件减少分组数据量");
                if (!"HIGH".equals(riskLevel)) riskLevel = "MEDIUM";
            }

            if (upperSql.contains("UNION")) {
                risks.add("包含UNION操作，需关注结果集大小");
                suggestions.add("确保每个UNION分支都有LIMIT");
                if (!"HIGH".equals(riskLevel) && !"MEDIUM".equals(riskLevel)) riskLevel = "MEDIUM";
            }

            Map<String, Object> data = new HashMap<>();
            data.put("riskLevel", riskLevel);
            data.put("risks", risks);
            data.put("suggestions", suggestions);
            data.put("reason", risks.isEmpty() ? "静态规则未发现明显风险" : String.join("; ", risks));

            log.info("[StaticRiskAssessmentTool] 评估完成: riskLevel={}, risks={}", riskLevel, risks.size());

            return ToolResponseBuilder.success("data")
                .withData(data)
                .addMetadata("toolName", "static_risk_assessment")
                .build();

        } catch (Exception e) {
            log.error("[StaticRiskAssessmentTool] 评估失败", e);
            return ToolResponseBuilder.error("ASSESSMENT_ERROR", e.getMessage())
                .addMetadata("toolName", "static_risk_assessment")
                .build();
        }
    }

    private int countKeyword(String sql, String keyword) {
        int count = 0;
        int idx = 0;
        while ((idx = sql.indexOf(keyword, idx)) != -1) {
            count++;
            idx += keyword.length();
        }
        return count;
    }
}
