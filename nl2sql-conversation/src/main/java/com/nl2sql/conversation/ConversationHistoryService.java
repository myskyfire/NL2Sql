package com.nl2sql.conversation;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 对话历史管理服务
 * 支持多轮对话上下文记忆
 */
@Slf4j
@Service
public class ConversationHistoryService {
    
    @Autowired
    private StringRedisTemplate redisTemplate;
    
    private static final String CONVERSATION_PREFIX = "conversation:";
    private static final long TTL_HOURS = 24; // 会话保留24小时
    private static final int MAX_HISTORY = 10; // 最多保留10轮
    
    /**
     * 保存用户消息
     */
    public void saveUserMessage(String sessionId, String message) {
        saveMessage(sessionId, "user", message);
    }
    
    /**
     * 保存AI回复
     */
    public void saveAssistantMessage(String sessionId, String message, String sql) {
        ChatMessage chatMessage = new ChatMessage("assistant", message, LocalDateTime.now());
        chatMessage.setGeneratedSql(sql);
        saveMessageObject(sessionId, chatMessage);
    }
    
    /**
     * 获取最近N轮对话历史
     */
    public List<ChatMessage> getRecentHistory(String sessionId, int limit) {
        try {
            String key = buildKey(sessionId);
            List<String> rawMessages = redisTemplate.opsForList()
                .range(key, -limit, -1);
            
            if (rawMessages == null || rawMessages.isEmpty()) {
                return new ArrayList<>();
            }
            
            List<ChatMessage> messages = new ArrayList<>();
            for (String json : rawMessages) {
                ChatMessage msg = deserialize(json);
                if (msg != null) {
                    messages.add(msg);
                }
            }
            
            log.debug("获取对话历史: sessionId={}, count={}", sessionId, messages.size());
            return messages;
            
        } catch (Exception e) {
            log.warn("获取对话历史失败: {}", e.getMessage());
            return new ArrayList<>();
        }
    }
    
    /**
     * 格式化对话历史为Prompt文本
     */
    public String formatHistoryForPrompt(String sessionId, int limit) {
        List<ChatMessage> history = getRecentHistory(sessionId, limit);
        
        if (history.isEmpty()) {
            return "";
        }
        
        StringBuilder sb = new StringBuilder();
        sb.append("【对话历史】\n");
        
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("HH:mm:ss");
        for (ChatMessage msg : history) {
            String role = "user".equals(msg.getRole()) ? "用户" : "AI";
            sb.append(String.format("%s (%s): %s\n", role, 
                msg.getTimestamp().format(formatter), msg.getContent()));
            
            if (msg.getGeneratedSql() != null && !msg.getGeneratedSql().isEmpty()) {
                sb.append("生成的SQL: ").append(msg.getGeneratedSql()).append("\n");
            }
        }
        
        sb.append("\n【当前问题】\n");
        return sb.toString();
    }
    
    /**
     * 清除会话历史
     */
    public void clearHistory(String sessionId) {
        try {
            String key = buildKey(sessionId);
            redisTemplate.delete(key);
            log.info("已清除会话历史: sessionId={}", sessionId);
        } catch (Exception e) {
            log.warn("清除会话历史失败: {}", e.getMessage());
        }
    }
    
    /**
     * 获取会话统计信息
     */
    public ConversationStats getStats(String sessionId) {
        try {
            String key = buildKey(sessionId);
            Long size = redisTemplate.opsForList().size(key);
            
            ConversationStats stats = new ConversationStats();
            stats.setSessionId(sessionId);
            stats.setMessageCount(size != null ? size.intValue() : 0);
            stats.setCreatedAt(getSessionCreateTime(sessionId));
            
            return stats;
            
        } catch (Exception e) {
            log.warn("获取会话统计失败: {}", e.getMessage());
            return new ConversationStats();
        }
    }
    
    // ==================== 私有方法 ====================
    
    private void saveMessage(String sessionId, String role, String content) {
        ChatMessage message = new ChatMessage(role, content, LocalDateTime.now());
        saveMessageObject(sessionId, message);
    }
    
    private void saveMessageObject(String sessionId, ChatMessage message) {
        try {
            String key = buildKey(sessionId);
            String json = serialize(message);
            
            redisTemplate.opsForList().rightPush(key, json);
            redisTemplate.expire(key, TTL_HOURS, TimeUnit.HOURS);
            
            // 限制历史记录数量
            trimHistory(key, MAX_HISTORY);
            
        } catch (Exception e) {
            log.error("保存对话消息失败: sessionId={}", sessionId, e);
        }
    }
    
    private void trimHistory(String key, int maxSize) {
        Long size = redisTemplate.opsForList().size(key);
        if (size != null && size > maxSize) {
            redisTemplate.opsForList().trim(key, size - maxSize, -1);
        }
    }
    
    private String buildKey(String sessionId) {
        return CONVERSATION_PREFIX + sessionId;
    }
    
    private String serialize(ChatMessage message) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                .writeValueAsString(message);
        } catch (Exception e) {
            throw new RuntimeException("序列化失败", e);
        }
    }
    
    private ChatMessage deserialize(String json) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(json, ChatMessage.class);
        } catch (Exception e) {
            log.warn("反序列化失败: {}", e.getMessage());
            return null;
        }
    }
    
    private LocalDateTime getSessionCreateTime(String sessionId) {
        // 简化实现：返回第一条消息时间
        List<ChatMessage> history = getRecentHistory(sessionId, 1);
        return history.isEmpty() ? LocalDateTime.now() : history.get(0).getTimestamp();
    }
    
    // ==================== 数据模型 ====================
    
    @Data
    public static class ChatMessage {
        private String role; // user / assistant
        private String content;
        private String generatedSql;
        private LocalDateTime timestamp;
        
        public ChatMessage() {}
        
        public ChatMessage(String role, String content, LocalDateTime timestamp) {
            this.role = role;
            this.content = content;
            this.timestamp = timestamp;
        }
    }
    
    @Data
    public static class ConversationStats {
        private String sessionId;
        private Integer messageCount;
        private LocalDateTime createdAt;
    }
}
