package com.nl2sql.core.service;

import com.nl2sql.common.event.StreamProgressEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * 会话上下文管理服务
 * 
 * 职责：
 * 1. 管理ThreadLocal会话ID
 * 2. 存储会话级别的SQL和Query（ConcurrentHashMap）
 * 3. 发布流式进度事件
 */
@Slf4j
@Service
public class SessionContextManager {
    
    @Autowired(required = false)
    private ApplicationEventPublisher eventPublisher;
    
    // ThreadLocal 存储当前会话ID
    private static final ThreadLocal<String> CURRENT_SESSION_ID = new ThreadLocal<>();
    
    // ✅ ConcurrentHashMap 存储每个会话的 SQL（支持跨线程访问）
    private static final java.util.concurrent.ConcurrentHashMap<String, String> SESSION_SQL_MAP = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ConcurrentHashMap<String, String> SESSION_QUERY_MAP = new java.util.concurrent.ConcurrentHashMap<>();
    
    /**
     * 设置当前会话ID（由调用方设置）
     */
    public void setCurrentSessionId(String sessionId) {
        CURRENT_SESSION_ID.set(sessionId);
    }
    
    /**
     * 获取当前会话ID
     */
    public String getCurrentSessionId() {
        return CURRENT_SESSION_ID.get();
    }
    
    /**
     * 清除当前会话ID
     */
    public void clearCurrentSessionId() {
        String sessionId = CURRENT_SESSION_ID.get();
        CURRENT_SESSION_ID.remove();
        // ✅ 关键修复：不清除 SQL 和 Query，保留用于后续的 AI 总结/图表生成
        if (sessionId != null) {
            log.debug("[SessionContext] 已清除 sessionId: {}", sessionId);
        }
    }
    
    /**
     * ✅ 手动清除所有上下文（仅在需要时调用）
     */
    public void clearAllContext() {
        String sessionId = CURRENT_SESSION_ID.get();
        CURRENT_SESSION_ID.remove();
        if (sessionId != null) {
            SESSION_SQL_MAP.remove(sessionId);
            SESSION_QUERY_MAP.remove(sessionId);
            log.debug("[SessionContext] 已清除所有上下文: sessionId={}", sessionId);
        }
    }
    
    /**
     * ✅ 保存当前 SQL 和查询问题（用于 AI 总结/图表生成）
     */
    public void saveCurrentContext(String sql, String query) {
        String sessionId = CURRENT_SESSION_ID.get();
        if (sessionId != null && !sessionId.trim().isEmpty()) {
            SESSION_SQL_MAP.put(sessionId, sql);
            SESSION_QUERY_MAP.put(sessionId, query);
            log.debug("[SessionContext] 已保存上下文: sessionId={}, sql={}, query={}", sessionId, sql, query);
        } else {
            log.warn("[SessionContext] sessionId 为空，无法保存上下文");
        }
    }
    
    /**
     * ✅ 获取当前 SQL
     */
    public String getCurrentSQL() {
        String sessionId = CURRENT_SESSION_ID.get();
        if (sessionId != null) {
            return SESSION_SQL_MAP.get(sessionId);
        }
        return null;
    }
    
    /**
     * ✅ 获取当前查询问题
     */
    public String getCurrentQuery() {
        String sessionId = CURRENT_SESSION_ID.get();
        if (sessionId != null) {
            return SESSION_QUERY_MAP.get(sessionId);
        }
        return null;
    }
    
    /**
     * 发布进度事件
     */
    public void publishProgress(Object source, String step, String message) {
        if (eventPublisher != null) {
            String sessionId = CURRENT_SESSION_ID.get();
            if (sessionId != null) {
                try {
                    eventPublisher.publishEvent(new StreamProgressEvent(
                        source, sessionId, step, message, null
                    ));
                    log.debug("[StreamProgress] 发布事件: step={}, message={}", step, message);
                } catch (Exception e) {
                    log.warn("[StreamProgress] 发布事件失败: {}", e.getMessage());
                }
            }
        }
    }
}
