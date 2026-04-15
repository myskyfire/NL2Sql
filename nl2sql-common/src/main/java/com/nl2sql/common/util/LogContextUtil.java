package com.nl2sql.common.util;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;

import java.util.UUID;

/**
 * MDC上下文工具类
 * 用于在日志中注入追踪信息，便于ELK分析
 */
@Slf4j
public class LogContextUtil {
    
    public static final String REQUEST_ID = "requestId";
    public static final String USER_ID = "userId";
    public static final String USERNAME = "username";
    public static final String SESSION_ID = "sessionId";
    public static final String SQL_TEXT = "sqlText";
    public static final String EXECUTION_TIME = "executionTime";
    
    /**
     * 生成并设置请求ID
     */
    public static String initRequestId() {
        String requestId = UUID.randomUUID().toString().replace("-", "");
        MDC.put(REQUEST_ID, requestId);
        return requestId;
    }
    
    /**
     * 设置用户信息
     */
    public static void setUserContext(Long userId, String username) {
        if (userId != null) {
            MDC.put(USER_ID, String.valueOf(userId));
        }
        if (username != null) {
            MDC.put(USERNAME, username);
        }
    }
    
    /**
     * 设置会话ID
     */
    public static void setSessionId(String sessionId) {
        if (sessionId != null) {
            MDC.put(SESSION_ID, sessionId);
        }
    }
    
    /**
     * 设置SQL上下文
     */
    public static void setSqlContext(String sqlText, Long executionTime) {
        if (sqlText != null) {
            // 截断过长的SQL，避免日志过大
            String truncatedSql = sqlText.length() > 1000 ? 
                sqlText.substring(0, 1000) + "..." : sqlText;
            MDC.put(SQL_TEXT, truncatedSql);
        }
        if (executionTime != null) {
            MDC.put(EXECUTION_TIME, String.valueOf(executionTime));
        }
    }
    
    /**
     * 清除所有MDC上下文
     */
    public static void clear() {
        MDC.clear();
    }
    
    /**
     * 清除特定字段
     */
    public static void remove(String key) {
        MDC.remove(key);
    }
}
