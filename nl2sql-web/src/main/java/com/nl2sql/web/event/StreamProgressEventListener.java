package com.nl2sql.web.event;

import com.nl2sql.common.event.StreamProgressEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 流式对话事件监听器
 * 负责将Spring Event转换为SSE推送
 */
@Slf4j
@Component
public class StreamProgressEventListener {
    
    // 会话ID -> SseEmitter 映射
    private static final Map<String, SseEmitter> EMITTER_MAP = new ConcurrentHashMap<>();
    
    /**
     * 注册SSE连接
     */
    public static void registerEmitter(String sessionId, SseEmitter emitter) {
        EMITTER_MAP.put(sessionId, emitter);
        log.info("注册SSE连接: sessionId={}", sessionId);
    }
    
    /**
     * 移除SSE连接
     */
    public static void removeEmitter(String sessionId) {
        EMITTER_MAP.remove(sessionId);
        log.info("移除SSE连接: sessionId={}", sessionId);
    }
    
    /**
     * 监听流式进度事件
     */
    @EventListener
    public void handleStreamProgress(StreamProgressEvent event) {
        String sessionId = event.getSessionId();
        SseEmitter emitter = EMITTER_MAP.get(sessionId);
        
        if (emitter == null) {
            log.warn("未找到SSE连接: sessionId={}", sessionId);
            return;
        }
        
        try {
            // 根据事件步骤发送不同类型的事件
            switch (event.getStep()) {
                case "generating_sql":
                case "generating_final_sql":
                case "validating_sql":
                case "validation_completed":
                case "sql_generated":
                case "executing":
                case "summarizing":
                case "generating_chart":
                    // 进度事件统一使用 progress 类型
                    sendProgress(emitter, event);
                    break;
                    
                case "query_result":
                case "summary_result":
                case "chart_result":
                    // 结果事件
                    sendResult(emitter, event);
                    break;
                    
                case "complete":
                    // 完成事件
                    sendComplete(emitter, event);
                    break;
                    
                default:
                    log.warn("未知事件步骤: step={}", event.getStep());
            }
        } catch (Exception e) {
            log.error("发送SSE事件失败: sessionId={}, step={}", sessionId, event.getStep(), e);
            removeEmitter(sessionId);
        }
    }
    
    private void sendProgress(SseEmitter emitter, StreamProgressEvent event) throws IOException {
        emitter.send(SseEmitter.event()
            .name("progress")
            .data(Map.of(
                "step", event.getStep(),
                "message", event.getMessage(),
                "sql", event.getSql() != null ? event.getSql() : ""
            )));
    }
    
    private void sendResult(SseEmitter emitter, StreamProgressEvent event) throws IOException {
        emitter.send(SseEmitter.event()
            .name("result")
            .data(Map.of(
                "status", "success",
                "message", event.getMessage(),
                "data", event.getData() != null ? event.getData() : Map.of(),
                "rowCount", event.getRowCount() != null ? event.getRowCount() : 0,
                "executionTime", event.getExecutionTime() != null ? event.getExecutionTime() : 0.0
            )));
    }
    
    private void sendComplete(SseEmitter emitter, StreamProgressEvent event) throws IOException {
        emitter.send(SseEmitter.event()
            .name("complete")
            .data(Map.of("message", event.getMessage())));
        
        // ✅ 不再手动complete，由Controller的onCompletion回调处理
        log.info("发送完成事件: sessionId={}", event.getSessionId());
    }
}
