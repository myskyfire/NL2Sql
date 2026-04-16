package com.nl2sql.core.cache;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * SQL查询结果缓存服务
 */
@Slf4j
@Service
public class QueryResultCache {
    
    @Autowired
    private StringRedisTemplate redisTemplate;
    
    private static final long CACHE_TTL_HOURS = 1; // 缓存1小时
    private static final String CACHE_PREFIX = "query_result:";
    
    /**
     * 获取缓存的查询结果
     */
    @SuppressWarnings("unchecked")
    public CachedResult getCachedResult(String sql, Long datasourceId) {
        try {
            String cacheKey = buildCacheKey(sql, datasourceId);
            String cached = redisTemplate.opsForValue().get(cacheKey);
            
            if (cached != null) {
                log.debug("查询结果缓存命中: key={}", cacheKey);
                return deserialize(cached);
            }
        } catch (Exception e) {
            log.warn("缓存读取失败: {}", e.getMessage());
        }
        
        return null;
    }
    
    /**
     * 缓存查询结果
     */
    public void cacheResult(String sql, Long datasourceId, List<Map<String, Object>> data, int rowCount) {
        try {
            String cacheKey = buildCacheKey(sql, datasourceId);
            CachedResult result = new CachedResult(data, rowCount, System.currentTimeMillis());
            
            String serialized = serialize(result);
            redisTemplate.opsForValue().set(cacheKey, serialized, CACHE_TTL_HOURS, TimeUnit.HOURS);
            
            log.debug("查询结果已缓存: key={}, rowCount={}", cacheKey, rowCount);
        } catch (Exception e) {
            log.warn("缓存写入失败: {}", e.getMessage());
        }
    }
    
    /**
     * 清除指定数据源的缓存
     */
    public void clearCache(Long datasourceId) {
        try {
            String pattern = CACHE_PREFIX + datasourceId + ":*";
            redisTemplate.keys(pattern).forEach(redisTemplate::delete);
            log.info("已清除数据源 {} 的查询结果缓存", datasourceId);
        } catch (Exception e) {
            log.warn("清除缓存失败: {}", e.getMessage());
        }
    }
    
    /**
     * 构建缓存Key
     */
    private String buildCacheKey(String sql, Long datasourceId) {
        // 使用SQL哈希作为key的一部分，避免key过长
        int sqlHash = Math.abs(sql.hashCode());
        return CACHE_PREFIX + datasourceId + ":" + sqlHash;
    }
    
    /**
     * 序列化
     */
    private String serialize(CachedResult result) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                .writeValueAsString(result);
        } catch (Exception e) {
            throw new RuntimeException("序列化失败", e);
        }
    }
    
    /**
     * 反序列化
     */
    @SuppressWarnings("unchecked")
    private CachedResult deserialize(String json) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(json, CachedResult.class);
        } catch (Exception e) {
            throw new RuntimeException("反序列化失败", e);
        }
    }
    
    /**
     * 缓存结果封装
     */
    @Data
    public static class CachedResult {
        private List<Map<String, Object>> data;
        private int rowCount;
        private long cachedAt;
        
        public CachedResult() {}
        
        public CachedResult(List<Map<String, Object>> data, int rowCount, long cachedAt) {
            this.data = data;
            this.rowCount = rowCount;
            this.cachedAt = cachedAt;
        }
        
        /**
         * 检查缓存是否过期
         */
        public boolean isExpired(long ttlMillis) {
            return System.currentTimeMillis() - cachedAt > ttlMillis;
        }
    }
}
