package com.nl2sql.core.agent.tool;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

/**
 * 标准化的 Tool 执行结果
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolResult {
    
    /**
     * 是否成功
     */
    private boolean success;
    
    /**
     * 返回数据（成功时）
     */
    private Object data;
    
    /**
     * 错误信息（失败时）
     */
    private String errorMessage;
    
    /**
     * 元数据（执行时间、调用次数等）
     */
    @Builder.Default
    private Map<String, Object> metadata = new HashMap<>();
    
    /**
     * 创建成功结果
     */
    public static ToolResult success(Object data) {
        return ToolResult.builder()
            .success(true)
            .data(data)
            .build();
    }
    
    /**
     * 创建成功结果（带元数据）
     */
    public static ToolResult success(Object data, Map<String, Object> metadata) {
        return ToolResult.builder()
            .success(true)
            .data(data)
            .metadata(metadata)
            .build();
    }
    
    /**
     * 创建失败结果
     */
    public static ToolResult error(String errorMessage) {
        return ToolResult.builder()
            .success(false)
            .errorMessage(errorMessage)
            .build();
    }
    
    /**
     * 创建失败结果（带元数据）
     */
    public static ToolResult error(String errorMessage, Map<String, Object> metadata) {
        return ToolResult.builder()
            .success(false)
            .errorMessage(errorMessage)
            .metadata(metadata)
            .build();
    }
    
    /**
     * 添加元数据
     */
    public void addMetadata(String key, Object value) {
        if (this.metadata == null) {
            this.metadata = new HashMap<>();
        }
        this.metadata.put(key, value);
    }
}
