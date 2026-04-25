package com.nl2sql.core.service;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

/**
 * 监控数据上下文 - ThreadLocal传递监控信息
 * 
 * 用于在表选择、SQL生成等阶段收集监控数据,最终由AgentChatService写入数据库
 */
@Slf4j
public class MonitoringContext {
    
    private static final ThreadLocal<MonitoringData> context = new ThreadLocal<>();
    
    @Data
    public static class MonitoringData {
        // 归一化相关
        private String normalizedQuery;
        private Boolean hasPersonEntity = false;
        private Boolean hasLocationEntity = false;
        private String normalizationMethod; // regex/hanlp/mixed
        
        // 缓存相关
        private String cacheLevel; // L1/L2/L3/MISS
        private Boolean cacheHit = false;
        
        // RAG相关
        private Integer ragExamplesCount = 0;
        private String industryTermsMatched; // JSON数组字符串
    }
    
    /**
     * 初始化监控上下文
     */
    public static void init() {
        context.set(new MonitoringData());
        log.debug("[MonitoringContext] 初始化监控上下文");
    }
    
    /**
     * 获取当前监控数据
     */
    public static MonitoringData get() {
        MonitoringData data = context.get();
        if (data == null) {
            init();
            data = context.get();
        }
        return data;
    }
    
    /**
     * 清除监控上下文(防止内存泄漏)
     */
    public static void clear() {
        context.remove();
        log.debug("[MonitoringContext] 清除监控上下文");
    }
    
    /**
     * 设置归一化信息
     */
    public static void setNormalizationInfo(String normalizedQuery, Boolean hasPerson, Boolean hasLocation, String method) {
        MonitoringData data = get();
        data.setNormalizedQuery(normalizedQuery);
        data.setHasPersonEntity(hasPerson);
        data.setHasLocationEntity(hasLocation);
        data.setNormalizationMethod(method);
    }
    
    /**
     * 设置缓存信息
     */
    public static void setCacheInfo(String cacheLevel, Boolean cacheHit) {
        MonitoringData data = get();
        data.setCacheLevel(cacheLevel);
        data.setCacheHit(cacheHit);
    }
    
    /**
     * 设置RAG信息
     */
    public static void setRagInfo(Integer examplesCount, String industryTermsJson) {
        MonitoringData data = get();
        data.setRagExamplesCount(examplesCount);
        data.setIndustryTermsMatched(industryTermsJson);
    }
}
