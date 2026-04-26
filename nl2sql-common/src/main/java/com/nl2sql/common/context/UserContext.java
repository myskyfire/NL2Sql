package com.nl2sql.common.context;

import com.alibaba.ttl.TransmittableThreadLocal;

/**
 * 用户会话上下文工具类
 * 基于TransmittableThreadLocal存储当前请求的用户信息，避免层层传参
 * 
 * 优势：
 * 1. 支持线程池场景下的上下文传递（普通ThreadLocal在线程池中会丢失）
 * 2. 异步任务、CompletableFuture等场景自动继承父线程上下文
 */
public class UserContext {
    
    private static final TransmittableThreadLocal<UserInfo> CONTEXT = new TransmittableThreadLocal<>();
    
    /**
     * 设置用户上下文
     */
    public static void set(UserInfo userInfo) {
        CONTEXT.set(userInfo);
    }
    
    /**
     * 获取当前用户ID
     */
    public static Long getUserId() {
        UserInfo info = CONTEXT.get();
        return info != null ? info.getUserId() : null;
    }
    
    /**
     * 获取当前用户名
     */
    public static String getUsername() {
        UserInfo info = CONTEXT.get();
        return info != null ? info.getUsername() : null;
    }
    
    /**
     * 获取当前会话ID
     */
    public static String getSessionId() {
        UserInfo info = CONTEXT.get();
        return info != null ? info.getSessionId() : null;
    }
    
    /**
     * 获取完整用户信息
     */
    public static UserInfo get() {
        return CONTEXT.get();
    }
    
    /**
     * 清除上下文（防止内存泄漏）
     */
    public static void clear() {
        CONTEXT.remove();
    }
    
    /**
     * 用户信息封装
     */
    public static class UserInfo {
        private Long userId;
        private String username;
        private String sessionId;
        
        public UserInfo(Long userId, String username, String sessionId) {
            this.userId = userId;
            this.username = username;
            this.sessionId = sessionId;
        }
        
        public Long getUserId() { return userId; }
        public String getUsername() { return username; }
        public String getSessionId() { return sessionId; }
    }
}
