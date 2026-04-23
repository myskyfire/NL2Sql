package com.nl2sql.core.agent.tool;

import java.util.Map;

/**
 * 统一的 Tool 接口
 * 
 * 所有 Tool 实现此接口，提供标准化的执行契约
 */
public interface BaseTool {
    
    /**
     * 获取工具名称（唯一标识）
     */
    String getName();
    
    /**
     * 获取工具描述（用于LLM理解工具用途）
     */
    String getDescription();
    
    /**
     * 获取参数Schema（JSON Schema格式，用于LLM生成参数）
     */
    Map<String, Object> getParameterSchema();
    
    /**
     * 执行工具
     * 
     * @param context 执行上下文（包含用户信息、数据源、历史对话等）
     * @return 标准化的执行结果
     */
    ToolResult execute(ToolContext context);
    
    /**
     * 获取适用场景描述（帮助LLM正确选择工具）
     */
    default String getApplicableScenarios() {
        return "";
    }
    
    /**
     * 获取不适用场景描述（避免LLM误用）
     */
    default String getInapplicableScenarios() {
        return "";
    }
}
