package com.nl2sql.web.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nl2sql.conversation.ConversationHistoryService;
import com.nl2sql.core.agent.ReActAgent;
import com.nl2sql.core.service.NL2SQLService;
import com.nl2sql.core.service.SessionContextManager;
import com.nl2sql.common.event.StreamProgressEvent;
import com.nl2sql.web.event.StreamProgressEventListener;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 流式对话服务
 * 
 * 职责：处理 SSE 流式对话的业务逻辑
 */
@Slf4j
@Service
public class StreamChatService {
    
    private final ReActAgent reActAgent;
    private final ConversationHistoryService conversationHistoryService;
    private final NL2SQLService nl2sqlService;
    private final SessionContextManager sessionContextManager;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    public StreamChatService(
        ReActAgent reActAgent,
        ConversationHistoryService conversationHistoryService,
        NL2SQLService nl2sqlService,
        SessionContextManager sessionContextManager,
        ApplicationEventPublisher eventPublisher
    ) {
        this.reActAgent = reActAgent;
        this.conversationHistoryService = conversationHistoryService;
        this.nl2sqlService = nl2sqlService;
        this.sessionContextManager = sessionContextManager;
        this.eventPublisher = eventPublisher;
    }
    
    /**
     * 处理流式对话
     */
    public SseEmitter processStreamChat(String sessionId, String question, Long datasourceId) {
        // 创建SSE连接，超时5分钟
        SseEmitter emitter = new SseEmitter(5 * 60 * 1000L);
        
        // 注册SSE连接到事件监听器
        StreamProgressEventListener.registerEmitter(sessionId, emitter);
        
        // 设置完成/超时/错误回调
        setupEmitterCallbacks(emitter, sessionId);
        
        // 异步处理
        CompletableFuture.runAsync(() -> {
            executeStreamChat(emitter, sessionId, question, datasourceId);
        });
        
        return emitter;
    }
    
    /**
     * 设置 Emitter 回调
     */
    private void setupEmitterCallbacks(SseEmitter emitter, String sessionId) {
        emitter.onCompletion(() -> {
            StreamProgressEventListener.removeEmitter(sessionId);
            log.info("SSE连接完成: sessionId={}", sessionId);
        });
        
        emitter.onTimeout(() -> {
            StreamProgressEventListener.removeEmitter(sessionId);
            log.warn("SSE连接超时: sessionId={}", sessionId);
        });
        
        emitter.onError((ex) -> {
            StreamProgressEventListener.removeEmitter(sessionId);
            log.error("SSE连接错误: sessionId={}", sessionId, ex);
        });
    }
    
    /**
     * 执行流式对话
     */
    private void executeStreamChat(SseEmitter emitter, String sessionId, String question, Long datasourceId) {
        try {
            log.info("开始流式对话: sessionId={}, question={}", sessionId, question);
            
            // 1. ✅ TODO: 保存用户消息 - 待实现
            // conversationHistoryService.saveUserMessage(sessionId, question);
            
            // 2. ✅ TODO: 获取对话历史 - 待实现
            // String history = conversationHistoryService.formatHistoryForPrompt(sessionId, 5);
            
            // 3. 设置当前会话ID
            setCurrentSessionId(sessionId);
            
            try {
                // 4. 发布SQL生成中事件
                eventPublisher.publishEvent(StreamProgressEvent.creating(sessionId));
                
                // 5. 调用Agent执行查询
                String agentResponse = reActAgent.execute(
                    question,
                    datasourceId,
                    1L, // TODO: 从SecurityContext获取
                    "user",
                    null  // ✅ TODO: 传入历史消息
                );
                
                // 6. 解析Agent响应
                Map<String, Object> responseMap = parseAgentResponse(agentResponse);
                
                // 7. 转换 success 为 status
                normalizeResponseStatus(responseMap);
                
                String sql = (String) responseMap.get("sql");
                
                // 8. 发布SQL生成完成事件
                eventPublisher.publishEvent(StreamProgressEvent.sqlGenerated(sessionId, sql));
                
                // 9. 发布执行中事件
                eventPublisher.publishEvent(StreamProgressEvent.executing(sessionId));
                
                // 10. 处理执行结果
                handleExecutionResult(emitter, sessionId, responseMap);
                
                // 11. 发布完成事件
                eventPublisher.publishEvent(StreamProgressEvent.completed(sessionId));
                
                log.info("流式对话完成: sessionId={}", sessionId);
                
            } finally {
                clearCurrentSessionId();
            }
            
        } catch (Exception e) {
            log.error("流式对话失败: sessionId={}", sessionId, e);
            sendError(emitter, e.getMessage());
            emitter.completeWithError(e);
        }
    }
    
    /**
     * 设置当前会话ID
     */
    private void setCurrentSessionId(String sessionId) {
        if (sessionContextManager != null) {
            sessionContextManager.setCurrentSessionId(sessionId);
            log.debug("[流式对话] 已设置会话ID: {}", sessionId);
        }
    }
    
    /**
     * 清除当前会话ID
     */
    private void clearCurrentSessionId() {
        if (sessionContextManager != null) {
            sessionContextManager.clearCurrentSessionId();
        }
    }
    
    /**
     * 规范化响应状态
     */
    private void normalizeResponseStatus(Map<String, Object> responseMap) {
        if (responseMap.containsKey("status")) {
            return;
        }
        
        Boolean success = (Boolean) responseMap.getOrDefault("success", false);
        if (success != null && success) {
            responseMap.put("status", "success");
        } else if (responseMap.containsKey("needsClarification") && 
                   (Boolean) responseMap.get("needsClarification")) {
            responseMap.put("status", "clarification_needed");
        } else {
            responseMap.put("status", "error");
        }
    }
    
    /**
     * 处理执行结果
     */
    private void handleExecutionResult(
        SseEmitter emitter, 
        String sessionId, 
        Map<String, Object> responseMap
    ) {
        String status = (String) responseMap.get("status");
        
        if ("success".equals(status)) {
            handleSuccessResult(emitter, sessionId, responseMap);
        } else if ("clarification_needed".equals(status)) {
            handleClarificationResult(responseMap);
        } else {
            handleErrorResult(responseMap);
        }
    }
    
    /**
     * 处理成功结果
     */
    private void handleSuccessResult(
        SseEmitter emitter,
        String sessionId,
        Map<String, Object> responseMap
    ) {
        List<Map<String, Object>> data = (List<Map<String, Object>>) responseMap.get("data");
        Integer rowCount = (Integer) responseMap.getOrDefault("rowCount", 0);
        Double executionTime = (Double) responseMap.getOrDefault("executionTime", 0.0);
        
        // 发布查询结果事件
        eventPublisher.publishEvent(StreamProgressEvent.queryResult(
            sessionId, 
            data != null ? data : List.of(), 
            rowCount != null ? rowCount : 0,
            executionTime != null ? executionTime : 0.0
        ));
        
        // 保存AI回复 - ✅ TODO: 待实现
        String summary = generateSummary(rowCount != null ? rowCount : 0, executionTime != null ? executionTime : 0.0);
        String sql = (String) responseMap.get("sql");
        // conversationHistoryService.saveAssistantMessage(sessionId, summary, sql);
    }
    
    /**
     * 处理澄清结果
     */
    private void handleClarificationResult(Map<String, Object> responseMap) {
        String message = (String) responseMap.getOrDefault("message", "需要澄清");
        log.info("[流式对话] 需要澄清: {}", message);
        // TODO: 发布澄清事件
    }
    
    /**
     * 处理错误结果
     */
    private void handleErrorResult(Map<String, Object> responseMap) {
        String error = (String) responseMap.getOrDefault("error", "未知错误");
        log.warn("[流式对话] 查询失败: {}", error);
    }
    
    /**
     * 发送 SSE 事件
     */
    public void sendEvent(SseEmitter emitter, String eventName, Object data) {
        try {
            SseEmitter.SseEventBuilder event = SseEmitter.event()
                .name(eventName)
                .data(data);
            emitter.send(event);
        } catch (IOException e) {
            log.error("发送SSE事件失败: event={}", eventName, e);
        }
    }
    
    /**
     * 发送错误事件
     */
    public void sendError(SseEmitter emitter, String errorMessage) {
        try {
            SseEmitter.SseEventBuilder event = SseEmitter.event()
                .name("error")
                .data(Map.of("error", errorMessage));
            emitter.send(event);
        } catch (IOException e) {
            log.error("发送错误事件失败", e);
        }
    }
    
    /**
     * 解析 Agent 响应
     */
    private Map<String, Object> parseAgentResponse(String response) {
        try {
            if (response != null && response.trim().startsWith("{")) {
                return objectMapper.readValue(response, Map.class);
            }
        } catch (Exception e) {
            log.warn("解析Agent响应失败，作为文本处理", e);
        }
        
        // 非JSON响应，包装成标准格式
        return Map.of(
            "success", false,
            "error", response != null ? response : "无响应"
        );
    }
    
    /**
     * 生成查询结果摘要
     */
    private String generateSummary(int rowCount, double executionTime) {
        StringBuilder summary = new StringBuilder();
        summary.append("查询成功，返回 ").append(rowCount).append(" 条记录");
        
        if (executionTime > 0) {
            summary.append("，耗时 ").append(String.format("%.2f", executionTime)).append(" ms");
        }
        
        return summary.toString();
    }
}
