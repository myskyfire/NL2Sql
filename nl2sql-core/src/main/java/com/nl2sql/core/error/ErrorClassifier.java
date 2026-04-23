package com.nl2sql.core.error;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * 错误分类器
 * 
 * 根据异常信息自动识别错误类型，用于统一的错误处理和重试策略
 */
@Slf4j
@Component
public class ErrorClassifier {
    
    // SQL错误模式
    private static final Pattern TABLE_NOT_FOUND_PATTERN = 
        Pattern.compile("table.*doesn't exist|Table.*doesn't exist", Pattern.CASE_INSENSITIVE);
    private static final Pattern COLUMN_NOT_FOUND_PATTERN = 
        Pattern.compile("Unknown column|Column.*not found", Pattern.CASE_INSENSITIVE);
    private static final Pattern SYNTAX_ERROR_PATTERN = 
        Pattern.compile("syntax error|SQLSyntaxErrorException", Pattern.CASE_INSENSITIVE);
    private static final Pattern AMBIGUOUS_COLUMN_PATTERN = 
        Pattern.compile("ambiguous|Ambiguous", Pattern.CASE_INSENSITIVE);
    private static final Pattern DEADLOCK_PATTERN = 
        Pattern.compile("deadlock|Deadlock", Pattern.CASE_INSENSITIVE);
    private static final Pattern TIMEOUT_PATTERN = 
        Pattern.compile("timeout|timed out|Query execution was interrupted", Pattern.CASE_INSENSITIVE);
    
    // LLM错误模式
    private static final Pattern LLM_TIMEOUT_PATTERN = 
        Pattern.compile("LLM.*timeout|OpenAI.*timeout|ollama.*timeout", Pattern.CASE_INSENSITIVE);
    private static final Pattern LLM_SERVICE_PATTERN = 
        Pattern.compile("service unavailable|503|connection refused", Pattern.CASE_INSENSITIVE);
    
    // 网络错误模式
    private static final Pattern NETWORK_ERROR_PATTERN = 
        Pattern.compile("ConnectException|SocketTimeoutException|Network", Pattern.CASE_INSENSITIVE);
    
    // 权限错误模式
    private static final Pattern PERMISSION_DENIED_PATTERN = 
        Pattern.compile("access denied|permission denied|无权访问", Pattern.CASE_INSENSITIVE);
    
    /**
     * 根据异常分类错误类型
     */
    public ErrorType classify(Exception e) {
        if (e == null) {
            return ErrorType.UNKNOWN;
        }
        
        String message = e.getMessage();
        if (message == null || message.isEmpty()) {
            return ErrorType.SYSTEM_ERROR;
        }
        
        // 1. 检查是否是已知的业务异常
        if (e instanceof IllegalArgumentException) {
            return ErrorType.INVALID_PARAMETER_FORMAT;
        }
        
        // 2. 根据异常类型和消息内容分类
        String className = e.getClass().getSimpleName();
        
        // SQL相关错误
        if (isSQLRelated(className)) {
            return classifySQLError(message);
        }
        
        // LLM相关错误
        if (isLLMRelated(className, message)) {
            return classifyLLMError(message);
        }
        
        // 网络相关错误
        if (isNetworkRelated(className, message)) {
            return classifyNetworkError(message);
        }
        
        // 权限相关错误
        if (isPermissionRelated(message)) {
            return ErrorType.PERMISSION_DENIED;
        }
        
        // 默认返回系统错误
        log.warn("[ErrorClassifier] 未识别的错误类型: {} - {}", className, message);
        return ErrorType.SYSTEM_ERROR;
    }
    
    /**
     * 根据异常和上下文分类（更精确）
     */
    public ErrorType classify(Exception e, String context) {
        ErrorType baseType = classify(e);
        
        // 如果有额外上下文，可以进一步细化
        if (context != null && !context.isEmpty()) {
            if (context.contains("table") && baseType == ErrorType.SYSTEM_ERROR) {
                return ErrorType.TABLE_NOT_FOUND;
            }
            if (context.contains("column") && baseType == ErrorType.SYSTEM_ERROR) {
                return ErrorType.COLUMN_NOT_FOUND;
            }
        }
        
        return baseType;
    }
    
    /**
     * 判断是否应该重试
     */
    public boolean shouldRetry(Exception e) {
        ErrorType errorType = classify(e);
        return errorType.isRetryable();
    }
    
    /**
     * 判断是否应该重试（带最大重试次数检查）
     */
    public boolean shouldRetry(Exception e, int currentAttempt, int maxAttempts) {
        if (currentAttempt >= maxAttempts) {
            return false;
        }
        return shouldRetry(e);
    }
    
    // ==================== 私有辅助方法 ====================
    
    private boolean isSQLRelated(String className) {
        return className.contains("SQL") || 
               className.contains("Jdbc") || 
               className.contains("Database");
    }
    
    private ErrorType classifySQLError(String message) {
        if (TABLE_NOT_FOUND_PATTERN.matcher(message).find()) {
            return ErrorType.TABLE_NOT_FOUND;
        }
        if (COLUMN_NOT_FOUND_PATTERN.matcher(message).find()) {
            return ErrorType.COLUMN_NOT_FOUND;
        }
        if (SYNTAX_ERROR_PATTERN.matcher(message).find()) {
            return ErrorType.SYNTAX_ERROR;
        }
        if (AMBIGUOUS_COLUMN_PATTERN.matcher(message).find()) {
            return ErrorType.AMBIGUOUS_COLUMN;
        }
        if (DEADLOCK_PATTERN.matcher(message).find()) {
            return ErrorType.DEADLOCK;
        }
        if (TIMEOUT_PATTERN.matcher(message).find()) {
            return ErrorType.QUERY_TIMEOUT;
        }
        return ErrorType.DATABASE_CONNECTION_ERROR;
    }
    
    private boolean isLLMRelated(String className, String message) {
        return className.contains("LLM") || 
               className.contains("OpenAI") || 
               className.contains("Ollama") ||
               message.toLowerCase().contains("llm") ||
               message.toLowerCase().contains("openai") ||
               message.toLowerCase().contains("ollama");
    }
    
    private ErrorType classifyLLMError(String message) {
        if (LLM_TIMEOUT_PATTERN.matcher(message).find() || 
            TIMEOUT_PATTERN.matcher(message).find()) {
            return ErrorType.LLM_TIMEOUT;
        }
        if (LLM_SERVICE_PATTERN.matcher(message).find()) {
            return ErrorType.LLM_SERVICE_UNAVAILABLE;
        }
        return ErrorType.LLM_INVALID_RESPONSE;
    }
    
    private boolean isNetworkRelated(String className, String message) {
        return NETWORK_ERROR_PATTERN.matcher(className).find() ||
               NETWORK_ERROR_PATTERN.matcher(message).find();
    }
    
    private ErrorType classifyNetworkError(String message) {
        if (TIMEOUT_PATTERN.matcher(message).find()) {
            return ErrorType.REQUEST_TIMEOUT;
        }
        return ErrorType.NETWORK_ERROR;
    }
    
    private boolean isPermissionRelated(String message) {
        return PERMISSION_DENIED_PATTERN.matcher(message).find();
    }
}
