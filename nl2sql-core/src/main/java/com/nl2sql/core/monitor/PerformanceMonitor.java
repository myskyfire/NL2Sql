package com.nl2sql.core.monitor;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 性能监控服务 - 记录各 Tool 的耗时和成功率
 * 
 * 监控指标：
 * 1. 各 Tool 调用次数、成功次数、失败次数
 * 2. 各 Tool 平均耗时、P95 耗时
 * 3. SQL 生成成功率
 * 4. 缓存命中率
 */
@Slf4j
@Component
public class PerformanceMonitor {
    
    /**
     * Tool 执行统计
     */
    private final Map<String, ToolMetrics> toolMetrics = new ConcurrentHashMap<>();
    
    /**
     * SQL 生成统计
     */
    private final AtomicLong sqlGenerationCount = new AtomicLong(0);
    private final AtomicLong sqlGenerationSuccessCount = new AtomicLong(0);
    private final AtomicLong sqlGenerationFailedCount = new AtomicLong(0);
    
    @PostConstruct
    public void init() {
        log.info("[PerformanceMonitor] 监控服务初始化完成");
    }
    
    // ==================== Tool 监控 ====================
    
    /**
     * 记录 Tool 调用开始
     */
    public long recordToolStart(String toolName) {
        return System.currentTimeMillis();
    }
    
    /**
     * 记录 Tool 调用结束
     */
    public void recordToolEnd(String toolName, long startTime, boolean success) {
        long duration = System.currentTimeMillis() - startTime;
        
        ToolMetrics metrics = toolMetrics.computeIfAbsent(toolName, k -> new ToolMetrics());
        metrics.incrementCall();
        metrics.addDuration(duration);
        
        if (success) {
            metrics.incrementSuccess();
        } else {
            metrics.incrementFailure();
        }
        
        // 每100次调用输出一次统计
        if (metrics.getCallCount().get() % 100 == 0) {
            log.info("[PerformanceMonitor] {} 统计: {}", toolName, metrics.getSummary());
        }
    }
    
    /**
     * 获取所有 Tool 的统计信息
     */
    public Map<String, ToolMetrics> getAllToolMetrics() {
        return new ConcurrentHashMap<>(toolMetrics);
    }
    
    // ==================== SQL 生成监控 ====================
    
    /**
     * 记录 SQL 生成
     */
    public void recordSQLGeneration(boolean success) {
        sqlGenerationCount.incrementAndGet();
        if (success) {
            sqlGenerationSuccessCount.incrementAndGet();
        } else {
            sqlGenerationFailedCount.incrementAndGet();
        }
    }
    
    /**
     * 获取 SQL 生成成功率
     */
    public double getSQLGenerationSuccessRate() {
        long total = sqlGenerationCount.get();
        if (total == 0) {
            return 0.0;
        }
        return (double) sqlGenerationSuccessCount.get() / total * 100;
    }
    
    /**
     * 获取 SQL 生成统计
     */
    public String getSQLGenerationStats() {
        return String.format("SQL生成统计: 总数=%d, 成功=%d, 失败=%d, 成功率=%.2f%%",
            sqlGenerationCount.get(),
            sqlGenerationSuccessCount.get(),
            sqlGenerationFailedCount.get(),
            getSQLGenerationSuccessRate()
        );
    }
    
    // ==================== 报告 ====================
    
    /**
     * 生成性能报告
     */
    public String generateReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== 性能监控报告 ===\n\n");
        
        sb.append("【SQL 生成】\n");
        sb.append(getSQLGenerationStats()).append("\n\n");
        
        sb.append("【Tool 执行统计】\n");
        for (Map.Entry<String, ToolMetrics> entry : toolMetrics.entrySet()) {
            sb.append(String.format("%s: %s\n", entry.getKey(), entry.getValue().getSummary()));
        }
        
        return sb.toString();
    }
    
    /**
     * 重置所有统计
     */
    public void reset() {
        toolMetrics.clear();
        sqlGenerationCount.set(0);
        sqlGenerationSuccessCount.set(0);
        sqlGenerationFailedCount.set(0);
        log.info("[PerformanceMonitor] 统计已重置");
    }
    
    // ==================== 内部类 ====================
    
    @Data
    public static class ToolMetrics {
        private AtomicLong callCount = new AtomicLong(0);
        private AtomicLong successCount = new AtomicLong(0);
        private AtomicLong failureCount = new AtomicLong(0);
        private AtomicLong totalDuration = new AtomicLong(0);
        private AtomicLong maxDuration = new AtomicLong(0);
        
        public void incrementCall() {
            callCount.incrementAndGet();
        }
        
        public void incrementSuccess() {
            successCount.incrementAndGet();
        }
        
        public void incrementFailure() {
            failureCount.incrementAndGet();
        }
        
        public void addDuration(long duration) {
            totalDuration.addAndGet(duration);
            
            // 更新最大值
            long currentMax = maxDuration.get();
            while (duration > currentMax) {
                if (maxDuration.compareAndSet(currentMax, duration)) {
                    break;
                }
                currentMax = maxDuration.get();
            }
        }
        
        public double getAverageDuration() {
            long calls = callCount.get();
            return calls > 0 ? (double) totalDuration.get() / calls : 0.0;
        }
        
        public double getSuccessRate() {
            long calls = callCount.get();
            return calls > 0 ? (double) successCount.get() / calls * 100 : 0.0;
        }
        
        public String getSummary() {
            return String.format(
                "调用=%d, 成功=%d, 失败=%d, 成功率=%.2f%%, 平均耗时=%dms, 最大耗时=%dms",
                callCount.get(),
                successCount.get(),
                failureCount.get(),
                getSuccessRate(),
                (long) getAverageDuration(),
                maxDuration.get()
            );
        }
    }
}
