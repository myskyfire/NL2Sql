package com.nl2sql.core.agent.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class GenerateFollowUpSuggestionsTool {

    @Tool("根据查询结果和SQL特征，智能生成追问建议（如AI总结、生成图表、下载Excel等）。输入SQL和结果行数，返回追问建议列表")
    public String generateFollowUpSuggestions(
        @P("执行的SQL语句") String sql,
        @P("查询结果行数") Integer rowCount,
        @P("查询结果数据，JSON数组格式（可选，用于检测数值字段）") String dataJson
    ) {
        try {
            log.info("[GenerateFollowUpSuggestionsTool] 生成追问建议: rowCount={}", rowCount);

            List<Map<String, String>> suggestions = new ArrayList<>();

            boolean isStatistical = isStatisticalData(sql);
            boolean hasNumeric = false;

            if (dataJson != null && !dataJson.trim().isEmpty()) {
                hasNumeric = checkHasNumericField(dataJson);
            }

            if (isStatistical) {
                Map<String, String> summarySuggestion = new LinkedHashMap<>();
                summarySuggestion.put("text", "🤖 AI 总结");
                summarySuggestion.put("action", "generate_summary");
                suggestions.add(summarySuggestion);
            }

            if (rowCount != null && rowCount >= 2 && hasNumeric && isStatistical) {
                Map<String, String> chartSuggestion = new LinkedHashMap<>();
                chartSuggestion.put("text", "📊 生成图表");
                chartSuggestion.put("action", "generate_chart");
                suggestions.add(chartSuggestion);
            }

            if (rowCount != null && rowCount > 0) {
                Map<String, String> exportSuggestion = new LinkedHashMap<>();
                exportSuggestion.put("text", "💾 下载 Excel");
                exportSuggestion.put("action", "export_excel");
                suggestions.add(exportSuggestion);
            }

            Map<String, Object> data = new HashMap<>();
            data.put("followUpSuggestions", suggestions);
            data.put("isStatisticalData", isStatistical);
            data.put("hasNumericField", hasNumeric);

            log.info("[GenerateFollowUpSuggestionsTool] 生成 {} 条追问建议", suggestions.size());

            return ToolResponseBuilder.success("data")
                .withData(data)
                .addMetadata("toolName", "generate_follow_up_suggestions")
                .build();

        } catch (Exception e) {
            log.error("[GenerateFollowUpSuggestionsTool] 生成失败", e);
            return ToolResponseBuilder.error("SUGGESTION_ERROR", e.getMessage())
                .addMetadata("toolName", "generate_follow_up_suggestions")
                .build();
        }
    }

    private boolean isStatisticalData(String sql) {
        if (sql == null || sql.isEmpty()) {
            return false;
        }
        String upperSql = sql.toUpperCase();
        return upperSql.contains("GROUP BY") ||
               upperSql.contains("COUNT(") ||
               upperSql.contains("SUM(") ||
               upperSql.contains("AVG(") ||
               upperSql.contains("MAX(") ||
               upperSql.contains("MIN(");
    }

    @SuppressWarnings("unchecked")
    private boolean checkHasNumericField(String dataJson) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            List<Map<String, Object>> data = mapper.readValue(dataJson,
                mapper.getTypeFactory().constructCollectionType(List.class, Map.class));
            if (data == null || data.isEmpty()) {
                return false;
            }
            Map<String, Object> firstRow = data.get(0);
            for (Object value : firstRow.values()) {
                if (value instanceof Number) {
                    return true;
                }
                if (value instanceof String) {
                    try {
                        Double.parseDouble((String) value);
                        return true;
                    } catch (NumberFormatException e) {
                        // continue
                    }
                }
            }
            return false;
        } catch (Exception e) {
            log.warn("[GenerateFollowUpSuggestionsTool] 数据解析失败: {}", e.getMessage());
            return false;
        }
    }
}
