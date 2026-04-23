package com.nl2sql.core.error;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.Callable;
import java.util.function.Supplier;

/**
 * 可重试执行器
 * 
 * 提供统一的重试机制，支持指数退避策略
 */
@Slf4j
@Component
public class RetryableExecutor {
    
    private static final int DEFAULT_MAX_ATTEMPTS = 3;
    private static final long DEFAULT_INITIAL_DELAY_MS = 1000; // 1秒
    private static final double DEFAULT_BACKOFF_MULTIPLIER = 2.0; // 指数退避
    
    /**
     * 执行可重试任务（使用默认配置）
     */
    public <T> T executeWithRetry(Supplier<T> task, ErrorClassifier errorClassifier) {
        return executeWithRetry(task, errorClassifier, DEFAULT_MAX_ATTEMPTS);
    }
    
    /**
     * 执行可重试任务（指定最大重试次数）
     */
    public <T> T executeWithRetry(Supplier<T> task, ErrorClassifier errorClassifier, int maxAttempts) {
        return executeWithRetry(task, errorClassifier, maxAttempts, DEFAULT_INITIAL_DELAY_MS, DEFAULT_BACKOFF_MULTIPLIER);
    }
    
    /**
     * 执行可重试任务（完整配置）
     */
    public <T> T executeWithRetry(Supplier<T> task, ErrorClassifier errorClassifier, 
                                   int maxAttempts, long initialDelayMs, double backoffMultiplier) {
        Exception lastException = null;
        
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                log.debug("[RetryableExecutor] 执行尝试 {}/{}", attempt, maxAttempts);
                return task.get();
                
            } catch (Exception e) {
                lastException = e;
                ErrorType errorType = errorClassifier.classify(e);
                
                // 检查是否应该重试
                if (!errorType.isRetryable() || attempt >= maxAttempts) {
                    log.error("[RetryableExecutor] 错误类型: {}, 不再重试", errorType);
                    throw new RetryableException(errorType, e.getMessage(), e);
                }
                
                // 计算等待时间（指数退避）
                long delayMs = (long) (initialDelayMs * Math.pow(backoffMultiplier, attempt - 1));
                log.warn("[RetryableExecutor] 第{}次尝试失败: {}, {}ms后重试", 
                        attempt, errorType.getDefaultMessage(), delayMs);
                
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RetryableException(ErrorType.SYSTEM_ERROR, "重试被中断", ie);
                }
            }
        }
        
        // 理论上不会到达这里
        throw new RetryableException(ErrorType.UNKNOWN, "未知错误", lastException);
    }
    
    /**
     * 执行可重试任务（无返回值）
     */
    public void executeRunnableWithRetry(Runnable task, ErrorClassifier errorClassifier) {
        executeWithRetry(() -> {
            task.run();
            return null;
        }, errorClassifier);
    }
    
    /**
     * 执行可重试任务（Callable接口）
     */
    public <T> T executeCallableWithRetry(Callable<T> task, ErrorClassifier errorClassifier) throws Exception {
        return executeWithRetry(() -> {
            try {
                return task.call();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, errorClassifier);
    }
    
    /**
     * 带上下文的重试执行
     */
    public <T> T executeWithRetry(Supplier<T> task, ErrorClassifier errorClassifier, String context) {
        Exception lastException = null;
        
        for (int attempt = 1; attempt <= DEFAULT_MAX_ATTEMPTS; attempt++) {
            try {
                log.debug("[RetryableExecutor] 执行尝试 {}/{} [context: {}]", attempt, DEFAULT_MAX_ATTEMPTS, context);
                return task.get();
                
            } catch (Exception e) {
                lastException = e;
                ErrorType errorType = errorClassifier.classify(e, context);
                
                if (!errorType.isRetryable() || attempt >= DEFAULT_MAX_ATTEMPTS) {
                    log.error("[RetryableExecutor] 错误类型: {}, context: {}, 不再重试", errorType, context);
                    throw new RetryableException(errorType, e.getMessage(), e);
                }
                
                long delayMs = (long) (DEFAULT_INITIAL_DELAY_MS * Math.pow(DEFAULT_BACKOFF_MULTIPLIER, attempt - 1));
                log.warn("[RetryableExecutor] 第{}次尝试失败: {}, context: {}, {}ms后重试", 
                        attempt, errorType.getDefaultMessage(), context, delayMs);
                
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RetryableException(ErrorType.SYSTEM_ERROR, "重试被中断", ie);
                }
            }
        }
        
        throw new RetryableException(ErrorType.UNKNOWN, "未知错误", lastException);
    }
}
