package com.nl2sql.core.agent.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 标准化的Agent响应
 * 
 * 统一所有Agent的返回格式，简化前后端对接
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentResponse {
    
    /**
     * 响应状态（success/error/clarification）
     */
    private String status;
    
    /**
     * 响应类型（sql/text/table/chart等）
     */
    private String responseType;
    
    /**
     * 响应数据
     */
    private Object data;
    
    /**
     * 消息（用户友好的提示）
     */
    private String message;
    
    /**
     * 生成的SQL（如果有）
     */
    private String sql;
    
    /**
     * 执行时间（毫秒）
     */
    private Long executionTimeMs;
    
    /**
     * 会话ID
     */
    private String sessionId;
    
    /**
     * 请求ID（用于追踪）
     */
    private String requestId;
    
    /**
     * 时间戳
     */
    @Builder.Default
    private LocalDateTime timestamp = LocalDateTime.now();
    
    /**
     * 元数据（扩展信息）
     */
    @Builder.Default
    private Map<String, Object> metadata = new HashMap<>();
    
    /**
     * 错误信息（失败时）
     */
    private ErrorDetail error;
    
    /**
     * 澄清信息（需要用户澄清时）
     */
    private ClarificationInfo clarification;
    
    /**
     * 错误详情
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ErrorDetail {
        /**
         * 错误代码
         */
        private String code;
        
        /**
         * 错误消息
         */
        private String message;
        
        /**
         * 建议的解决方案
         */
        private String suggestion;
        
        /**
         * 详细错误信息（开发调试用）
         */
        private String detail;
    }
    
    /**
     * 澄清信息
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ClarificationInfo {
        /**
         * 澄清问题
         */
        private String question;
        
        /**
         * 可选答案列表
         */
        private List<String> options;
        
        /**
         * 澄清类型（table_selection/time_range/column_ambiguity等）
         */
        private String type;
    }
    
    // ==================== 便捷工厂方法 ====================
    
    /**
     * 创建成功响应（SQL结果）
     */
    public static AgentResponse sqlSuccess(String sql, Object data, String sessionId) {
        return AgentResponse.builder()
            .status("success")
            .responseType("sql")
            .sql(sql)
            .data(data)
            .message("SQL生成成功")
            .sessionId(sessionId)
            .build();
    }
    
    /**
     * 创建成功响应（文本结果）
     */
    public static AgentResponse textSuccess(String text, String sessionId) {
        return AgentResponse.builder()
            .status("success")
            .responseType("text")
            .data(text)
            .message("处理成功")
            .sessionId(sessionId)
            .build();
    }
    
    /**
     * 创建成功响应（表格结果）
     */
    public static AgentResponse tableSuccess(List<Map<String, Object>> tableData, String sessionId) {
        return AgentResponse.builder()
            .status("success")
            .responseType("table")
            .data(tableData)
            .message("查询成功")
            .sessionId(sessionId)
            .build();
    }
    
    /**
     * 创建错误响应
     */
    public static AgentResponse error(String code, String message, String suggestion, String sessionId) {
        ErrorDetail error = new ErrorDetail(code, message, suggestion, null);
        return AgentResponse.builder()
            .status("error")
            .error(error)
            .message(message)
            .sessionId(sessionId)
            .build();
    }
    
    /**
     * 创建澄清响应
     */
    public static AgentResponse clarification(String question, List<String> options, String type, String sessionId) {
        ClarificationInfo clarification = new ClarificationInfo(question, options, type);
        return AgentResponse.builder()
            .status("clarification")
            .clarification(clarification)
            .message("需要您的确认")
            .sessionId(sessionId)
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
