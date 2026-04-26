package com.nl2sql.core.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * Tool/Skill 统一响应构建器
 * 
 * 使用示例：
 * <pre>
 * // 成功响应
 * return ToolResponseBuilder.success("data")
 *     .withData(Map.of("rows", results, "rowCount", 100))
 *     .withMetadata(Map.of("toolName", "execute_sql", "executionTimeMs", 123))
 *     .build();
 * 
 * // 澄清响应
 * return ToolResponseBuilder.clarification("datasource_recommendation")
 *     .withMessage("推荐使用数据源：xxx")
 *     .withAutoExecuted(true)
 *     .withRecommendedDatasourceId(1L)
 *     .build();
 * 
 * // 错误响应
 * return ToolResponseBuilder.error("SQL_ERROR", "SQL语法错误")
 *     .build();
 * </pre>
 */
public class ToolResponseBuilder {
    
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    
    private boolean success;
    private String type;
    private Object data;
    private Map<String, Object> clarification;
    private Map<String, Object> error;
    private Map<String, Object> metadata;
    
    private ToolResponseBuilder() {}
    
    /**
     * 构建成功响应
     */
    public static ToolResponseBuilder success(String type) {
        ToolResponseBuilder builder = new ToolResponseBuilder();
        builder.success = true;
        builder.type = type;
        return builder;
    }
    
    /**
     * 构建澄清响应
     */
    public static ToolResponseBuilder clarification(String clarificationType) {
        ToolResponseBuilder builder = new ToolResponseBuilder();
        builder.success = true;
        builder.type = "clarification";
        builder.clarification = new HashMap<>();
        builder.clarification.put("clarificationType", clarificationType);
        return builder;
    }
    
    /**
     * 构建错误响应
     */
    public static ToolResponseBuilder error(String code, String message) {
        ToolResponseBuilder builder = new ToolResponseBuilder();
        builder.success = false;
        builder.type = "error";
        builder.error = new HashMap<>();
        builder.error.put("code", code);
        builder.error.put("message", message);
        return builder;
    }
    
    /**
     * 设置业务数据
     */
    public ToolResponseBuilder withData(Object data) {
        this.data = data;
        return this;
    }
    
    /**
     * 设置元数据
     */
    public ToolResponseBuilder withMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
        return this;
    }
    
    /**
     * 设置澄清消息
     */
    public ToolResponseBuilder withMessage(String message) {
        if (this.clarification != null) {
            this.clarification.put("message", message);
        }
        return this;
    }
    
    /**
     * 设置自动执行标志
     */
    public ToolResponseBuilder withAutoExecuted(boolean autoExecuted) {
        if (this.clarification != null) {
            this.clarification.put("autoExecuted", autoExecuted);
        }
        return this;
    }
    
    /**
     * 设置推荐的数据源ID
     */
    public ToolResponseBuilder withRecommendedDatasourceId(Long datasourceId) {
        if (this.clarification != null && datasourceId != null) {
            this.clarification.put("recommendedDatasourceId", datasourceId);
        }
        return this;
    }
    
    /**
     * 设置澄清上下文
     */
    public ToolResponseBuilder withContext(Map<String, Object> context) {
        if (this.clarification != null && context != null) {
            this.clarification.put("context", context);
        }
        return this;
    }
    
    /**
     * 添加元数据字段
     */
    public ToolResponseBuilder addMetadata(String key, Object value) {
        if (this.metadata == null) {
            this.metadata = new HashMap<>();
        }
        this.metadata.put(key, value);
        return this;
    }
    
    /**
     * 构建JSON响应
     */
    public String build() {
        try {
            Map<String, Object> response = new HashMap<>();
            response.put("success", this.success);
            response.put("type", this.type);
            
            if (this.data != null) {
                response.put("data", this.data);
            }
            
            if (this.clarification != null) {
                response.put("clarification", this.clarification);
            }
            
            if (this.error != null) {
                response.put("error", this.error);
            }
            
            // ✅ 自动添加时间戳
            if (this.metadata == null) {
                this.metadata = new HashMap<>();
            }
            this.metadata.putIfAbsent("timestamp", LocalDateTime.now().format(FORMATTER));
            response.put("metadata", this.metadata);
            
            return objectMapper.writeValueAsString(response);
            
        } catch (Exception e) {
            // 序列化失败，返回标准错误
            return "{\"success\":false,\"type\":\"error\",\"error\":{\"code\":\"JSON_ERROR\",\"message\":\"响应序列化失败\"}}";
        }
    }
}
