package com.nl2sql.core.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class QueryCacheService {
    
    private static final String CACHE_PREFIX = "NL2SQL:query:";
    private static final int  DEFAULT_TTL_MINUTES = 30; // 默认缓存30分钟
    
    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;
    
    public QueryCacheService(RedisTemplate<String, Object> redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }
    
    /**
     * 缓存键生成结果
     */
    @Data
    public static class CacheKey {
        private String key;
        private String normalizedSQL;
        
        public CacheKey(String key, String normalizedSQL) {
            this.key = key;
            this.normalizedSQL = normalizedSQL;
        }
    }
    
    /**
     * 尝试从缓存获取查询结果
     * 
     * @param sql SQL语句
     * @return 缓存的结果，如果不存在返回null
     */
    @SuppressWarnings("unchecked")
    public CachedResult getFromCache(String sql) {
        try {
            CacheKey cacheKey = generateCacheKey(sql);
            String redisKey = CACHE_PREFIX + cacheKey.getKey();
            
            Object cached = redisTemplate.opsForValue().get(redisKey);
            
            if (cached != null) {
                // ✅ Redis 中存储的是 JSON 字符串，直接反序列化
                String json;
                if (cached instanceof String) {
                    json = (String) cached;
                } else {
                    // 兼容旧数据：如果是对象，先序列化
                    json = objectMapper.writeValueAsString(cached);
                }
                
                CachedResult result = objectMapper.readValue(json, CachedResult.class);
                
                log.info("缓存命中: sql={}, rows={}", 
                    cacheKey.getNormalizedSQL(), 
                    result != null && result.getData() != null ? result.getData().size() : 0);
                
                return result;
            }
            
            log.debug("缓存未命中: sql={}", cacheKey.getNormalizedSQL());
            return null;
            
        } catch (Exception e) {
            log.error("从缓存读取失败", e);
            return null;
        }
    }
    
    /**
     * ✅ 新增：从规范化查询文本检索5分SQL模板
     * 
     * @param normalizedQuery 规范化后的查询文本（如"查询最近<NUM>天订单列表"）
     * @return 缓存结果，未命中返回null
     */
    public CachedResult getFromNormalizedQuery(String normalizedQuery) {
        try {
            if (normalizedQuery == null || normalizedQuery.trim().isEmpty()) {
                return null;
            }
            
            String redisKey = CACHE_PREFIX + "query:" + md5(normalizedQuery);
            Object cached = redisTemplate.opsForValue().get(redisKey);
            
            if (cached != null) {
                String json;
                if (cached instanceof String) {
                    json = (String) cached;
                } else {
                    json = objectMapper.writeValueAsString(cached);
                }
                
                CachedResult result = objectMapper.readValue(json, CachedResult.class);
                
                log.info("[QueryCache] 模板命中: query={}, rating={}", 
                    normalizedQuery, result.getUserRating());
                
                return result;
            }
            
            log.debug("[QueryCache] 模板未命中: query={}", normalizedQuery);
            return null;
            
        } catch (Exception e) {
            log.error("[QueryCache] 读取失败", e);
            return null;
        }
    }
    
    /**
     * 将5分SQL模板存入缓存（基于规范化查询文本）
     * 
     * @param normalizedQuery 规范化查询文本
     * @param result 包含SQL模板的缓存结果
     * @param ttlMinutes 缓存时间（分钟）
     */
    public void putTemplateToCache(String normalizedQuery, CachedResult result, int ttlMinutes) {
        try {
            String redisKey = CACHE_PREFIX + "query:" + md5(normalizedQuery);
            String json = objectMapper.writeValueAsString(result);
            redisTemplate.opsForValue().set(redisKey, json, ttlMinutes, TimeUnit.MINUTES);
            
            log.info("[QueryCache] 模板写入成功: query={}, rating={}, sql={}", 
                normalizedQuery, result.getUserRating(), result.getSql());
            
        } catch (Exception e) {
            log.error("[QueryCache] 模板写入失败", e);
        }
    }
    
    /**
     * 将查询结果存入缓存（基于规范化SQL）
     * 
     * @param sql SQL语句
     * @param result 查询结果
     * @param ttlMinutes 缓存时间（分钟）
     */
    public void putToCache(String sql, CachedResult result, int ttlMinutes) {
        try {
            CacheKey cacheKey = generateCacheKey(sql);
            String redisKey = CACHE_PREFIX + cacheKey.getKey();
            
            // 序列化并存储
            String json = objectMapper.writeValueAsString(result);
            redisTemplate.opsForValue().set(redisKey, json, ttlMinutes, TimeUnit.MINUTES);
            
            log.info("缓存写入成功: sql={}, ttl={}min, size={}", 
                cacheKey.getNormalizedSQL(), 
                ttlMinutes,
                result.getData() != null ? result.getData().size() : 0);
            
        } catch (Exception e) {
            log.error("写入缓存失败", e);
        }
    }
    
    /**
     * 将查询结果存入缓存（使用默认TTL）
     */
    public void putToCache(String sql, CachedResult result) {
        putToCache(sql, result, DEFAULT_TTL_MINUTES);
    }
    
    /**
     * 清除指定SQL的缓存
     */
    public void invalidateCache(String sql) {
        try {
            CacheKey cacheKey = generateCacheKey(sql);
            String redisKey = CACHE_PREFIX + cacheKey.getKey();
            redisTemplate.delete(redisKey);
            log.info("缓存已清除: sql={}", cacheKey.getNormalizedSQL());
        } catch (Exception e) {
            log.error("清除缓存失败", e);
        }
    }
    
    /**
     * 清除所有查询缓存
     */
    public void clearAllCache() {
        try {
            redisTemplate.keys(CACHE_PREFIX + "*").forEach(redisTemplate::delete);
            log.info("所有查询缓存已清除");
        } catch (Exception e) {
            log.error("清除所有缓存失败", e);
        }
    }
    
    /**
     * 获取缓存统计信息
     */
    public CacheStats getCacheStats() {
        CacheStats stats = new CacheStats();
        try {
            java.util.Set<String> keys = redisTemplate.keys(CACHE_PREFIX + "*");
            stats.setTotalKeys(keys != null ? (long) keys.size() : 0L);
            stats.setPrefix(CACHE_PREFIX);
            stats.setDefaultTtlMinutes(DEFAULT_TTL_MINUTES);
        } catch (Exception e) {
            log.error("获取缓存统计失败", e);
        }
        return stats;
    }
    
    /**
     * 生成缓存键
     * 基于规范化后的SQL生成MD5哈希
     */
    private CacheKey generateCacheKey(String sql) {
        // 1. 规范化SQL
        String normalized = normalizeSQL(sql);
        
        // 2. 生成MD5
        String md5 = md5(normalized);
        
        return new CacheKey(md5, normalized);
    }
    
    /**
     * 规范化SQL
     * - 转大写
     * - 去除多余空格
     * - 统一格式
     */
    private String normalizeSQL(String sql) {
        if (sql == null) {
            return "";
        }
        
        // 转大写
        String normalized = sql.toUpperCase().trim();
        
        // 去除多余空格
        normalized = normalized.replaceAll("\\s+", " ");
        
        // 去除末尾分号
        normalized = normalized.replaceAll(";+$", "");
        
        // 标准化LIMIT语法
        normalized = normalized.replaceAll("LIMIT\\s+(\\d+)\\s+OFFSET\\s+(\\d+)", "LIMIT $2, $1");
        
        return normalized;
    }
    
    /**
     * MD5哈希
     */
    private String md5(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] hashBytes = md.digest(input.getBytes(StandardCharsets.UTF_8));
            
            StringBuilder hexString = new StringBuilder();
            for (byte b : hashBytes) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("MD5算法不可用", e);
        }
    }
    
    /**
     * 缓存的数据结构
     */
    @Data
    public static class CachedResult {
        private java.util.List<java.util.Map<String, Object>> data;
        private int rowCount;
        private double executionTime;
        private long cachedAt; // 缓存时间戳
        
        // ✅ 新增：5分反馈相关字段
        private String sql;              // 原始SQL（用于模板）
        private java.util.Set<String> usedTables;  // 使用的表
        private Integer userRating;      // 用户评分（4-5分为高质量）
        private String normalizedQuery;  // 归一化查询文本
        
        public CachedResult() {
            this.cachedAt = System.currentTimeMillis();
        }
    }
    
    /**
     * 缓存统计信息
     */
    @Data
    public static class CacheStats {
        private Long totalKeys;
        private String prefix;
        private int defaultTtlMinutes;
    }
}
