package com.nl2sql.core.agent.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

@Slf4j
@Component
public class QuickRiskCheckTool {

    @Tool("快速判断SQL是否为简单查询。简单查询（单表、主键查询、无聚合、无JOIN）直接放行，无需EXPLAIN分析。返回isSimple和reason")
    public String quickRiskCheck(
        @P("待检查的SQL语句") String sql
    ) {
        try {
            log.info("[QuickRiskCheckTool] 快速风险判断: sql={}", sql != null ? sql.substring(0, Math.min(100, sql.length())) : "null");

            if (sql == null || sql.trim().isEmpty()) {
                return ToolResponseBuilder.error("INVALID_INPUT", "SQL不能为空")
                    .addMetadata("toolName", "quick_risk_check")
                    .build();
            }

            String upperSql = sql.toUpperCase().trim();

            if (!upperSql.startsWith("SELECT")) {
                Map<String, Object> data = new HashMap<>();
                data.put("isSimple", false);
                data.put("reason", "非SELECT语句，需要完整风险评估");
                data.put("riskLevel", "MEDIUM");
                return ToolResponseBuilder.success("data")
                    .withData(data)
                    .addMetadata("toolName", "quick_risk_check")
                    .build();
            }

            if (upperSql.contains(" JOIN ")) {
                Map<String, Object> data = new HashMap<>();
                data.put("isSimple", false);
                data.put("reason", "包含JOIN，需要EXPLAIN分析");
                data.put("riskLevel", null);
                return ToolResponseBuilder.success("data")
                    .withData(data)
                    .addMetadata("toolName", "quick_risk_check")
                    .build();
            }

            int selectCount = 0;
            for (int i = 0; i < upperSql.length(); i++) {
                if (upperSql.substring(i).startsWith("SELECT")) {
                    selectCount++;
                }
            }
            if (selectCount > 1) {
                Map<String, Object> data = new HashMap<>();
                data.put("isSimple", false);
                data.put("reason", "包含子查询，需要EXPLAIN分析");
                data.put("riskLevel", null);
                return ToolResponseBuilder.success("data")
                    .withData(data)
                    .addMetadata("toolName", "quick_risk_check")
                    .build();
            }

            if (upperSql.contains("COUNT(") || upperSql.contains("SUM(")
                || upperSql.contains("AVG(") || upperSql.contains("MAX(")
                || upperSql.contains("MIN(") || upperSql.contains("GROUP BY")) {
                Map<String, Object> data = new HashMap<>();
                data.put("isSimple", false);
                data.put("reason", "包含聚合函数或GROUP BY，需要EXPLAIN分析");
                data.put("riskLevel", null);
                return ToolResponseBuilder.success("data")
                    .withData(data)
                    .addMetadata("toolName", "quick_risk_check")
                    .build();
            }

            if (upperSql.contains("ORDER BY") || upperSql.contains("DISTINCT")) {
                Map<String, Object> data = new HashMap<>();
                data.put("isSimple", false);
                data.put("reason", "包含ORDER BY或DISTINCT，需要EXPLAIN分析");
                data.put("riskLevel", null);
                return ToolResponseBuilder.success("data")
                    .withData(data)
                    .addMetadata("toolName", "quick_risk_check")
                    .build();
            }

            Pattern primaryKeyPattern = Pattern.compile("WHERE\\s+\\w*_?id\\s*=\\s*\\d+", Pattern.CASE_INSENSITIVE);
            if (primaryKeyPattern.matcher(sql).find()) {
                Map<String, Object> data = new HashMap<>();
                data.put("isSimple", true);
                data.put("reason", "简单主键查询，无需EXPLAIN");
                data.put("riskLevel", "LOW");
                return ToolResponseBuilder.success("data")
                    .withData(data)
                    .addMetadata("toolName", "quick_risk_check")
                    .build();
            }

            Map<String, Object> data = new HashMap<>();
            data.put("isSimple", false);
            data.put("reason", "非主键查询，需要EXPLAIN分析");
            data.put("riskLevel", null);
            return ToolResponseBuilder.success("data")
                .withData(data)
                .addMetadata("toolName", "quick_risk_check")
                .build();

        } catch (Exception e) {
            log.error("[QuickRiskCheckTool] 判断失败", e);
            return ToolResponseBuilder.error("CHECK_ERROR", e.getMessage())
                .addMetadata("toolName", "quick_risk_check")
                .build();
        }
    }
}
