package com.nl2sql.core.observability;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 执行追踪器
 * 
 * 管理所有执行追踪记录，支持异步上报和查询
 */
@Slf4j
@Component
public class ExecutionTracer {
    
    /**
     * 活跃追踪记录（traceId -> ExecutionTrace）
     */
    private final Map<String, ExecutionTrace> activeTraces = new ConcurrentHashMap<>();
    
    /**
     * 开始追踪
     */
    public ExecutionTrace startTrace(String sessionId, Long userId) {
        String traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        
        ExecutionTrace trace = ExecutionTrace.builder()
            .traceId(traceId)
            .sessionId(sessionId)
            .userId(userId)
            .startTime(LocalDateTime.now())
            .success(true)
            .build();
        
        activeTraces.put(traceId, trace);
        log.debug("[ExecutionTracer] 开始追踪: traceId={}, sessionId={}", traceId, sessionId);
        
        return trace;
    }
    
    /**
     * 结束追踪
     */
    public ExecutionTrace endTrace(String traceId, boolean success, String errorMessage) {
        ExecutionTrace trace = activeTraces.remove(traceId);
        if (trace != null) {
            trace.setEndTime(LocalDateTime.now());
            trace.calculateTotalDuration();
            trace.setSuccess(success);
            trace.setErrorMessage(errorMessage);
            
            // 记录日志
            if (success) {
                log.info("[ExecutionTracer] 追踪完成: {}", trace.generateSummary());
            } else {
                log.warn("[ExecutionTracer] 追踪失败: {}\n错误: {}", trace.generateSummary(), errorMessage);
            }
            
            // TODO: 异步上报到监控系统（如Prometheus、SkyWalking等）
            reportToMonitoring(trace);
        }
        
        return trace;
    }
    
    /**
     * 添加阶段span
     */
    public void addSpan(String traceId, String stage, long durationMs, boolean success, Map<String, Object> details) {
        ExecutionTrace trace = activeTraces.get(traceId);
        if (trace != null) {
            ExecutionTrace.TraceSpan span = success 
                ? ExecutionTrace.TraceSpan.success(stage, durationMs)
                : ExecutionTrace.TraceSpan.error(stage, durationMs, (String) details.get("errorMessage"));
            
            if (details != null) {
                span.getDetails().putAll(details);
            }
            
            trace.addSpan(span);
            log.debug("[ExecutionTracer] 添加span: traceId={}, stage={}, duration={}ms", traceId, stage, durationMs);
        }
    }
    
    /**
     * 添加元数据
     */
    public void addMetadata(String traceId, String key, Object value) {
        ExecutionTrace trace = activeTraces.get(traceId);
        if (trace != null) {
            trace.getMetadata().put(key, value);
        }
    }
    
    /**
     * 获取活跃追踪数量
     */
    public int getActiveTraceCount() {
        return activeTraces.size();
    }
    
    /**
     * 清理超时的追踪记录（防止内存泄漏）
     */
    public void cleanupTimeoutTraces(long timeoutMinutes) {
        LocalDateTime cutoffTime = LocalDateTime.now().minusMinutes(timeoutMinutes);
        
        activeTraces.entrySet().removeIf(entry -> {
            ExecutionTrace trace = entry.getValue();
            boolean isTimeout = trace.getStartTime().isBefore(cutoffTime);
            if (isTimeout) {
                log.warn("[ExecutionTracer] 清理超时追踪: traceId={}, startTime={}", 
                    entry.getKey(), trace.getStartTime());
            }
            return isTimeout;
        });
    }
    
    /**
     * 上报到监控系统（预留接口）
     */
    private void reportToMonitoring(ExecutionTrace trace) {
        // TODO: 集成Prometheus、SkyWalking等监控系统
        // 示例：
        // - 上报耗时指标到Prometheus
        // - 上报链路信息到SkyWalking
        // - 记录慢查询告警
        
        log.debug("[ExecutionTracer] 上报监控数据: traceId={}, duration={}ms", 
            trace.getTraceId(), trace.getTotalDurationMs());
    }
}
