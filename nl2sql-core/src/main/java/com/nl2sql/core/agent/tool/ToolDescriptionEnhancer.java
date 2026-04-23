package com.nl2sql.core.agent.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Tool描述增强器
 * 
 * 动态生成System Prompt，帮助LLM更好地理解和使用Tool
 */
@Slf4j
@Component
public class ToolDescriptionEnhancer {
    
    /**
     * 生成增强的Tool描述（用于System Prompt）
     */
    public String generateEnhancedDescription(List<BaseTool> tools) {
        if (tools == null || tools.isEmpty()) {
            return "";
        }
        
        StringBuilder sb = new StringBuilder();
        sb.append("## 可用工具\n\n");
        
        for (BaseTool tool : tools) {
            sb.append(generateToolDescription(tool));
            sb.append("\n---\n\n");
        }
        
        return sb.toString();
    }
    
    /**
     * 生成单个Tool的详细描述
     */
    private String generateToolDescription(BaseTool tool) {
        StringBuilder sb = new StringBuilder();
        
        // 工具名称和描述
        sb.append(String.format("### %s\n", tool.getName()));
        sb.append(String.format("**描述**: %s\n\n", tool.getDescription()));
        
        // 适用场景
        String applicableScenarios = tool.getApplicableScenarios();
        if (applicableScenarios != null && !applicableScenarios.isEmpty()) {
            sb.append("**适用场景**:\n");
            sb.append(applicableScenarios);
            sb.append("\n\n");
        }
        
        // 不适用场景
        String inapplicableScenarios = tool.getInapplicableScenarios();
        if (inapplicableScenarios != null && !inapplicableScenarios.isEmpty()) {
            sb.append("**不适用场景**:\n");
            sb.append(inapplicableScenarios);
            sb.append("\n\n");
        }
        
        // 参数说明
        sb.append("**参数**:\n");
        sb.append(formatParameterSchema(tool.getParameterSchema()));
        
        return sb.toString();
    }
    
    /**
     * 格式化参数Schema为可读文本
     */
    private String formatParameterSchema(java.util.Map<String, Object> schema) {
        if (schema == null || schema.isEmpty()) {
            return "无参数\n";
        }
        
        StringBuilder sb = new StringBuilder();
        
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> properties = (java.util.Map<String, Object>) schema.get("properties");
        
        @SuppressWarnings("unchecked")
        java.util.List<String> required = (java.util.List<String>) schema.get("required");
        
        if (properties != null) {
            for (java.util.Map.Entry<String, Object> entry : properties.entrySet()) {
                String paramName = entry.getKey();
                @SuppressWarnings("unchecked")
                java.util.Map<String, Object> paramInfo = (java.util.Map<String, Object>) entry.getValue();
                
                boolean isRequired = required != null && required.contains(paramName);
                String type = (String) paramInfo.getOrDefault("type", "unknown");
                String description = (String) paramInfo.getOrDefault("description", "");
                
                sb.append(String.format("- `%s` (%s%s): %s\n", 
                    paramName, 
                    type, 
                    isRequired ? ", 必填" : "",
                    description));
            }
        }
        
        return sb.toString();
    }
    
    /**
     * 生成Tool选择建议
     */
    public String generateToolSelectionGuide(List<BaseTool> tools) {
        if (tools == null || tools.isEmpty()) {
            return "";
        }
        
        StringBuilder sb = new StringBuilder();
        sb.append("## Tool选择指南\n\n");
        sb.append("根据用户需求选择合适的Tool：\n\n");
        
        for (BaseTool tool : tools) {
            sb.append(String.format("- **%s**: %s\n", tool.getName(), tool.getDescription()));
        }
        
        sb.append("\n**注意事项**:\n");
        sb.append("1. 优先使用最匹配用户需求的Tool\n");
        sb.append("2. 如果不确定，可以调用多个Tool获取更多信息\n");
        sb.append("3. 避免调用不适用的Tool\n");
        
        return sb.toString();
    }
    
    /**
     * 生成完整的System Prompt（包含Tool描述）
     */
    public String generateSystemPrompt(String basePrompt, List<BaseTool> tools) {
        StringBuilder sb = new StringBuilder();
        
        // 基础Prompt
        if (basePrompt != null && !basePrompt.isEmpty()) {
            sb.append(basePrompt);
            sb.append("\n\n");
        }
        
        // Tool描述
        String toolDescription = generateEnhancedDescription(tools);
        if (!toolDescription.isEmpty()) {
            sb.append(toolDescription);
        }
        
        // Tool选择指南
        String selectionGuide = generateToolSelectionGuide(tools);
        if (!selectionGuide.isEmpty()) {
            sb.append(selectionGuide);
        }
        
        return sb.toString();
    }
}
