package com.nl2sql.core.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Component
public class ChartDetectionTool {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Deprecated
    @Tool("检测用户问题中是否包含图表生成意图（柱状图/折线图/饼图/面积图），如果检测到则生成ECharts配置。@Deprecated - 建议使用 detectChartIntent + generateChartConfig 组合")
    public String detectAndGenerateChart(
        @P("用户原始问题") String question,
        @P("查询结果数据") Object data
    ) {
        try {
            log.info("[ChartDetectionTool] @Deprecated - 建议使用 detectChartIntent + generateChartConfig 组合");
            log.info("[ChartDetectionTool] 检测图表意图: question={}", question);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", true);

            if (question == null || question.isEmpty()) {
                response.put("chartDetected", false);
                response.put("cleanedQuestion", question);
                return objectMapper.writeValueAsString(response);
            }

            String cleanedQuestion = removeChartDescription(question);
            String chartType = extractChartType(question);
            if (chartType == null) {
                response.put("chartDetected", false);
                response.put("cleanedQuestion", cleanedQuestion);
                return objectMapper.writeValueAsString(response);
            }
            response.put("chartDetected", true);
            response.put("chartType", chartType);
            response.put("chartTypeName", getChartTypeName(chartType));
            response.put("cleanedQuestion", cleanedQuestion);

            log.info("[ChartDetectionTool] 检测到图表意图: type={}", chartType);

            return objectMapper.writeValueAsString(response);

        } catch (Exception e) {
            log.error("[ChartDetectionTool] 检测失败", e);
            return "{\"success\":false,\"error\":\"图表检测异常: " + e.getMessage() + "\"}";
        }
    }

    private String extractChartType(String question) {
        if (question == null || question.isEmpty()) {
            return null;
        }

        String lower = question.toLowerCase();

        if (lower.contains("柱状图") || lower.contains("bar chart") || lower.contains("bar")) {
            return "bar";
        }
        if (lower.contains("折线图") || lower.contains("line chart") || lower.contains("line")) {
            return "line";
        }
        if (lower.contains("饼图") || lower.contains("pie chart") || lower.contains("pie")) {
            return "pie";
        }
        if (lower.contains("面积图") || lower.contains("area chart") || lower.contains("area")) {
            return "area";
        }

        return null;
    }

    private String removeChartDescription(String question) {
        if (question == null || question.isEmpty()) {
            return question;
        }

        return question
            .replaceAll("并生成[柱状折线饼面积]图", "")
            .replaceAll("并画出[柱状折线饼面积]图", "")
            .replaceAll("并展示[柱状折线饼面积]图", "")
            .replaceAll("生成[柱状折线饼面积]图", "")
            .replaceAll("画出[柱状折线饼面积]图", "")
            .replaceAll("展示[柱状折线饼面积]图", "")
            .trim();
    }

    private String getChartTypeName(String chartType) {
        switch (chartType) {
            case "bar": return "柱状图";
            case "line": return "折线图";
            case "pie": return "饼图";
            case "area": return "面积图";
            default: return chartType;
        }
    }
}
