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
public class GenerateChartConfigTool {

    @Tool("根据查询结果数据和图表类型生成ECharts配置。输入图表类型（bar/line/pie/area）和查询结果数据，返回完整的ECharts配置JSON")
    public String generateChartConfig(
        @P("图表类型：bar(柱状图)/line(折线图)/pie(饼图)/area(面积图)") String chartType,
        @P("查询结果数据，JSON数组格式") String dataJson
    ) {
        try {
            log.info("[GenerateChartConfigTool] 生成图表配置: chartType={}", chartType);

            if (chartType == null || chartType.trim().isEmpty()) {
                return ToolResponseBuilder.error("INVALID_INPUT", "图表类型不能为空")
                    .addMetadata("toolName", "generate_chart_config")
                    .build();
            }

            if (dataJson == null || dataJson.trim().isEmpty()) {
                return ToolResponseBuilder.error("INVALID_INPUT", "数据不能为空")
                    .addMetadata("toolName", "generate_chart_config")
                    .build();
            }

            List<Map<String, Object>> data = parseDataJson(dataJson);
            if (data == null || data.isEmpty()) {
                return ToolResponseBuilder.error("INVALID_DATA", "数据为空或格式异常")
                    .addMetadata("toolName", "generate_chart_config")
                    .build();
            }

            Map<String, Object> echartsConfig = generateEChartsConfig(chartType, data);

            Map<String, Object> result = new HashMap<>();
            result.put("chartType", chartType);
            result.put("chartTypeName", getChartTypeName(chartType));
            result.put("echartsConfig", echartsConfig);

            log.info("[GenerateChartConfigTool] 图表配置生成完成: type={}", chartType);

            return ToolResponseBuilder.success("data")
                .withData(result)
                .addMetadata("toolName", "generate_chart_config")
                .build();

        } catch (Exception e) {
            log.error("[GenerateChartConfigTool] 生成失败", e);
            return ToolResponseBuilder.error("GENERATION_ERROR", e.getMessage())
                .addMetadata("toolName", "generate_chart_config")
                .build();
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseDataJson(String dataJson) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            return mapper.readValue(dataJson, mapper.getTypeFactory().constructCollectionType(List.class, Map.class));
        } catch (Exception e) {
            log.warn("[GenerateChartConfigTool] 数据解析失败: {}", e.getMessage());
            return null;
        }
    }

    private Map<String, Object> generateEChartsConfig(String chartType, List<Map<String, Object>> data) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("type", chartType);

        if (data == null || data.isEmpty()) {
            return config;
        }

        if ("pie".equals(chartType)) {
            return generatePieConfig(data);
        }

        List<String> categories = new ArrayList<>();
        Map<String, List<Object>> seriesMap = new LinkedHashMap<>();

        List<String> keys = new ArrayList<>(data.get(0).keySet());
        String categoryKey = keys.get(0);

        for (Map<String, Object> row : data) {
            categories.add(String.valueOf(row.get(categoryKey)));
        }

        for (int i = 1; i < keys.size(); i++) {
            String valueKey = keys.get(i);
            List<Object> values = new ArrayList<>();

            for (Map<String, Object> row : data) {
                Object val = row.get(valueKey);
                if (val instanceof Number) {
                    values.add(val);
                } else if (val != null) {
                    try {
                        values.add(Double.parseDouble(String.valueOf(val)));
                    } catch (Exception e) {
                        values.add(0);
                    }
                } else {
                    values.add(0);
                }
            }

            seriesMap.put(valueKey, values);
        }

        config.put("categories", categories);
        config.put("series", seriesMap);
        config.put("title", getChartTypeName(chartType));

        return config;
    }

    private Map<String, Object> generatePieConfig(List<Map<String, Object>> data) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("type", "pie");

        List<String> keys = new ArrayList<>(data.get(0).keySet());
        String categoryKey = keys.get(0);

        List<Map<String, Object>> pieData = new ArrayList<>();
        for (Map<String, Object> row : data) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", String.valueOf(row.get(categoryKey)));

            Object value = null;
            for (int i = 1; i < keys.size(); i++) {
                Object val = row.get(keys.get(i));
                if (val instanceof Number) {
                    value = val;
                    break;
                }
            }
            if (value == null) value = 0;
            item.put("value", value);
            pieData.add(item);
        }

        config.put("data", pieData);
        config.put("title", "饼图");

        return config;
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
