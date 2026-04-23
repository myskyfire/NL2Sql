package com.nl2sql.core.error;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 标准化的错误响应
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ErrorResponse {
    
    /**
     * 错误类型
     */
    private ErrorType errorType;
    
    /**
     * 错误代码（用于前端识别）
     */
    private String errorCode;
    
    /**
     * 错误消息（用户友好）
     */
    private String message;
    
    /**
     * 详细错误信息（开发调试用）
     */
    private String detail;
    
    /**
     * 发生时间
     */
    @Builder.Default
    private LocalDateTime timestamp = LocalDateTime.now();
    
    /**
     * 请求ID（用于追踪）
     */
    private String requestId;
    
    /**
     * 额外上下文信息
     */
    @Builder.Default
    private Map<String, Object> context = new HashMap<>();
    
    /**
     * 建议的解决方案
     */
    private String suggestion;
    
    /**
     * 从异常创建错误响应
     */
    public static ErrorResponse fromException(Exception e, ErrorType errorType) {
        return ErrorResponse.builder()
            .errorType(errorType)
            .errorCode(errorType.name())
            .message(errorType.getDefaultMessage())
            .detail(e.getMessage())
            .suggestion(generateSuggestion(errorType))
            .build();
    }
    
    /**
     * 从异常创建错误响应（带上下文）
     */
    public static ErrorResponse fromException(Exception e, ErrorType errorType, Map<String, Object> context) {
        return ErrorResponse.builder()
            .errorType(errorType)
            .errorCode(errorType.name())
            .message(errorType.getDefaultMessage())
            .detail(e.getMessage())
            .context(context)
            .suggestion(generateSuggestion(errorType))
            .build();
    }
    
    /**
     * 生成建议的解决方案
     */
    private static String generateSuggestion(ErrorType errorType) {
        switch (errorType) {
            case TABLE_NOT_FOUND:
                return "请检查表名是否正确，或联系管理员确认您是否有该表的访问权限";
            case COLUMN_NOT_FOUND:
                return "请检查字段名是否正确，注意字段名区分大小写";
            case SYNTAX_ERROR:
                return "系统正在尝试自动修正SQL语法，请稍后重试";
            case PERMISSION_DENIED:
            case TABLE_PERMISSION_DENIED:
                return "请联系管理员添加相应的访问权限";
            case LLM_TIMEOUT:
            case NETWORK_ERROR:
                return "网络波动导致超时，系统正在自动重试";
            case USER_INPUT_ERROR:
                return "请检查输入内容是否符合要求";
            default:
                return "如果问题持续存在，请联系技术支持";
        }
    }
}
