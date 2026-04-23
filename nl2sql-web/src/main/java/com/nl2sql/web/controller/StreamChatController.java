package com.nl2sql.web.controller;

import com.nl2sql.common.result.Result;
import com.nl2sql.conversation.ConversationHistoryService;
import com.nl2sql.web.service.StreamChatService;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 流式对话Controller
 * 
 * 职责：
 * - 接收 HTTP 请求
 * - 调用 Service 层处理业务逻辑
 * - 返回 SSE 流
 * 
 * ✅ 业务逻辑由 StreamChatService 处理
 */
@Slf4j
@RestController
@RequestMapping("/api/chat")
public class StreamChatController {
    
    @Autowired
    private StreamChatService streamChatService;
    
    @Autowired
    private ConversationHistoryService conversationHistoryService;
    
    /**
     * 流式对话接口（SSE）
     * 
     * ✅ 业务逻辑由 StreamChatService 处理
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamChat(@RequestBody StreamChatRequest request) {
        log.info("[流式对话] 收到请求: sessionId={}, question={}", 
            request.getSessionId(), request.getQuestion());
        
        return streamChatService.processStreamChat(
            request.getSessionId(),
            request.getQuestion(),
            request.getDatasourceId()
        );
    }
    
    /**
     * 获取对话历史
     */
    @GetMapping("/history/{sessionId}")
    public Result<List<Map<String, Object>>> getHistory(
            @PathVariable String sessionId,
            @RequestParam(defaultValue = "10") int limit) {
        try {
            List<Map<String, Object>> history = conversationHistoryService.getHistory(sessionId);
            return Result.success(history);
        } catch (Exception e) {
            log.error("获取对话历史失败: sessionId={}", sessionId, e);
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
     * TODO: 待实现 - 需要扩展ConversationHistoryService
     */
    @GetMapping("/stats/{sessionId}")
    public Result<Map<String, Object>> getStats(@PathVariable String sessionId) {
        // TODO: 实现统计接口
        return Result.success(new HashMap<>());
    }
    
    /**
     * 流式对话请求 DTO
     */
    @Data
    public static class StreamChatRequest {
        private String sessionId;
        private String question;
        private Long datasourceId;
    }
}
