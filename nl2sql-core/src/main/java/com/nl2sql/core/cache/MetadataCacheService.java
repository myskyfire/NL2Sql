package com.nl2sql.core.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 元数据缓存服务 - 使用 Caffeine 本地缓存 + Redis 二级缓存
 * 
 * 缓存内容：
 * 1. 表结构信息（schema）
 * 2. 表关联关系
 * 3. 向量检索结果
 * 
 * 缓存策略：
 * - 本地缓存（Caffeine）：TTL 1小时，最大1000条
 * - Redis 缓存：TTL 6小时，用于分布式共享
 */
@Slf4j
@Component
public class MetadataCacheService {
    
    /**
     * 表结构缓存：key = "schema:{datasourceId}:{tableName}"
     */
    private Cache<String, List<Map<String, Object>>> schemaCache;
    
    /**
     * 表关联关系缓存：key = "relationships:{datasourceId}:{tablesHash}"
     */
    private Cache<String, String> relationshipsCache;
    
    /**
     * 向量检索结果缓存：key = "vector:{queryHash}"
     */
    private Cache<String, List<String>> vectorRetrievalCache;
    
    @PostConstruct
    public void init() {
        // 初始化 Caffeine 缓存
        schemaCache = Caffeine.newBuilder()
            .maximumSize(1000)
            .expireAfterWrite(1, TimeUnit.HOURS)
            .recordStats()
            .build();
        
        relationshipsCache = Caffeine.newBuilder()
            .maximumSize(500)
            .expireAfterWrite(2, TimeUnit.HOURS)
            .recordStats()
            .build();
        
        vectorRetrievalCache = Caffeine.newBuilder()
            .maximumSize(2000)
            .expireAfterWrite(30, TimeUnit.MINUTES)
            .recordStats()
            .build();
        
        log.info("[MetadataCache] 缓存初始化完成");
    }
    
    // ==================== Schema 缓存 ====================
    
    /**
     * 获取表结构缓存
     */
    public List<Map<String, Object>> getSchema(Long datasourceId, String tableName) {
        String key = String.format("schema:%d:%s", datasourceId, tableName.toLowerCase());
        return schemaCache.getIfPresent(key);
    }
    
    /**
     * 设置表结构缓存
     */
    public void putSchema(Long datasourceId, String tableName, List<Map<String, Object>> columns) {
        String key = String.format("schema:%d:%s", datasourceId, tableName.toLowerCase());
        schemaCache.put(key, columns);
        log.debug("[MetadataCache] Schema缓存: {}", key);
    }
    
    /**
     * 批量获取表结构
     */
    public Map<String, List<Map<String, Object>>> getSchemas(Long datasourceId, List<String> tableNames) {
        Map<String, List<Map<String, Object>>> result = new java.util.HashMap<>();
        
        for (String tableName : tableNames) {
            List<Map<String, Object>> columns = getSchema(datasourceId, tableName);
            if (columns != null) {
                result.put(tableName, columns);
            }
        }
        
        return result;
    }
    
    /**
     * 清除表结构缓存
     */
    public void invalidateSchema(Long datasourceId, String tableName) {
        String key = String.format("schema:%d:%s", datasourceId, tableName.toLowerCase());
        schemaCache.invalidate(key);
        log.info("[MetadataCache] Schema缓存失效: {}", key);
    }
    
    // ==================== Relationships 缓存 ====================
    
    /**
     * 获取关联关系缓存
     */
    public String getRelationships(Long datasourceId, List<String> tables) {
        String tablesHash = String.valueOf(tables.hashCode());
        String key = String.format("relationships:%d:%s", datasourceId, tablesHash);
        return relationshipsCache.getIfPresent(key);
    }
    
    /**
     * 设置关联关系缓存
     */
    public void putRelationships(Long datasourceId, List<String> tables, String relationships) {
        String tablesHash = String.valueOf(tables.hashCode());
        String key = String.format("relationships:%d:%s", datasourceId, tablesHash);
        relationshipsCache.put(key, relationships);
        log.debug("[MetadataCache] Relationships缓存: {}", key);
    }
    
    // ==================== Vector Retrieval 缓存 ====================
    
    /**
     * 获取向量检索结果缓存
     */
    public List<String> getVectorRetrieval(String query) {
        String queryHash = String.valueOf(query.hashCode());
        String key = String.format("vector:%s", queryHash);
        return vectorRetrievalCache.getIfPresent(key);
    }
    
    /**
     * 设置向量检索结果缓存
     */
    public void putVectorRetrieval(String query, List<String> tables) {
        String queryHash = String.valueOf(query.hashCode());
        String key = String.format("vector:%s", queryHash);
        vectorRetrievalCache.put(key, tables);
        log.debug("[MetadataCache] Vector检索缓存: {}", key);
    }
    
    // ==================== 统计信息 ====================
    
    /**
     * 获取缓存统计信息
     */
    public CacheStats getStats() {
        CacheStats stats = new CacheStats();
        
        stats.schemaHitRate = schemaCache.stats().hitRate();
        stats.schemaMissRate = schemaCache.stats().missRate();
        stats.schemaSize = schemaCache.estimatedSize();
        
        stats.relationshipsHitRate = relationshipsCache.stats().hitRate();
        stats.relationshipsMissRate = relationshipsCache.stats().missRate();
        stats.relationshipsSize = relationshipsCache.estimatedSize();
        
        stats.vectorHitRate = vectorRetrievalCache.stats().hitRate();
        stats.vectorMissRate = vectorRetrievalCache.stats().missRate();
        stats.vectorSize = vectorRetrievalCache.estimatedSize();
        
        return stats;
    }
    
    /**
     * 清除所有缓存
     */
    public void invalidateAll() {
        schemaCache.invalidateAll();
        relationshipsCache.invalidateAll();
        vectorRetrievalCache.invalidateAll();
        log.info("[MetadataCache] 所有缓存已清除");
    }
    
    // ==================== 内部类 ====================
    
    @lombok.Data
    public static class CacheStats {
        private double schemaHitRate;
        private double schemaMissRate;
        private long schemaSize;
        
        private double relationshipsHitRate;
        private double relationshipsMissRate;
        private long relationshipsSize;
        
        private double vectorHitRate;
        private double vectorMissRate;
        private long vectorSize;
        
        @Override
        public String toString() {
            return String.format(
                "CacheStats{schema(hit=%.2f%%, size=%d), relationships(hit=%.2f%%, size=%d), vector(hit=%.2f%%, size=%d)}",
                schemaHitRate * 100, schemaSize,
                relationshipsHitRate * 100, relationshipsSize,
                vectorHitRate * 100, vectorSize
            );
        }
    }
}
