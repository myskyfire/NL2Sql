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
import java.util.regex.Pattern;

/**
 * 后处理 Tool - 构建完整响应（等效于 Groovy createSuccessResult + generateFollowUpSuggestions）
 * 
 * 功能：
 * 1. 根据数据特征智能生成追问建议
 * 2. 添加中风险优化建议
 * 3. 检测图表需求并生成ECharts配置
 */
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
        return "后处理查询结果，生成智能追问建议、优化建议和图表配置。等效于Groovy脚本的createSuccessResult逻辑。";
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
        
        schema.put("properties", properties);
        schema.put("required", Arrays.asList("data", "rowCount", "sql", "datasourceId"));
        
        return schema;
    }
    
    /**
     * ✅ BaseTool接口方法 - 供内部调用
     */
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
            
            if (data == null) data = new ArrayList<>();
            if (rowCount == null) rowCount = data.size();
            
            log.info("[PostProcessTool] 开始构建响应: rowCount={}, sql={}", rowCount, sql);
            
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", true);
            response.put("type", "data");
            response.put("data", data);
            response.put("rowCount", rowCount);
            response.put("executionTime", executionTime != null ? executionTime : 0.0);
            response.put("sql", sql);
            response.put("datasourceId", datasourceId);
            
            List<Map<String, String>> followUpSuggestions = generateFollowUpSuggestions(data, rowCount, sql);
            if (!followUpSuggestions.isEmpty()) {
                response.put("followUpSuggestions", followUpSuggestions);
            }
            
            if (optimizationSuggestion != null && !optimizationSuggestion.trim().isEmpty()) {
                response.put("optimizationSuggestion", optimizationSuggestion);
            }
            
            log.info("[PostProcessTool] 响应构建完成: followUpSuggestions={}", followUpSuggestions.size());
            
            return ToolResult.success(response);
            
        } catch (Exception e) {
            log.error("[PostProcessTool] 后处理失败", e);
            return ToolResult.error("后处理失败: " + e.getMessage());
        }
    }
    
    /**
     * ✅ LangChain4j Tool方法 - 供WorkflowEngine调用
     */
    @Tool(name = "post_process_response", value = "后处理查询结果，生成智能追问建议、优化建议和图表配置")
    public Map<String, Object> execute(
        @P("查询结果数据列表") Object data,
        @P("结果行数") Object rowCount,
        @P("执行的SQL语句") String sql,
        @P("数据源ID") Object datasourceId,
        @P("执行时间（毫秒）") Object executionTime,
        @P("优化建议（可选）") String optimizationSuggestion,
        @P("图表配置JSON（可选，来自ChartDetectionTool）") String chartConfig
    ) {
        try {
            // ✅ 极致兼容的类型转换
            List<Map<String, Object>> dataList = convertToList(data);
            Integer rc = convertToInt(rowCount);
            if (rc == null) rc = dataList.size();
            
            Long dsId = convertToLong(datasourceId);
            Double execTime = convertToDouble(executionTime);
            if (execTime == null) execTime = 0.0;

            log.info("[PostProcessTool] 开始构建响应: rowCount={}, sql={}", rc, sql);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", true);
            response.put("type", "data");
            response.put("data", dataList);
            response.put("rowCount", rc);
            response.put("executionTime", execTime);
            response.put("sql", sql);
            response.put("datasourceId", dsId);

            List<Map<String, String>> followUpSuggestions = generateFollowUpSuggestions(dataList, rc, sql);
            if (!followUpSuggestions.isEmpty()) {
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
        // 如果是字符串，尝试解析 JSON
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
    
    /**
     * ✅ 根据数据特征智能生成追问建议（等效于 Groovy generateFollowUpSuggestions）
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, String>> generateFollowUpSuggestions(List<Map<String, Object>> data, 
                                                                   int rowCount, String sql) {
        List<Map<String, String>> suggestions = new ArrayList<>();
        
        // ✅ 规则1：只有统计数据才提供 AI 总结（明细数据不提供）
        if (data != null && !data.isEmpty() && isStatisticalData(sql)) {
            Map<String, String> summarySuggestion = new LinkedHashMap<>();
            summarySuggestion.put("text", "🤖 AI 总结");
            summarySuggestion.put("action", "generate_summary");
            suggestions.add(summarySuggestion);
        }
        
        // ✅ 规则2：只有统计数据且有数值字段，才提供图表生成（明细数据禁止推荐）
        if (data != null && rowCount >= 2 && hasNumericColumn(data) && isStatisticalData(sql)) {
            Map<String, String> chartSuggestion = new LinkedHashMap<>();
            chartSuggestion.put("text", "📊 生成图表");
            chartSuggestion.put("action", "generate_chart");
            suggestions.add(chartSuggestion);
        }
        
        // ✅ 规则3：只要有数据就提供下载（包括明细和统计）
        if (data != null && !data.isEmpty()) {
            Map<String, String> exportSuggestion = new LinkedHashMap<>();
            exportSuggestion.put("text", "💾 下载 Excel");
            exportSuggestion.put("action", "export_excel");
            suggestions.add(exportSuggestion);
        }
        
        return suggestions;
    }
    
    /**
     * 判断是否为统计数据（而非明细数据）
     * 通过SQL关键字判断：包含 GROUP BY、聚合函数等为统计
     */
    private boolean isStatisticalData(String sql) {
        if (sql == null || sql.isEmpty()) {
            return false;
        }
        
        String upperSql = sql.toUpperCase();
        
        // 检查是否包含聚合函数
        return upperSql.contains("GROUP BY") ||
               upperSql.contains("COUNT(") ||
               upperSql.contains("SUM(") ||
               upperSql.contains("AVG(") ||
               upperSql.contains("MAX(") ||
               upperSql.contains("MIN(");
    }
    
    /**
     * 检查数据是否包含数值字段
     */
    @SuppressWarnings("unchecked")
    private boolean hasNumericColumn(List<Map<String, Object>> data) {
        if (data == null || data.isEmpty()) {
            return false;
        }
        
        // 检查第一行数据的值是否有数值类型
        Map<String, Object> firstRow = data.get(0);
        for (Object value : firstRow.values()) {
            if (value instanceof Number) {
                return true;
            }
            // 尝试解析字符串是否为数字
            if (value instanceof String) {
                try {
                    Double.parseDouble((String) value);
                    return true;
                } catch (NumberFormatException e) {
                    // 不是数字，继续检查
                }
            }
        }
        
        return false;
    }
}
