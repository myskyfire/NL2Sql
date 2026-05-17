package com.nl2sql.core.agent.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nl2sql.core.agent.planner.QueryPlan;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

@Slf4j
@Component
public class ChartWorker implements Worker {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String getWorkerType() {
        return "chart";
    }

    @Override
    public Worker.WorkerResult execute(Worker.WorkerContext context) {
        QueryPlan plan = context.getPlan();
        QueryPlan.ChartPlan chartPlan = plan.getChart();

        if (chartPlan == null || !chartPlan.isNeeded()) {
            return Worker.WorkerResult.skip("chart");
        }

        log.info("[ChartWorker] 开始生成图表，类型: {}", chartPlan.getType());

        try {
            List<Map<String, Object>> rows = extractDataRows(context);
            if (rows == null || rows.isEmpty()) {
                return Worker.WorkerResult.failure("chart", "未获取到SQL执行结果，无法生成图表");
            }

            String chartType = resolveChartType(chartPlan);
            Map<String, Object> echartsConfig = generateEChartsConfig(chartType, rows, chartPlan);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("chartType", getChartTypeName(chartType));
            result.put("title", chartPlan.getTitle() != null ? chartPlan.getTitle() : "数据图表");
            result.put("echartsConfig", echartsConfig);

            return Worker.WorkerResult.success("chart", result);

        } catch (Exception e) {
            log.error("[ChartWorker] 图表生成失败", e);
            return Worker.WorkerResult.failure("chart", "图表生成失败: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractDataRows(Worker.WorkerContext context) {
        Worker.WorkerResult sqlResult = context.getPreviousResults().get("sql");
        if (sqlResult == null || sqlResult.getData() == null) {
            return null;
        }

        Map<String, Object> sqlData = sqlResult.getData();

        Object dataObj = sqlData.get("data");
        if (dataObj instanceof List) {
            return (List<Map<String, Object>>) dataObj;
        }

        if (sqlData.get("rows") instanceof List) {
            return (List<Map<String, Object>>) sqlData.get("rows");
        }

        Object stepsObj = sqlData.get("steps");
        if (stepsObj instanceof List) {
            List<Map<String, Object>> steps = (List<Map<String, Object>>) stepsObj;
            if (!steps.isEmpty()) {
                Map<String, Object> lastStep = steps.get(steps.size() - 1);
                Object stepData = lastStep.get("data");
                if (stepData instanceof List) {
                    return (List<Map<String, Object>>) stepData;
                }
            }
        }

        return null;
    }

    private String resolveChartType(QueryPlan.ChartPlan chartPlan) {
        if (chartPlan.getType() != null) {
            switch (chartPlan.getType()) {
                case BAR: return "bar";
                case LINE: return "line";
                case PIE: return "pie";
                case AREA: return "area";
                case SCATTER: return "scatter";
                default: return "bar";
            }
        }
        return "bar";
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> generateEChartsConfig(String chartType, List<Map<String, Object>> rows,
                                                       QueryPlan.ChartPlan chartPlan) {
        Map<String, Object> config = new LinkedHashMap<>();

        if (rows.isEmpty()) {
            config.put("warning", "无数据可展示");
            return config;
        }

        Map<String, Object> firstRow = rows.get(0);
        List<String> allKeys = new ArrayList<>(firstRow.keySet());

        String categoryField = identifyCategoryField(allKeys, firstRow);
        List<String> metricFields = identifyMetricFields(allKeys, firstRow);

        if (categoryField == null && !allKeys.isEmpty()) {
            categoryField = allKeys.get(0);
        }
        if (metricFields.isEmpty() && allKeys.size() > 1) {
            metricFields = allKeys.subList(1, allKeys.size());
        }

        if ("pie".equals(chartType)) {
            return generatePieConfig(rows, categoryField, metricFields, chartPlan);
        }

        List<String> categories = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            categories.add(String.valueOf(row.getOrDefault(categoryField, "")));
        }

        List<Map<String, Object>> seriesList = new ArrayList<>();
        for (String metric : metricFields) {
            Map<String, Object> series = new LinkedHashMap<>();
            series.put("name", metric);

            String seriesType = "area".equals(chartType) ? "line" : chartType;
            series.put("type", seriesType);

            if ("area".equals(chartType)) {
                series.put("areaStyle", Map.of());
            }

            List<Object> seriesData = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                Object val = row.get(metric);
                seriesData.add(val instanceof Number ? val : parseNumber(val));
            }
            series.put("data", seriesData);
            seriesList.add(series);
        }

        if (seriesList.isEmpty() && !allKeys.isEmpty()) {
            Map<String, Object> fallbackSeries = new LinkedHashMap<>();
            fallbackSeries.put("name", allKeys.get(allKeys.size() - 1));
            fallbackSeries.put("type", "area".equals(chartType) ? "line" : chartType);
            if ("area".equals(chartType)) {
                fallbackSeries.put("areaStyle", Map.of());
            }
            List<Object> fallbackData = new ArrayList<>();
            String yKey = allKeys.get(allKeys.size() - 1);
            for (Map<String, Object> row : rows) {
                Object val = row.get(yKey);
                fallbackData.add(val instanceof Number ? val : parseNumber(val));
            }
            fallbackSeries.put("data", fallbackData);
            seriesList.add(fallbackSeries);
        }

        config.put("tooltip", Map.of("trigger", "axis"));
        config.put("legend", Map.of("data", metricFields));
        config.put("xAxis", Map.of("type", "category", "data", categories));
        config.put("yAxis", Map.of("type", "value"));
        config.put("series", seriesList);

        return config;
    }

    private Map<String, Object> generatePieConfig(List<Map<String, Object>> rows,
                                                    String categoryField, List<String> metricFields,
                                                    QueryPlan.ChartPlan chartPlan) {
        Map<String, Object> config = new LinkedHashMap<>();

        String valueField = metricFields.isEmpty() ? null : metricFields.get(0);
        if (valueField == null && !rows.isEmpty()) {
            List<String> keys = new ArrayList<>(rows.get(0).keySet());
            if (keys.size() > 1) {
                valueField = keys.get(keys.size() - 1);
            }
        }

        List<Map<String, Object>> pieData = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", String.valueOf(row.getOrDefault(categoryField, "")));
            Object val = valueField != null ? row.get(valueField) : 0;
            item.put("value", val instanceof Number ? val : parseNumber(val));
            pieData.add(item);
        }

        Map<String, Object> series = new LinkedHashMap<>();
        series.put("type", "pie");
        series.put("radius", List.of("30%", "70%"));
        series.put("data", pieData);

        config.put("tooltip", Map.of("trigger", "item"));
        config.put("legend", Map.of("orient", "vertical", "left", "left"));
        config.put("series", List.of(series));

        return config;
    }

    private String identifyCategoryField(List<String> keys, Map<String, Object> firstRow) {
        for (String key : keys) {
            Object val = firstRow.get(key);
            if (!(val instanceof Number) && !isNumericString(val)) {
                return key;
            }
        }
        return keys.isEmpty() ? null : keys.get(0);
    }

    private List<String> identifyMetricFields(List<String> keys, Map<String, Object> firstRow) {
        List<String> metrics = new ArrayList<>();
        for (String key : keys) {
            Object val = firstRow.get(key);
            if (val instanceof Number || isNumericString(val)) {
                metrics.add(key);
            }
        }
        return metrics;
    }

    private boolean isNumericString(Object val) {
        if (val == null) return false;
        if (val instanceof Number) return true;
        try {
            Double.parseDouble(String.valueOf(val));
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private Object parseNumber(Object val) {
        if (val == null) return 0;
        try {
            return Double.parseDouble(String.valueOf(val));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private String getChartTypeName(String chartType) {
        if (chartType == null) return "柱状图";
        switch (chartType) {
            case "bar": return "柱状图";
            case "line": return "折线图";
            case "pie": return "饼图";
            case "area": return "面积图";
            case "scatter": return "散点图";
            default: return "图表";
        }
    }
}
