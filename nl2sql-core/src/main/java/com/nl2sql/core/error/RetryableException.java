package com.nl2sql.core.error;

import lombok.Getter;

/**
 * 可重试异常
 * 
 * 包装需要重试的异常，携带错误类型信息
 */
@Getter
public class RetryableException extends RuntimeException {
    
    private final ErrorType errorType;
    
    public RetryableException(ErrorType errorType, String message) {
        super(message);
        this.errorType = errorType;
    }
    
    public RetryableException(ErrorType errorType, String message, Throwable cause) {
        super(message, cause);
        this.errorType = errorType;
    }
}
