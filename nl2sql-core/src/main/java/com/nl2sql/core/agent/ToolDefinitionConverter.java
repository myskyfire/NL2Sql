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
            
            log.debug("[ToolConverter] 转换工具: {} -> {}", toolName, executor.getDescription());
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
        
        // 根据工具名推断参数
        switch (toolName) {
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
                properties.put("context", createProperty("object", "上下文信息，包含 lastQuery 和 generatedSQL"));
                required.add("context");
                break;
                
            case "generate_chart":
                properties.put("context", createProperty("object", "上下文信息，包含 chartType 和 generatedSQL"));
                required.add("context");
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
