package com.nl2sql.core.agent.skills;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;

/**
 * Skill 统一响应结果
 * 
 * 设计目标：
 * 1. 所有 Skills 必须返回此格式，确保一致性
 * 2. 支持多种响应类型（查询结果、澄清、错误等）
 * 3. 包含元数据用于监控和调试
 * 
 * 使用示例：
 * <pre>
 * // 成功响应
 * SkillResult.success()
 *     .type(ResultType.QUERY_RESULT)
 *     .data(queryData)
 *     .metadata(Map.of("rowCount", 100, "sql", sql))
 *     .build();
 * 
 * // 澄清响应
 * SkillResult.clarification()
 *     .clarificationType("datasource_selection")
 *     .message("请选择数据源")
 *     .options(datasourceList)
 *     .build();
 * 
 * // 错误响应
 * SkillResult.error()
 *     .errorCode("SQL_SYNTAX_ERROR")
 *     .errorMessage("SQL语法错误")
 *     .suggestion("请检查SQL语句")
 *     .build();
 * </pre>
 */
@Slf4j
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)  // ✅ 忽略未知字段(兼容Groovy脚本返回的扁平化结构)
public class SkillResult {
    
    private static final ObjectMapper objectMapper = new ObjectMapper();
    
    static {
        // ✅ 配置枚举大小写不敏感
        objectMapper.configure(com.fasterxml.jackson.databind.MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS, true);
    }
    
    /**
     * 是否成功
     */
    @Builder.Default
    private boolean success = true;
    
    /**
     * 响应类型
     */
    @Builder.Default
    private ResultType type = ResultType.QUERY_RESULT;
    
    /**
     * 响应数据（成功时）
     */
    private Object data;
    
    /**
     * 澄清信息（澄清类型时使用）
     */
    private ClarificationInfo clarification;
    
    /**
     * 错误信息（失败时使用）
     */
    private ErrorInfo error;
    
    /**
     * 元数据（执行时间、调用次数等）
     */
    @Builder.Default
    private Map<String, Object> metadata = new HashMap<>();
    
    /**
     * 响应类型枚举
     */
    public enum ResultType {
        QUERY_RESULT,      // 查询结果(标准)
        DATA,              // 查询结果(别名,兼容旧代码)
        SUMMARY,           // AI总结
        CHART,             // 图表配置
        CLARIFICATION,     // 需要澄清
        ERROR,             // 错误
        PROGRESS           // 进度更新（流式响应）
    }
    
    /**
     * 澄清信息
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public static class ClarificationInfo {
        private String clarificationType;  // datasource_selection, table_missing, etc.
        private String message;            // 澄清消息
        private Object options;            // 可选选项列表
        private Boolean autoExecuted;      // 是否已自动执行
        private Long recommendedDatasourceId;  // 推荐的数据源ID
    }
    
    /**
     * 错误信息
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public static class ErrorInfo {
        private String errorCode;          // 错误代码
        private String errorMessage;       // 错误消息
        private String suggestion;         // 建议操作
        private Object context;            // 错误上下文
    }
    
    // ==================== 便捷构建方法 ====================
    
    /**
     * 创建成功响应
     */
    public static SkillResult success(Object data) {
        return SkillResult.builder()
            .success(true)
            .type(ResultType.QUERY_RESULT)
            .data(data)
            .build();
    }
    
    /**
     * 创建澄清响应
     */
    public static SkillResult clarification(String type, String message) {
        return SkillResult.builder()
            .success(true)
            .type(ResultType.CLARIFICATION)
            .clarification(ClarificationInfo.builder()
                .clarificationType(type)
                .message(message)
                .build())
            .build();
    }
    
    /**
     * 创建错误响应
     */
    public static SkillResult error(String errorCode, String errorMessage) {
        return SkillResult.builder()
            .success(false)
            .type(ResultType.ERROR)
            .error(ErrorInfo.builder()
                .errorCode(errorCode)
                .errorMessage(errorMessage)
                .build())
            .build();
    }
    
    /**
     * 转换为 JSON 字符串
     */
    public String toJson() {
        try {
            return objectMapper.writeValueAsString(this);
        } catch (Exception e) {
            log.error("[SkillResult] 序列化失败", e);
            return "{\"success\":false,\"error\":{\"errorMessage\":\"序列化失败\"}}";
        }
    }
    
    /**
     * 从 JSON 字符串解析
     */
    public static SkillResult fromJson(String json) {
        try {
            return objectMapper.readValue(json, SkillResult.class);
        } catch (Exception e) {
            log.error("[SkillResult] 反序列化失败: {}", json, e);
            return SkillResult.builder()
                .success(false)
                .type(ResultType.ERROR)
                .error(ErrorInfo.builder()
                    .errorCode("DESERIALIZE_ERROR")
                    .errorMessage("JSON解析失败: " + e.getMessage())
                    .build())
                .build();
        }
    }
}
