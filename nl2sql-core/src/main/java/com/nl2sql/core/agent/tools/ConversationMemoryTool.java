package com.nl2sql.core.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.Tool;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * 会话记忆工具 - 管理长期对话记忆（Redis持久化）
 */
@Slf4j
@Component
public class ConversationMemoryTool {
    
    @Autowired(required = false)
    private StringRedisTemplate redisTemplate;
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    // 降级方案：内存存储
    private static final Map<String, ConversationSession> memoryStore = new HashMap<>();
    private static final long SESSION_TTL_HOURS = 24;
    
    /**
     * 保存对话到记忆
     */
    @Tool("保存用户对话到长期记忆。输入会话ID、用户消息和Agent回复")
    public String saveToMemory(String sessionId, String userMessage, String agentReply) {
        try {
            ConversationSession session = getSession(sessionId);
            
            // 添加对话记录
            session.addMessage("user", userMessage);
            session.addMessage("agent", agentReply);
            
            // 如果对话过多，自动压缩
            if (session.getMessageCount() > 20) {
                compressMemory(sessionId);
            }
            
            saveSession(sessionId, session);
            
            return "✅ 已保存到记忆，当前对话数: " + session.getMessageCount();
            
        } catch (Exception e) {
            log.error("[Memory] 保存失败", e);
            return "❌ 保存失败: " + e.getMessage();
        }
    }
    
    /**
     * 从记忆检索相关对话
     */
    @Tool("从长期记忆中检索与当前问题相关的历史对话。输入会话ID和当前问题")
    public String retrieveFromMemory(String sessionId, String currentQuery) {
        try {
            ConversationSession session = getSession(sessionId);
            
            if (session.getMessages().isEmpty()) {
                return "暂无历史对话";
            }
            
            // 简单策略：返回最近5轮对话
            List<Map<String, String>> recentMessages = session.getRecentMessages(10);
            
            StringBuilder result = new StringBuilder();
            result.append("📚 相关历史对话:\n\n");
            
            for (Map<String, String> msg : recentMessages) {
                result.append(msg.get("role")).append(": ").append(msg.get("content")).append("\n");
            }
            
            return result.toString();
            
        } catch (Exception e) {
            log.error("[Memory] 检索失败", e);
            return "❌ 检索失败: " + e.getMessage();
        }
    }
    
    /**
     * 清除会话记忆
     */
    @Tool("清除指定会话的所有记忆。输入会话ID")
    public String clearMemory(String sessionId) {
        try {
            if (redisTemplate != null) {
                redisTemplate.delete("agent:memory:" + sessionId);
            } else {
                memoryStore.remove(sessionId);
            }
            
            return "✅ 已清除会话 " + sessionId + " 的记忆";
            
        } catch (Exception e) {
            log.error("[Memory] 清除失败", e);
            return "❌ 清除失败: " + e.getMessage();
        }
    }
    
    /**
     * 压缩记忆（保留关键信息）
     */
    @Tool("压缩会话记忆，只保留关键信息。输入会话ID")
    public String compressMemory(String sessionId) {
        try {
            ConversationSession session = getSession(sessionId);
            
            // 策略：保留第1轮和最后5轮
            List<Map<String, String>> allMessages = session.getMessages();
            List<Map<String, String>> compressed = new ArrayList<>();
            
            if (allMessages.size() > 6) {
                compressed.add(allMessages.get(0));  // 保留第一轮
                compressed.addAll(allMessages.subList(allMessages.size() - 5, allMessages.size()));
            } else {
                compressed = allMessages;
            }
            
            session.setMessages(compressed);
            saveSession(sessionId, session);
            
            return "✅ 记忆已压缩，从 " + allMessages.size() + " 条减少到 " + compressed.size() + " 条";
            
        } catch (Exception e) {
            log.error("[Memory] 压缩失败", e);
            return "❌ 压缩失败: " + e.getMessage();
        }
    }
    
    /**
     * 获取会话
     */
    private ConversationSession getSession(String sessionId) {
        // 尝试从 Redis 读取
        if (redisTemplate != null) {
            try {
                String key = "agent:memory:" + sessionId;
                String json = redisTemplate.opsForValue().get(key);
                if (json != null) {
                    return parseSession(json);
                }
            } catch (Exception e) {
                log.warn("[Memory] Redis读取失败，降级到内存", e);
            }
        }
        
        // 降级到内存
        return memoryStore.computeIfAbsent(sessionId, k -> new ConversationSession());
    }
    
    /**
     * 保存会话
     */
    private void saveSession(String sessionId, ConversationSession session) {
        if (redisTemplate != null) {
            try {
                String key = "agent:memory:" + sessionId;
                String json = serializeSession(session);
                redisTemplate.opsForValue().set(key, json, SESSION_TTL_HOURS, TimeUnit.HOURS);
            } catch (Exception e) {
                log.warn("[Memory] Redis写入失败，降级到内存", e);
                memoryStore.put(sessionId, session);
            }
        } else {
            memoryStore.put(sessionId, session);
        }
    }
    
    /**
     * 序列化会话为JSON
     */
    private String serializeSession(ConversationSession session) {
        try {
            return objectMapper.writeValueAsString(session);
        } catch (Exception e) {
            log.error("[Memory] 序列化失败", e);
            throw new RuntimeException("序列化失败", e);
        }
    }
    
    /**
     * 从JSON反序列化为会话
     */
    private ConversationSession parseSession(String json) {
        try {
            return objectMapper.readValue(json, ConversationSession.class);
        } catch (Exception e) {
            log.error("[Memory] 反序列化失败: {}", json, e);
            throw new RuntimeException("反序列化失败", e);
        }
    }
    
    /**
     * 会话数据结构
     */
    @Data
    static class ConversationSession {
        private List<Map<String, String>> messages = new ArrayList<>();
        private Long createdAt = System.currentTimeMillis();
        private Long lastAccessedAt = System.currentTimeMillis();
        
        public void addMessage(String role, String content) {
            Map<String, String> msg = new HashMap<>();
            msg.put("role", role);
            msg.put("content", content);
            msg.put("timestamp", String.valueOf(System.currentTimeMillis()));
            messages.add(msg);
            lastAccessedAt = System.currentTimeMillis();
        }
        
        public int getMessageCount() {
            return messages.size();
        }
        
        public List<Map<String, String>> getRecentMessages(int count) {
            int start = Math.max(0, messages.size() - count);
            return messages.subList(start, messages.size());
        }
    }
}
