package com.nl2sql.core.agent.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
public class DetectChartIntentTool {

    @Tool("检测用户问题中是否包含图表生成意图（柱状图/折线图/饼图/面积图）。仅检测意图并返回图表类型和清理后的问题，不生成图表配置")
    public String detectChartIntent(
        @P("用户原始问题") String question
    ) {
        try {
            log.info("[DetectChartIntentTool] 检测图表意图: question={}", question);

            if (question == null || question.trim().isEmpty()) {
                Map<String, Object> data = new HashMap<>();
                data.put("chartDetected", false);
                data.put("chartType", null);
                data.put("cleanedQuestion", question);
                return ToolResponseBuilder.success("data")
                    .withData(data)
                    .addMetadata("toolName", "detect_chart_intent")
                    .build();
            }

            String chartType = extractChartType(question);

            if (chartType == null) {
                Map<String, Object> data = new HashMap<>();
                data.put("chartDetected", false);
                data.put("chartType", null);
                data.put("cleanedQuestion", question);
                return ToolResponseBuilder.success("data")
                    .withData(data)
                    .addMetadata("toolName", "detect_chart_intent")
                    .build();
            }

            String cleanedQuestion = removeChartDescription(question);

            Map<String, Object> data = new HashMap<>();
            data.put("chartDetected", true);
            data.put("chartType", chartType);
            data.put("chartTypeName", getChartTypeName(chartType));
            data.put("cleanedQuestion", cleanedQuestion);

            log.info("[DetectChartIntentTool] 检测到图表意图: type={}", chartType);

            return ToolResponseBuilder.success("data")
                .withData(data)
                .addMetadata("toolName", "detect_chart_intent")
                .build();

        } catch (Exception e) {
            log.error("[DetectChartIntentTool] 检测失败", e);
            return ToolResponseBuilder.error("DETECTION_ERROR", e.getMessage())
                .addMetadata("toolName", "detect_chart_intent")
                .build();
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
