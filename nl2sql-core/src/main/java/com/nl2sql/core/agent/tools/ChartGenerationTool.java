package com.nl2sql.core.agent.tools;

import dev.langchain4j.agent.tool.Tool;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 图表生成工具 - 生成 ECharts 配置
 */
@Slf4j
@Component
public class ChartGenerationTool {
    
    /**
     * 根据数据生成图表配置（ECharts JSON）
     */
    @Tool("根据查询结果数据生成合适的图表配置。输入用户问题、数据类型和数据JSON字符串，返回 ECharts 配置JSON")
    public String generateChart(String userQuery, String chartType, String dataJson) {
        try {
            List<Map<String, Object>> data = parseDataJson(dataJson);
            log.info("[ChartGenerator] 生成图表: type={}, 数据行数={}", chartType, data.size());
            
            if (data == null || data.isEmpty()) {
                return "❌ 没有数据可生成图表";
            }
            
            Map<String, Object> chartConfig;
            
            switch (chartType.toLowerCase()) {
                case "bar":
                case "柱状图":
                    chartConfig = generateBarChart(userQuery, data);
                    break;
                case "line":
                case "折线图":
                    chartConfig = generateLineChart(userQuery, data);
                    break;
                case "pie":
                case "饼图":
                    chartConfig = generatePieChart(userQuery, data);
                    break;
                case "area":
                case "面积图":
                    chartConfig = generateAreaChart(userQuery, data);
                    break;
                default:
                    // 自动推荐
                    chartConfig = autoSelectChart(userQuery, data);
            }
            
            // 转换为 JSON
            return convertToJSON(chartConfig);
            
        } catch (Exception e) {
            log.error("[ChartGenerator] 生成失败", e);
            return "❌ 图表生成失败: " + e.getMessage();
        }
    }
    
    /**
     * 生成柱状图
     */
    private Map<String, Object> generateBarChart(String query, List<Map<String, Object>> data) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("title", extractChartTitle(query));
        config.put("type", "bar");
        
        List<String> categories = new ArrayList<>();
        List<Object> values = new ArrayList<>();
        
        for (Map<String, Object> row : data) {
            String key = row.keySet().iterator().next();
            categories.add(String.valueOf(row.get(key)));
            
            if (row.size() > 1) {
                String valueKey = row.keySet().stream().skip(1).findFirst().orElse(key);
                values.add(row.get(valueKey));
            }
        }
        
        config.put("categories", categories);
        config.put("values", values);
        
        return config;
    }
    
    /**
     * 生成折线图
     */
    private Map<String, Object> generateLineChart(String query, List<Map<String, Object>> data) {
        Map<String, Object> config = generateBarChart(query, data);
        config.put("type", "line");
        config.put("smooth", true);
        return config;
    }
    
    /**
     * 生成饼图
     */
    private Map<String, Object> generatePieChart(String query, List<Map<String, Object>> data) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("title", extractChartTitle(query));
        config.put("type", "pie");
        
        List<Map<String, Object>> series = new ArrayList<>();
        for (Map<String, Object> row : data) {
            Map<String, Object> item = new LinkedHashMap<>();
            String key = row.keySet().iterator().next();
            item.put("name", String.valueOf(row.get(key)));
            
            if (row.size() > 1) {
                String valueKey = row.keySet().stream().skip(1).findFirst().orElse(key);
                item.put("value", row.get(valueKey));
            }
            series.add(item);
        }
        
        config.put("data", series);
        return config;
    }
    
    /**
     * 生成面积图
     */
    private Map<String, Object> generateAreaChart(String query, List<Map<String, Object>> data) {
        Map<String, Object> config = generateLineChart(query, data);
        config.put("type", "area");
        return config;
    }
    
    /**
     * 自动选择图表类型
     */
    private Map<String, Object> autoSelectChart(String query, List<Map<String, Object>> data) {
        String lowerQuery = query.toLowerCase();
        
        if (lowerQuery.contains("趋势") || lowerQuery.contains("变化")) {
            return generateLineChart(query, data);
        } else if (lowerQuery.contains("占比") || lowerQuery.contains("比例")) {
            return generatePieChart(query, data);
        } else {
            return generateBarChart(query, data);
        }
    }
    
    /**
     * 提取图表标题
     */
    private String extractChartTitle(String query) {
        if (query.length() > 30) {
            return query.substring(0, 30) + "...";
        }
        return query;
    }
    
    /**
     * 转换为 JSON 字符串（简化版）
     */
    private String convertToJSON(Map<String, Object> config) {
        StringBuilder json = new StringBuilder("{\n");
        boolean first = true;
        
        for (Map.Entry<String, Object> entry : config.entrySet()) {
            if (!first) json.append(",\n");
            first = false;
            
            json.append("  \"").append(entry.getKey()).append("\": ");
            
            if (entry.getValue() instanceof String) {
                json.append("\"").append(entry.getValue()).append("\"");
            } else if (entry.getValue() instanceof List) {
                json.append(convertListToJSON((List<?>) entry.getValue()));
            } else if (entry.getValue() instanceof Map) {
                json.append(convertToJSON((Map<String, Object>) entry.getValue()));
            } else {
                json.append(entry.getValue());
            }
        }
        
        json.append("\n}");
        return json.toString();
    }
    
    /**
     * 转换 List 为 JSON
     */
    private String convertListToJSON(List<?> list) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) json.append(", ");
            
            Object item = list.get(i);
            if (item instanceof String) {
                json.append("\"").append(item).append("\"");
            } else if (item instanceof Map) {
                json.append(convertToJSON((Map<String, Object>) item));
            } else {
                json.append(item);
            }
        }
        json.append("]");
        return json.toString();
    }
    
    /**
     * 解析数据 JSON
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseDataJson(String dataJson) {
        if (dataJson == null || dataJson.trim().isEmpty()) {
            return new ArrayList<>();
        }
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            return mapper.readValue(dataJson, List.class);
        } catch (Exception e) {
            log.error("[ChartGenerator] 解析数据JSON失败", e);
            return new ArrayList<>();
        }
    }
}
