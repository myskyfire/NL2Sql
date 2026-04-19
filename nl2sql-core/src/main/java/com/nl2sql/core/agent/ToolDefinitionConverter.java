package com.nl2sql.core.agent;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

/**
 * 工具定义转换器 - 将 ReActAgent 的工具转换为 OpenAI 兼容格式
 */
@Slf4j
public class ToolDefinitionConverter {
    
    /**
     * 将 ReActAgent 的工具列表转换为 OpenAI 兼容的 tools 格式
     */
    public static List<Map<String, Object>> convertToOpenAITools(Map<String, ReActAgent.ToolExecutor> tools) {
        List<Map<String, Object>> openAITools = new ArrayList<>();
        
        for (Map.Entry<String, ReActAgent.ToolExecutor> entry : tools.entrySet()) {
            String toolName = entry.getKey();
            ReActAgent.ToolExecutor executor = entry.getValue();
            
            Map<String, Object> toolDef = new HashMap<>();
            toolDef.put("type", "function");
            
            Map<String, Object> function = new HashMap<>();
            function.put("name", toolName);
            function.put("description", executor.getDescription());
            function.put("parameters", buildParametersSchema(toolName));
            
            toolDef.put("function", function);
            openAITools.add(toolDef);
        }
        
        return openAITools;
    }
    
    /**
     * 构建参数 Schema（简化版，根据工具名推断）
     */
    private static Map<String, Object> buildParametersSchema(String toolName) {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        
        Map<String, Object> properties = new HashMap<>();
        List<String> required = new ArrayList<>();
        
        // ⚠️ 处理 Groovy Skill 的双重前缀问题：execute_execute_standard_query -> execute_standard_query
        String normalizedToolName = toolName;
        if (toolName.startsWith("execute_execute_")) {
            normalizedToolName = "execute_" + toolName.substring("execute_".length());
        }
        
        // 根据工具名推断参数
        switch (normalizedToolName) {
            case "execute_standard_query":
                properties.put("question", createProperty("string", "用户的问题"));
                properties.put("datasourceId", createProperty("integer", "数据源ID"));
                required.addAll(Arrays.asList("question", "datasourceId"));
                break;
                
            case "clarify_datasource":
                properties.put("userQuery", createProperty("string", "用户的查询意图"));
                required.add("userQuery");
                break;
                
            case "summarize_result":
                // ✅ 简化：将 context 展开为顶层参数，避免嵌套 object
                properties.put("lastQuery", createProperty("string", "用户的原始查询问题，例如：统计每个地区的销售额"));
                properties.put("generatedSQL", createProperty("string", "之前生成的 SQL 查询语句"));
                required.add("lastQuery");
                required.add("generatedSQL");
                break;
                
            case "generate_chart":
                // ✅ 简化：将 context 展开为顶层参数
                properties.put("chartType", createProperty("string", "图表类型：bar-柱状图/line-折线图/pie-饼图/area-面积图，如果为 null 则自动推荐"));
                properties.put("generatedSQL", createProperty("string", "SQL查询语句，用于获取图表数据"));
                required.add("generatedSQL");
                break;
                
            default:
                // 通用处理：接受任意参数
                properties.put("args", createProperty("object", "工具参数"));
                break;
        }
        
        schema.put("properties", properties);
        schema.put("required", required);
        
        return schema;
    }
    
    private static Map<String, Object> createProperty(String type, String description) {
        Map<String, Object> prop = new HashMap<>();
        prop.put("type", type);
        prop.put("description", description);
        return prop;
    }
}
