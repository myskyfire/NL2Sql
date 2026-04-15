package com.nl2sql.conversation;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class ConversationService {
    
    private final RedisTemplate<String, Object> redisTemplate;
    private static final int MAX_HISTORY = 10;
    private static final long TTL_HOURS = 24;
    
    public ConversationService(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }
    
    @Data
    public static class Message {
        private String role;
        private String content;
        
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        private LocalDateTime timestamp;
        
        public Message() {
            this.timestamp = LocalDateTime.now();
        }
        
        public Message(String role, String content) {
            this.role = role;
            this.content = content;
            this.timestamp = LocalDateTime.now();
        }
    }
    
    @Data
    public static class ConversationContext {
        private List<Message> messages;
        private Map<String, Object> slots;
        private List<String> contextTables;
        private List<String> contextColumns;
        
        public ConversationContext() {
            this.messages = new ArrayList<>();
            this.slots = new HashMap<>();
            this.contextTables = new ArrayList<>();
            this.contextColumns = new ArrayList<>();
        }
    }
    
    public void addMessage(String sessionId, Message message) {
        String key = "conversation:" + sessionId;
        
        redisTemplate.opsForList().rightPush(key, message);
        redisTemplate.opsForList().trim(key, -MAX_HISTORY, -1);
        redisTemplate.expire(key, TTL_HOURS, TimeUnit.HOURS);
        
        log.debug("添加消息到会话 {}: {}", sessionId, message.getContent());
    }
    
    public List<Message> getHistory(String sessionId) {
        String key = "conversation:" + sessionId;
        List<Object> messages = redisTemplate.opsForList().range(key, 0, -1);
        
        if (messages == null || messages.isEmpty()) {
            return Collections.emptyList();
        }
        
        List<Message> result = new ArrayList<>();
        for (Object msg : messages) {
            if (msg instanceof Message) {
                result.add((Message) msg);
            }
        }
        
        return result;
    }
    
    /**
     * 获取对话历史字符串格式
     */
    public String getHistoryAsString(String sessionId, int maxRounds) {
        List<Message> history = getHistory(sessionId);
        
        if (history.isEmpty()) {
            return "";
        }
        
        StringBuilder sb = new StringBuilder();
        int count = 0;
        int startIdx = Math.max(0, history.size() - maxRounds * 2);
        
        for (int i = startIdx; i < history.size(); i++) {
            Message msg = history.get(i);
            sb.append(msg.getRole()).append(": ").append(msg.getContent()).append("\n");
            count++;
        }
        
        return sb.toString();
    }
    
    public void clearConversation(String sessionId) {
        String key = "conversation:" + sessionId;
        redisTemplate.delete(key);
        log.info("清空会话: {}", sessionId);
    }
    
    public String buildContextPrompt(String sessionId, String currentQuery) {
        List<Message> history = getHistory(sessionId);
        
        if (history.isEmpty()) {
            return currentQuery;
        }
        
        StringBuilder context = new StringBuilder();
        context.append("历史对话:\n");
        
        int count = 0;
        for (Message msg : history) {
            if (count >= 5) break;
            context.append(msg.getRole()).append(": ").append(msg.getContent()).append("\n");
            count++;
        }
        
        context.append("\n当前问题: ").append(currentQuery);
        context.append("\n\n请基于历史对话理解当前问题，如果当前问题是追问，请结合上下文生成完整的SQL查询。");
        
        return context.toString();
    }
    
    public void saveContext(String sessionId, List<String> tables, List<String> columns) {
        String key = "context:" + sessionId;
        
        Map<String, Object> context = new HashMap<>();
        context.put("tables", tables);
        context.put("columns", columns);
        
        redisTemplate.opsForHash().putAll(key, context);
        redisTemplate.expire(key, TTL_HOURS, TimeUnit.HOURS);
    }
    
    public Map<String, Object> getContext(String sessionId) {
        String key = "context:" + sessionId;
        Map<Object, Object> entries = redisTemplate.opsForHash().entries(key);
        
        Map<String, Object> result = new HashMap<>();
        for (Map.Entry<Object, Object> entry : entries.entrySet()) {
            result.put(entry.getKey().toString(), entry.getValue());
        }
        
        return result;
    }
}
