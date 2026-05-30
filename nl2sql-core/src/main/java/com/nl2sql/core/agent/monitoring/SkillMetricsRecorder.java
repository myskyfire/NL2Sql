package com.nl2sql.core.agent.monitoring;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Skill 执行指标记录器
 * 
 * 功能：
 * - 记录每个 Skill 的调用次数、成功率、失败率
 * - 记录平均执行时间和最大执行时间
 * - 提供查询接口供 Dashboard 使用
 */
@Slf4j
@Component
public class SkillMetricsRecorder {
    
    /**
     * Skill 执行指标
     */
    @Data
    public static class SkillMetrics {
        private String skillName;
        private AtomicLong totalCalls = new AtomicLong(0);
        private AtomicLong successCalls = new AtomicLong(0);
        private AtomicLong failedCalls = new AtomicLong(0);
        private AtomicLong totalExecutionTimeMs = new AtomicLong(0);
        private AtomicLong maxExecutionTimeMs = new AtomicLong(0);
        
        /**
         * 记录成功执行
         */
        public void recordSuccess(long executionTimeMs) {
            totalCalls.incrementAndGet();
            successCalls.incrementAndGet();
            totalExecutionTimeMs.addAndGet(executionTimeMs);
            maxExecutionTimeMs.updateAndGet(current -> Math.max(current, executionTimeMs));
        }
        
        /**
         * 记录失败执行
         */
        public void recordFailure(long executionTimeMs) {
            totalCalls.incrementAndGet();
            failedCalls.incrementAndGet();
            totalExecutionTimeMs.addAndGet(executionTimeMs);
        }
        
        /**
         * 获取平均执行时间（毫秒）
         */
        public double getAverageExecutionTimeMs() {
            long total = totalCalls.get();
            return total > 0 ? (double) totalExecutionTimeMs.get() / total : 0.0;
        }
        
        /**
         * 获取成功率（0-1）
         */
        public double getSuccessRate() {
            long total = totalCalls.get();
            return total > 0 ? (double) successCalls.get() / total : 0.0;
        }
    }
    
    /**
     * 存储所有 Skill 的指标
     * Key: Skill 名称
     * Value: 指标数据
     */
    private final ConcurrentHashMap<String, SkillMetrics> metricsMap = new ConcurrentHashMap<>();
    
    /**
     * 记录 Skill 执行开始
     */
    public void recordStart(String skillName) {
        metricsMap.computeIfAbsent(skillName, k -> new SkillMetrics());
    }
    
    /**
     * 记录 Skill 执行成功
     * 
     * @param skillName Skill 名称
     * @param executionTimeMs 执行时间（毫秒）
     */
    public void recordSuccess(String skillName, long executionTimeMs) {
        SkillMetrics metrics = metricsMap.computeIfAbsent(skillName, k -> new SkillMetrics());
        metrics.recordSuccess(executionTimeMs);
        
        log.debug("[SkillMetrics] Skill={}, status=SUCCESS, time={}ms", 
            skillName, executionTimeMs);
    }
    
    /**
     * 记录 Skill 执行失败
     * 
     * @param skillName Skill 名称
     * @param executionTimeMs 执行时间（毫秒）
     * @param error 错误信息
     */
    public void recordFailure(String skillName, long executionTimeMs, String error) {
        SkillMetrics metrics = metricsMap.computeIfAbsent(skillName, k -> new SkillMetrics());
        metrics.recordFailure(executionTimeMs);
        
        log.warn("[SkillMetrics] Skill={}, status=FAILURE, time={}ms, error={}", 
            skillName, executionTimeMs, error);
    }
    
    /**
     * 获取所有指标
     * 
     * @return Skill 名称 → 指标数据的映射
     */
    public Map<String, SkillMetrics> getAllMetrics() {
        return new ConcurrentHashMap<>(metricsMap);
    }
    
    /**
     * 获取指定 Skill 的指标
     * 
     * @param skillName Skill 名称
     * @return 指标数据，如果不存在返回 null
     */
    public SkillMetrics getMetrics(String skillName) {
        return metricsMap.get(skillName);
    }
    
    /**
     * 重置所有指标
     */
    public void resetAll() {
        metricsMap.clear();
        log.info("[SkillMetrics] 所有指标已重置");
    }
}
