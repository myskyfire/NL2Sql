package com.nl2sql.core.agent.tool;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

/**
 * Tool 执行上下文
 * 
 * 封装执行所需的所有信息，包括用户信息、数据源、历史对话等
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolContext {
    
    /**
     * 用户ID
     */
    private Long userId;
    
    /**
     * 用户名
     */
    private String username;
    
    /**
     * 会话ID
     */
    private String sessionId;
    
    /**
     * 数据源ID
     */
    private Long datasourceId;
    
    /**
     * 用户原始消息
     */
    private String userMessage;
    
    /**
     * 工具参数（由LLM生成）
     */
    @Builder.Default
    private Map<String, Object> parameters = new HashMap<>();
    
    /**
     * 对话历史（可选）
     */
    private Object conversationHistory;
    
    /**
     * 获取参数值
     */
    @SuppressWarnings("unchecked")
    public <T> T getParameter(String key) {
        return (T) parameters.get(key);
    }
    
    /**
     * 获取必填参数，不存在则抛出异常
     */
    @SuppressWarnings("unchecked")
    public <T> T getRequiredParameter(String key) {
        Object value = parameters.get(key);
        if (value == null) {
            throw new IllegalArgumentException("缺少必填参数: " + key);
        }
        return (T) value;
    }
}
