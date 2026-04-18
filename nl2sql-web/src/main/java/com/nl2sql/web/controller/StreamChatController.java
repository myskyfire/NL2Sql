package com.nl2sql.web.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.nl2sql.common.result.Result;
import com.nl2sql.conversation.ConversationHistoryService;
import com.nl2sql.core.agent.ReActAgent;
import com.nl2sql.core.agent.tools.SQLExecutionTool;
import com.nl2sql.web.event.StreamProgressEvent;
import com.nl2sql.web.event.StreamProgressEventListener;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 流式对话Controller
 * 支持SSE实时推送SQL生成过程
 */
@Slf4j
@RestController
@RequestMapping("/api/chat")
public class StreamChatController {
    
    @Autowired
    private ReActAgent reActAgent;
    
    @Autowired
    private ConversationHistoryService conversationHistoryService;
    
    @Autowired
    private SQLExecutionTool sqlExecutionTool;
    
    @Autowired
    private ApplicationEventPublisher eventPublisher;
    
    private final ObjectMapper objectMapper;
    
    public StreamChatController() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }
    
    /**
     * 流式对话接口（SSE）
     * 
     * @param request 对话请求
     * @return SSE流
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamChat(@RequestBody StreamChatRequest request) {
        String sessionId = request.getSessionId();
        String question = request.getQuestion();
        Long datasourceId = request.getDatasourceId();
        
        // 创建SSE连接，超时60秒
        SseEmitter emitter = new SseEmitter(60_000L);
        
        // ✅ 注册SSE连接到事件监听器
        StreamProgressEventListener.registerEmitter(sessionId, emitter);
        
        // 异步处理
        CompletableFuture.runAsync(() -> {
            try {
                log.info("开始流式对话: sessionId={}, question={}", sessionId, question);
                
                // 1. 保存用户消息
                conversationHistoryService.saveUserMessage(sessionId, question);
                
                // 2. 获取对话历史
                String history = conversationHistoryService.formatHistoryForPrompt(sessionId, 5);
                
                // 3. 发布SQL生成中事件
                eventPublisher.publishEvent(StreamProgressEvent.creating(sessionId));
                
                // 4. 调用Agent执行查询（StandardQuerySkill已包含SQL生成+执行）
                String agentResponse = reActAgent.execute(
                    question,
                    datasourceId,
                    1L, // TODO: 从SecurityContext获取
                    "user"
                );
                
                // 5. 解析Agent响应
                Map<String, Object> responseMap = parseAgentResponse(agentResponse);
                
                // ✅ 关键修复：将 success 字段转换为 status 字段（前端需要）
                Boolean success = (Boolean) responseMap.getOrDefault("success", false);
                if (!responseMap.containsKey("status")) {
                    if (success != null && success) {
                        responseMap.put("status", "success");
                    } else if (responseMap.containsKey("needsClarification") && (Boolean) responseMap.get("needsClarification")) {
                        responseMap.put("status", "clarification_needed");
                    } else {
                        responseMap.put("status", "error");
                    }
                }
                
                String sql = (String) responseMap.get("sql");
                
                // 6. 发布SQL生成完成事件
                eventPublisher.publishEvent(StreamProgressEvent.sqlGenerated(sessionId, sql));
                
                // 7. 发布执行中事件
                eventPublisher.publishEvent(StreamProgressEvent.executing(sessionId));
                
                // 8. 发布执行结果
                String status = (String) responseMap.get("status");
                if ("success".equals(status)) {
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
                    
                    // 9. 保存AI回复
                    String summary = generateSummary(rowCount != null ? rowCount : 0, executionTime != null ? executionTime : 0.0);
                    conversationHistoryService.saveAssistantMessage(sessionId, summary, sql);
                } else if ("clarification_needed".equals(status)) {
                    String message = (String) responseMap.getOrDefault("message", "需要澄清");
                    // TODO: 发布澄清事件
                } else {
                    String error = (String) responseMap.getOrDefault("error", "未知错误");
                    log.warn("[流式对话] 查询失败: {}", error);
                }
                
                // 10. 发布完成事件
                eventPublisher.publishEvent(StreamProgressEvent.completed(sessionId));
                
                log.info("流式对话完成: sessionId={}", sessionId);
                
            } catch (Exception e) {
                log.error("流式对话失败: sessionId={}", sessionId, e);
                sendError(emitter, e.getMessage());
                emitter.completeWithError(e);
            }
        });
        
        return emitter;
    }
    
    /**
     * 获取对话历史
     */
    @GetMapping("/history/{sessionId}")
    public Result<List<ConversationHistoryService.ChatMessage>> getHistory(
            @PathVariable String sessionId,
            @RequestParam(defaultValue = "10") int limit) {
        try {
            List<ConversationHistoryService.ChatMessage> history = 
                conversationHistoryService.getRecentHistory(sessionId, limit);
            return Result.success(history);
        } catch (Exception e) {
            log.error("获取对话历史失败", e);
            return Result.error("获取失败: " + e.getMessage());
        }
    }
    
    /**
     * 清除对话历史
     */
    @DeleteMapping("/history/{sessionId}")
    public Result<String> clearHistory(@PathVariable String sessionId) {
        try {
            conversationHistoryService.clearHistory(sessionId);
            return Result.success("已清除");
        } catch (Exception e) {
            log.error("清除对话历史失败", e);
            return Result.error("清除失败: " + e.getMessage());
        }
    }
    
    /**
     * 获取会话统计
     */
    @GetMapping("/stats/{sessionId}")
    public Result<ConversationHistoryService.ConversationStats> getStats(
            @PathVariable String sessionId) {
        try {
            ConversationHistoryService.ConversationStats stats = 
                conversationHistoryService.getStats(sessionId);
            return Result.success(stats);
        } catch (Exception e) {
            log.error("获取会话统计失败", e);
            return Result.error("获取失败: " + e.getMessage());
        }
    }
    
    // ==================== 私有方法 ====================
    
    private void sendEvent(SseEmitter emitter, String eventName, Object data) {
        try {
            SseEmitter.SseEventBuilder event = SseEmitter.event()
                .name(eventName)
                .data(data);
            emitter.send(event);
        } catch (IOException e) {
            log.error("发送SSE事件失败: event={}", eventName, e);
        }
    }
    
    private void sendError(SseEmitter emitter, String errorMessage) {
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
     * 解析Agent响应
     */
    private Map<String, Object> parseAgentResponse(String response) {
        try {
            // 尝试解析JSON
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
    
    // ==================== 数据模型 ====================
    
    @Data
    public static class StreamChatRequest {
        private String sessionId;
        private String question;
        private Long datasourceId;
    }
}
