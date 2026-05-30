package com.nl2sql.conversation;

import com.nl2sql.conversation.mapper.ConversationHistoryMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 对话历史服务
 * 
 * 职责：管理多轮对话的历史记录，支持上下文加载和保存
 */
@Slf4j
@Service
public class ConversationHistoryService {
    
    @Autowired(required = false)
    private com.nl2sql.conversation.mapper.ConversationMapper conversationMapper;
    
    @Autowired(required = false)
    private ConversationHistoryMapper historyMapper;
    
    private static final int MAX_HISTORY_ROUNDS = 5; // 最多保留5轮对话
    private static final int MAX_MESSAGES_PER_ROUND = 4; // 每轮最多4条消息(user/assistant/tool_calls/tool_result)
    
    /**
     * 获取会话历史(最多MAX_HISTORY_ROUNDS轮)
     * 
     * @param sessionId 会话ID
     * @return 历史消息列表
     */
    public List<Map<String, Object>> getHistory(String sessionId) {
        try {
            // 查询最近N轮对话的消息
            List<Map<String, Object>> messages = historyMapper.selectMessagesBySessionId(
                sessionId,
                MAX_HISTORY_ROUNDS * MAX_MESSAGES_PER_ROUND
            );
            
            // ✅ 过滤掉clarify_datasource相关的tool消息
            List<Map<String, Object>> filtered = filterClarifyTools(messages);
            
            log.info("[ConversationHistory] 加载历史: sessionId={}, 原始={}, 过滤后={}", 
                sessionId, messages.size(), filtered.size());
            
            return filtered;
            
        } catch (Exception e) {
            log.error("[ConversationHistory] 加载历史失败: sessionId={}", sessionId, e);
            return Collections.emptyList();
        }
    }
    
    /**
     * 保存本轮对话消息
     * 
     * @param sessionId 会话ID
     * @param userId 用户ID
     * @param messages 消息列表
     */
    public void saveHistory(String sessionId, Long userId, List<Map<String, Object>> messages) {
        
        if (messages == null || messages.isEmpty()) {
            return;
        }
        
        try {
            String insertSql = "INSERT INTO conversation_history " +
                              "(session_id, user_id, role, content, name, tool_call_id) " +
                              "VALUES (?, ?, ?, ?, ?, ?)";
            
            for (Map<String, Object> msg : messages) {
                String role = (String) msg.get("role");
                String content = (String) msg.get("content");
                String name = (String) msg.get("name");
                String toolCallId = (String) msg.get("tool_call_id");
                
                // ✅ 跳过clarify_datasource的tool消息
                if ("tool".equals(role) && "clarify_datasource".equals(name)) {
                    log.debug("[ConversationHistory] 跳过clarify_datasource工具消息");
                    continue;
                }
                
                // ✅ 跳过包含clarify_datasource tool_calls的assistant消息
                if ("assistant".equals(role) && content != null && content.contains("clarify_datasource")) {
                    log.debug("[ConversationHistory] 跳过包含clarify_datasource的assistant消息");
                    continue;
                }
                
                conversationMapper.insertMessage(sessionId, userId, role, content, name, toolCallId);
            }
            
            log.info("[ConversationHistory] 保存历史: sessionId={}, 消息数={}", 
                sessionId, messages.size());
            
        } catch (Exception e) {
            log.error("[ConversationHistory] 保存历史失败: sessionId={}", sessionId, e);
        }
    }
    
    /**
     * 过滤掉clarify_datasource相关的工具消息
     * 
     * @param messages 原始消息列表
     * @return 过滤后的消息列表
     */
    private List<Map<String, Object>> filterClarifyTools(List<Map<String, Object>> messages) {
        List<Map<String, Object>> filtered = new ArrayList<>();
        
        boolean skipNextToolResult = false;
        
        for (Map<String, Object> msg : messages) {
            String role = (String) msg.get("role");
            String name = (String) msg.get("name");
            
            // 跳过clarify_datasource的tool消息
            if ("tool".equals(role) && "clarify_datasource".equals(name)) {
                skipNextToolResult = false; // tool消息本身不添加到结果
                continue;
            }
            
            // 跳过assistant消息中包含clarify_datasource tool_calls的
            if ("assistant".equals(role)) {
                String content = (String) msg.get("content");
                if (content != null && content.contains("clarify_datasource")) {
                    skipNextToolResult = true;
                    continue;
                }
            }
            
            filtered.add(msg);
        }
        
        return filtered;
    }
    
    /**
     * 清除会话历史
     * 
     * @param sessionId 会话ID
     */
    public void clearHistory(String sessionId) {
        
        try {
            conversationMapper.clearHistory(sessionId);
            log.info("[ConversationHistory] 清除历史: sessionId={}", sessionId);
        } catch (Exception e) {
            log.error("[ConversationHistory] 清除历史失败: sessionId={}", sessionId, e);
        }
    }
}
