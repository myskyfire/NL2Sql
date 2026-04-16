package com.nl2sql.web.controller;

import com.nl2sql.common.result.Result;
import com.nl2sql.conversation.ConversationHistoryService;
import com.nl2sql.core.agent.ReActAgent;
import com.nl2sql.core.agent.tools.SQLExecutionTool;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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
        
        // 异步处理
        CompletableFuture.runAsync(() -> {
            try {
                log.info("开始流式对话: sessionId={}, question={}", sessionId, question);
                
                // 1. 保存用户消息
                conversationHistoryService.saveUserMessage(sessionId, question);
                
                // 2. 获取对话历史
                String history = conversationHistoryService.formatHistoryForPrompt(sessionId, 5);
                
                // 3. 发送开始事件
                sendEvent(emitter, "start", Map.of(
                    "sessionId", sessionId,
                    "message", "开始生成SQL..."
                ));
                
                // 4. 调用Agent生成SQL（模拟流式输出）
                String sql = reActAgent.execute(
                    question,
                    datasourceId,
                    1L, // TODO: 从SecurityContext获取
                    "user"
                );
                
                // 5. 发送SQL生成完成事件
                sendEvent(emitter, "sql_generated", Map.of(
                    "sql", sql,
                    "message", "SQL生成完成"
                ));
                
                // 6. 执行SQL
                sendEvent(emitter, "executing", Map.of(
                    "message", "正在执行SQL..."
                ));
                
                // 调用SQLExecutionTool执行SQL
                SQLExecutionTool.ExecutionResult executionResult = sqlExecutionTool.executeSQL(
                    sql, 
                    datasourceId,
                    1L, // TODO: 从SecurityContext获取
                    "user"
                );
                
                // 7. 发送执行结果
                if (executionResult.success) {
                    sendEvent(emitter, "result", Map.of(
                        "success", true,
                        "rowCount", executionResult.rowCount,
                        "data", executionResult.data,
                        "executionTime", executionResult.executionTime,
                        "summary", generateSummary(executionResult)
                    ));
                    
                    // 8. 保存AI回复
                    String summary = generateSummary(executionResult);
                    conversationHistoryService.saveAssistantMessage(sessionId, summary, sql);
                } else {
                    sendEvent(emitter, "error", Map.of(
                        "success", false,
                        "error", executionResult.error
                    ));
                    log.warn("[流式对话] SQL执行失败: {}", executionResult.error);
                }
                
                // 9. 发送完成事件
                sendEvent(emitter, "complete", Map.of(
                    "message", "查询完成"
                ));
                
                // 10. 关闭连接
                emitter.complete();
                
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
     * 生成查询结果摘要
     */
    private String generateSummary(SQLExecutionTool.ExecutionResult result) {
        if (!result.success) {
            return "查询执行失败: " + result.error;
        }
        
        StringBuilder summary = new StringBuilder();
        summary.append("查询成功，返回 ").append(result.rowCount).append(" 条记录");
        
        if (result.executionTime != null) {
            summary.append("，耗时 ").append(String.format("%.2f", result.executionTime)).append(" ms");
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
