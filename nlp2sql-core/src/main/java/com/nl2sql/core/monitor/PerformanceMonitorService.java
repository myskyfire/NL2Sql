package com.nl2sql.core.monitor;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.Map;

@Slf4j
@Service
public class PerformanceMonitorService {
    
    private final AtomicLong totalQueries = new AtomicLong(0);
    private final AtomicLong failedQueries = new AtomicLong(0);
    private final AtomicLong slowQueries = new AtomicLong(0);
    private final AtomicLong cacheHits = new AtomicLong(0);
    private final ConcurrentHashMap<String, AtomicLong> modelUsage = new ConcurrentHashMap<>();
    
    /**
     * 记录查询
     */
    public void recordQuery(boolean success, long executionTimeMs, boolean fromCache) {
        totalQueries.incrementAndGet();
        
        if (!success) {
            failedQueries.incrementAndGet();
        }
        
        if (executionTimeMs > 5000) { // 5秒为慢查询
            slowQueries.incrementAndGet();
            log.warn("慢查询检测: executionTime={}ms", executionTimeMs);
        }
        
        if (fromCache) {
            cacheHits.incrementAndGet();
        }
    }
    
    /**
     * 记录模型使用
     */
    public void recordModelUsage(String modelName) {
        modelUsage.computeIfAbsent(modelName, k -> new AtomicLong(0)).incrementAndGet();
    }
    
    /**
     * 获取监控指标
     */
    public MonitorMetrics getMetrics() {
        MonitorMetrics metrics = new MonitorMetrics();
        metrics.setTotalQueries(totalQueries.get());
        metrics.setFailedQueries(failedQueries.get());
        metrics.setSlowQueries(slowQueries.get());
        metrics.setCacheHits(cacheHits.get());
        
        long total = totalQueries.get();
        if (total > 0) {
            metrics.setSuccessRate((double)(total - failedQueries.get()) / total * 100);
            metrics.setCacheHitRate((double)cacheHits.get() / total * 100);
        }
        
        metrics.setModelUsage(modelUsage.entrySet().stream()
            .collect(java.util.stream.Collectors.toMap(
                Map.Entry::getKey,
                e -> e.getValue().get()
            )));
        
        return metrics;
    }
    
    /**
     * 重置统计
     */
    public void reset() {
        totalQueries.set(0);
        failedQueries.set(0);
        slowQueries.set(0);
        cacheHits.set(0);
        modelUsage.clear();
        log.info("监控指标已重置");
    }
    
    @Data
    public static class MonitorMetrics {
        private long totalQueries;
        private long failedQueries;
        private long slowQueries;
        private long cacheHits;
        private double successRate;
        private double cacheHitRate;
        private java.util.Map<String, Long> modelUsage;
    }
}
