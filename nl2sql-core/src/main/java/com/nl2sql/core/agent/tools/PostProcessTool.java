package com.nl2sql.core.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nl2sql.core.agent.tool.BaseTool;
import com.nl2sql.core.agent.tool.ToolContext;
import com.nl2sql.core.agent.tool.ToolResult;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

@Slf4j
@Component
public class PostProcessTool implements BaseTool {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String getName() {
        return "post_process_response";
    }

    @Override
    public String getDescription() {
        return "组装查询结果响应。@Deprecated - 追问建议生成已移至 generateFollowUpSuggestions，图表注入已移至 generateChartConfig";
    }

    @Override
    public Map<String, Object> getParameterSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();
        properties.put("data", Map.of("type", "array", "description", "查询结果数据列表"));
        properties.put("rowCount", Map.of("type", "integer", "description", "结果行数"));
        properties.put("sql", Map.of("type", "string", "description", "执行的SQL语句"));
        properties.put("datasourceId", Map.of("type", "integer", "description", "数据源ID"));
        properties.put("executionTime", Map.of("type", "number", "description", "执行时间（毫秒）"));
        properties.put("optimizationSuggestion", Map.of("type", "string", "description", "优化建议（可选）"));
        properties.put("followUpSuggestions", Map.of("type", "array", "description", "追问建议列表（可选，由generateFollowUpSuggestions生成）"));
        properties.put("chartConfig", Map.of("type", "string", "description", "图表配置JSON（可选，由generateChartConfig生成）"));

        schema.put("properties", properties);
        schema.put("required", Arrays.asList("data", "rowCount", "sql", "datasourceId"));

        return schema;
    }

    @Deprecated
    @Override
    @SuppressWarnings("unchecked")
    public ToolResult execute(ToolContext context) {
        try {
            List<Map<String, Object>> data = context.getParameter("data");
            Integer rowCount = context.getParameter("rowCount");
            String sql = context.getParameter("sql");
            Long datasourceId = context.getParameter("datasourceId");
            Double executionTime = context.getParameter("executionTime");
            String optimizationSuggestion = context.getParameter("optimizationSuggestion");
            List<Map<String, String>> followUpSuggestions = context.getParameter("followUpSuggestions");
            String chartConfig = context.getParameter("chartConfig");

            if (data == null) data = new ArrayList<>();
            if (rowCount == null) rowCount = data.size();

            log.info("[PostProcessTool] 开始组装响应: rowCount={}, sql={}", rowCount, sql);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", true);
            response.put("type", "data");
            response.put("data", data);
            response.put("rowCount", rowCount);
            response.put("executionTime", executionTime != null ? executionTime : 0.0);
            response.put("sql", sql);
            response.put("datasourceId", datasourceId);

            if (followUpSuggestions != null && !followUpSuggestions.isEmpty()) {
                response.put("followUpSuggestions", followUpSuggestions);
            }

            if (optimizationSuggestion != null && !optimizationSuggestion.trim().isEmpty()) {
                response.put("optimizationSuggestion", optimizationSuggestion);
            }

            if (chartConfig != null && !chartConfig.trim().isEmpty()) {
                try {
                    Map<String, Object> chartConfigMap = objectMapper.readValue(chartConfig,
                        new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
                    response.put("chartConfig", chartConfigMap);
                } catch (Exception e) {
                    log.warn("[PostProcessTool] 图表配置解析失败: {}", e.getMessage());
                }
            }

            log.info("[PostProcessTool] 响应组装完成");

            return ToolResult.success(response);

        } catch (Exception e) {
            log.error("[PostProcessTool] 后处理失败", e);
            return ToolResult.error("后处理失败: " + e.getMessage());
        }
    }

    @Deprecated
    @Tool(name = "post_process_response", value = "组装查询结果响应。@Deprecated - 追问建议生成已移至 generateFollowUpSuggestions，图表注入已移至 generateChartConfig")
    public Map<String, Object> execute(
        @P("查询结果数据列表") Object data,
        @P("结果行数") Object rowCount,
        @P("执行的SQL语句") String sql,
        @P("数据源ID") Object datasourceId,
        @P("执行时间（毫秒）") Object executionTime,
        @P("优化建议（可选）") String optimizationSuggestion,
        @P("图表配置JSON（可选，来自generateChartConfig）") String chartConfig
    ) {
        try {
            List<Map<String, Object>> dataList = convertToList(data);
            Integer rc = convertToInt(rowCount);
            if (rc == null) rc = dataList.size();

            Long dsId = convertToLong(datasourceId);
            Double execTime = convertToDouble(executionTime);
            if (execTime == null) execTime = 0.0;

            log.info("[PostProcessTool] 开始组装响应: rowCount={}, sql={}", rc, sql);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", true);
            response.put("type", "data");
            response.put("data", dataList);
            response.put("rowCount", rc);
            response.put("executionTime", execTime);
            response.put("sql", sql);
            response.put("datasourceId", dsId);

            if (optimizationSuggestion != null && !optimizationSuggestion.trim().isEmpty()) {
                response.put("optimizationSuggestion", optimizationSuggestion);
            }

            if (chartConfig != null && !chartConfig.trim().isEmpty()) {
                try {
                    Map<String, Object> chartConfigMap = objectMapper.readValue(chartConfig,
                        new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
                    response.put("chartConfig", chartConfigMap);
                } catch (Exception e) {
                    log.warn("[PostProcessTool] 图表配置解析失败: {}", e.getMessage());
                }
            }

            return response;

        } catch (Exception e) {
            log.error("[PostProcessTool] 后处理失败", e);
            Map<String, Object> errorResponse = new LinkedHashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("error", "后处理失败: " + e.getMessage());
            return errorResponse;
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> convertToList(Object data) {
        if (data == null) return new ArrayList<>();
        if (data instanceof List) return (List<Map<String, Object>>) data;
        if (data instanceof String) {
            try {
                return objectMapper.readValue((String) data, List.class);
            } catch (Exception e) {
                log.warn("[PostProcessTool] 无法将字符串解析为列表: {}", e.getMessage());
                return new ArrayList<>();
            }
        }
        return new ArrayList<>();
    }

    private Integer convertToInt(Object value) {
        if (value == null) return null;
        if (value instanceof Integer) return (Integer) value;
        if (value instanceof Number) return ((Number) value).intValue();
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Long convertToLong(Object value) {
        if (value == null) return null;
        if (value instanceof Long) return (Long) value;
        if (value instanceof Number) return ((Number) value).longValue();
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Double convertToDouble(Object value) {
        if (value == null) return null;
        if (value instanceof Double) return (Double) value;
        if (value instanceof Number) return ((Number) value).doubleValue();
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
