package com.nl2sql.core.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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
    
    @Autowired(required = false)
    private QueryCacheVectorService queryCacheVectorService;
    
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
    
    /**
     * ✅ 新增：模糊查询缓存（归一化后）：key = "vector:fuzzy:{normalizedQueryHash}"
     */
    private Cache<String, List<String>> fuzzyVectorRetrievalCache;
    
    /**
     * ✅ 新增：语义相似度缓存索引（用于L3快速检索）
     * key = datasourceId, value = List<CachedQueryEntry>
     */
    private java.util.Map<Long, java.util.List<CachedQueryEntry>> semanticIndexByDatasource = new java.util.concurrent.ConcurrentHashMap<>();
    
    /**
     * ✅ 新增：列名白名单缓存：key = "columns:{datasourceId}:{tablesHash}"
     */
    private Cache<String, java.util.Set<String>> columnWhitelistCache;
    
    /**
     * ✅ 新增：LLM列名翻译缓存：key = "translation:{datasourceId}:{columnNameHash}"
     * 仅在从元数据中找不到中文注释时使用
     */
    private Cache<String, String> columnTranslationCache;
    
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
        
        // ✅ 新增：模糊查询缓存（TTL 1小时，最大1000条）
        fuzzyVectorRetrievalCache = Caffeine.newBuilder()
            .maximumSize(1000)
            .expireAfterWrite(1, TimeUnit.HOURS)
            .recordStats()
            .build();
        
        // ✅ 新增：列名白名单缓存（TTL 2小时，最大500条）
        columnWhitelistCache = Caffeine.newBuilder()
            .maximumSize(500)
            .expireAfterWrite(2, TimeUnit.HOURS)
            .recordStats()
            .build();
        
        // ✅ 新增：LLM列名翻译缓存（TTL 24小时，最大2000条）
        columnTranslationCache = Caffeine.newBuilder()
            .maximumSize(2000)
            .expireAfterWrite(24, TimeUnit.HOURS)
            .recordStats()
            .build();
        
        log.info("[MetadataCache] 缓存初始化完成（含L3语义索引 + LLM翻译缓存）");
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
     * 获取向量检索结果缓存（精确匹配）
     */
    public List<String> getVectorRetrieval(String query) {
        String queryHash = String.valueOf(query.hashCode());
        String key = String.format("vector:%s", queryHash);
        return vectorRetrievalCache.getIfPresent(key);
    }
    
    /**
     * 设置向量检索结果缓存（精确匹配）
     */
    public void putVectorRetrieval(String query, List<String> tables) {
        String queryHash = String.valueOf(query.hashCode());
        String key = String.format("vector:%s", queryHash);
        vectorRetrievalCache.put(key, tables);
        log.debug("[MetadataCache] Vector检索缓存(精确): {}", key);
    }
    
    /**
     * ✅ 新增：获取模糊向量检索结果缓存（归一化匹配）
     */
    public List<String> getFuzzyVectorRetrieval(String normalizedQuery) {
        String queryHash = String.valueOf(normalizedQuery.hashCode());
        String key = String.format("vector:fuzzy:%s", queryHash);
        return fuzzyVectorRetrievalCache.getIfPresent(key);
    }
    
    /**
     * ✅ 新增：设置模糊向量检索结果缓存（归一化匹配）
     */
    public void putFuzzyVectorRetrieval(String normalizedQuery, List<String> tables) {
        String queryHash = String.valueOf(normalizedQuery.hashCode());
        String key = String.format("vector:fuzzy:%s", queryHash);
        fuzzyVectorRetrievalCache.put(key, tables);
        log.debug("[MetadataCache] Vector检索缓存(模糊): {}", key);
    }
    
    /**
     * ✅ L3 语义相似度检索 - 优先使用Chroma，降级到Jaccard
     * @param query 原始查询
     * @param datasourceId 数据源ID
     * @param threshold 相似度阈值（0-1），默认0.85
     * @return 相似的表列表，未找到返回null
     */
    public List<String> findSimilarQueryBySemantic(String query, Long datasourceId, double threshold) {
        log.info("[MetadataCache] 🔍 L3语义检索开始: query='{}', datasourceId={}, threshold={}", 
            query, datasourceId, threshold);
        
        // ✅ 步骤1：尝试使用Chroma向量检索
        if (queryCacheVectorService != null && queryCacheVectorService.isAvailable()) {
            log.info("[MetadataCache] 🚀 使用Chroma向量检索");
            try {
                QueryCacheVectorService.CachedQueryResult bestMatch = 
                    queryCacheVectorService.findBestMatch(query, datasourceId);
                
                if (bestMatch != null && bestMatch.getScore() >= threshold) {
                    log.info("[MetadataCache] ✅ L3语义匹配成功(Chroma): query='{}', similar='{}', similarity={:.3f}", 
                        query, bestMatch.getCachedQuery(), bestMatch.getScore());
                    
                    // 解析JSON格式的tables字符串
                    try {
                        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                        @SuppressWarnings("unchecked")
                        List<String> tables = mapper.readValue(bestMatch.getTables(), List.class);
                        log.info("[MetadataCache] ✅ Chroma缓存命中，返回表: {}", tables);
                        return tables;
                    } catch (Exception e) {
                        log.warn("[MetadataCache] ⚠️ 解析缓存的tables失败: query='{}', error={}", 
                            query, e.getMessage());
                        return null;
                    }
                }
                
                log.info("[MetadataCache] ⚠️ Chroma语义匹配失败: bestScore={:.3f}, threshold={}, query='{}'", 
                    bestMatch != null ? bestMatch.getScore() : 0.0, threshold, query);
                    
            } catch (Exception e) {
                log.warn("[MetadataCache] ⚠️ Chroma检索异常，降级到Jaccard: query='{}', error={}", 
                    query, e.getMessage());
            }
        } else {
            if (queryCacheVectorService == null) {
                log.debug("[MetadataCache] QueryCacheVectorService未注入");
            } else {
                log.debug("[MetadataCache] QueryCacheVectorService不可用");
            }
        }
        
        // ✅ 步骤2：降级到Jaccard算法
        log.info("[MetadataCache] 🔄 降级到Jaccard算法进行语义匹配");
        java.util.List<CachedQueryEntry> index = semanticIndexByDatasource.get(datasourceId);
        if (index == null || index.isEmpty()) {
            log.info("[MetadataCache] ⚠️ Jaccard索引为空: datasourceId={}", datasourceId);
            return null;
        }
        
        log.debug("[MetadataCache] Jaccard索引大小: {}", index.size());
        
        CachedQueryEntry bestMatch = null;
        double bestSimilarity = 0.0;
        
        // ⚠️ 性能优化：只检查最近100个缓存项
        int checkCount = Math.min(index.size(), 100);
        for (int i = index.size() - 1; i >= index.size() - checkCount; i--) {
            CachedQueryEntry entry = index.get(i);
            double similarity = calculateTextSimilarity(query, entry.originalQuery);
            
            if (similarity > bestSimilarity) {
                bestSimilarity = similarity;
                bestMatch = entry;
            }
        }
        
        if (bestMatch != null && bestSimilarity >= threshold) {
            log.info("[MetadataCache] ✅ L3语义匹配成功(Jaccard): query='{}', similar='{}', similarity={:.3f}", 
                query, bestMatch.originalQuery, bestSimilarity);
            return bestMatch.tables;
        }
        
        log.info("[MetadataCache] ❌ L3语义匹配失败(Jaccard): query='{}', bestSimilarity={:.3f}, threshold={}", 
            query, bestSimilarity, threshold);
        return null;
    }
    
    /**
     * ✅ 新增：记录查询到语义索引（供L3使用）
     */
    public void recordQueryToSemanticIndex(Long datasourceId, String query, List<String> tables) {
        semanticIndexByDatasource.computeIfAbsent(datasourceId, k -> new java.util.ArrayList<>());
        
        java.util.List<CachedQueryEntry> index = semanticIndexByDatasource.get(datasourceId);
        
        // 检查是否已存在相同查询
        for (CachedQueryEntry entry : index) {
            if (entry.originalQuery.equals(query)) {
                entry.lastAccessTime = System.currentTimeMillis();
                entry.accessCount++;
                return;
            }
        }
        
        // 添加新条目
        CachedQueryEntry newEntry = new CachedQueryEntry(query, tables, System.currentTimeMillis());
        index.add(newEntry);
        
        // ⚠️ 性能优化：限制索引大小，移除最旧的条目
        if (index.size() > 500) {
            index.remove(0); // 移除最旧的
        }
        
        log.debug("[MetadataCache] L3语义索引更新: datasourceId={}, totalEntries={}", 
            datasourceId, index.size());
    }
    
    /**
     * ✅ 新增：计算文本相似度（简化版Jaccard + 编辑距离）
     */
    private double calculateTextSimilarity(String text1, String text2) {
        if (text1 == null || text2 == null) {
            return 0.0;
        }
        
        if (text1.equals(text2)) {
            return 1.0;
        }
        
        // 方法1: 字符级Jaccard相似度
        java.util.Set<Character> set1 = new java.util.HashSet<>();
        java.util.Set<Character> set2 = new java.util.HashSet<>();
        
        for (char c : text1.toCharArray()) {
            if (!Character.isWhitespace(c)) {
                set1.add(c);
            }
        }
        for (char c : text2.toCharArray()) {
            if (!Character.isWhitespace(c)) {
                set2.add(c);
            }
        }
        
        java.util.Set<Character> intersection = new java.util.HashSet<>(set1);
        intersection.retainAll(set2);
        
        java.util.Set<Character> union = new java.util.HashSet<>(set1);
        union.addAll(set2);
        
        double jaccardSim = union.isEmpty() ? 0.0 : (double) intersection.size() / union.size();
        
        // 方法2: 公共子串比例
        int commonChars = 0;
        int minLength = Math.min(text1.length(), text2.length());
        for (int i = 0; i < minLength; i++) {
            if (text1.charAt(i) == text2.charAt(i)) {
                commonChars++;
            }
        }
        double prefixSim = (double) commonChars / Math.max(text1.length(), text2.length());
        
        // 综合评分：Jaccard占60%，前缀相似度占40%
        return jaccardSim * 0.6 + prefixSim * 0.4;
    }
    
    // ==================== Column Whitelist 缓存 ====================
    
    /**
     * ✅ 新增：获取列名白名单缓存
     */
    public java.util.Set<String> getColumnWhitelist(Long datasourceId, java.util.Set<String> tableNames) {
        String tablesHash = String.valueOf(tableNames.hashCode());
        String key = String.format("columns:%d:%s", datasourceId, tablesHash);
        return columnWhitelistCache.getIfPresent(key);
    }
    
    /**
     * ✅ 新增：设置列名白名单缓存
     */
    public void putColumnWhitelist(Long datasourceId, java.util.Set<String> tableNames, java.util.Set<String> columns) {
        String tablesHash = String.valueOf(tableNames.hashCode());
        String key = String.format("columns:%d:%s", datasourceId, tablesHash);
        columnWhitelistCache.put(key, columns);
        log.debug("[MetadataCache] 列名白名单缓存: {} ({}个列)", key, columns.size());
    }
    
    // ==================== Column Translation 缓存（LLM翻译结果）====================
    
    /**
     * ✅ 新增：获取LLM列名翻译缓存
     * @param datasourceId 数据源ID
     * @param columnName 英文列名
     * @return 中文翻译，未找到返回null
     */
    public String getColumnTranslation(Long datasourceId, String columnName) {
        if (columnName == null || columnName.isEmpty()) {
            return null;
        }
        String key = String.format("translation:%d:%s", datasourceId, columnName.toLowerCase());
        String translation = columnTranslationCache.getIfPresent(key);
        if (translation != null) {
            log.debug("[MetadataCache] LLM翻译缓存命中: {} -> {}", columnName, translation);
        }
        return translation;
    }
    
    /**
     * ✅ 新增：设置LLM列名翻译缓存
     * @param datasourceId 数据源ID
     * @param columnName 英文列名
     * @param chineseName 中文翻译
     */
    public void putColumnTranslation(Long datasourceId, String columnName, String chineseName) {
        if (columnName == null || columnName.isEmpty() || chineseName == null) {
            return;
        }
        String key = String.format("translation:%d:%s", datasourceId, columnName.toLowerCase());
        columnTranslationCache.put(key, chineseName);
        log.debug("[MetadataCache] LLM翻译缓存: {} -> {}", columnName, chineseName);
    }
    
    /**
     * ✅ 新增：批量获取LLM列名翻译缓存
     * @param datasourceId 数据源ID
     * @param columnNames 英文列名列表
     * @return Map<英文列名, 中文翻译>，只包含缓存命中的项
     */
    public Map<String, String> batchGetColumnTranslations(Long datasourceId, List<String> columnNames) {
        Map<String, String> result = new java.util.HashMap<>();
        for (String colName : columnNames) {
            String translation = getColumnTranslation(datasourceId, colName);
            if (translation != null) {
                result.put(colName, translation);
            }
        }
        if (!result.isEmpty()) {
            log.debug("[MetadataCache] LLM翻译批量缓存命中: {}/{} 个", result.size(), columnNames.size());
        }
        return result;
    }
    
    /**
     * ✅ 新增：批量设置LLM列名翻译缓存
     * @param datasourceId 数据源ID
     * @param translations Map<英文列名, 中文翻译>
     */
    public void batchPutColumnTranslations(Long datasourceId, Map<String, String> translations) {
        if (translations == null || translations.isEmpty()) {
            return;
        }
        for (Map.Entry<String, String> entry : translations.entrySet()) {
            putColumnTranslation(datasourceId, entry.getKey(), entry.getValue());
        }
        log.debug("[MetadataCache] LLM翻译批量缓存: {} 个", translations.size());
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
        
        stats.fuzzyVectorHitRate = fuzzyVectorRetrievalCache.stats().hitRate();
        stats.fuzzyVectorMissRate = fuzzyVectorRetrievalCache.stats().missRate();
        stats.fuzzyVectorSize = fuzzyVectorRetrievalCache.estimatedSize();
        
        return stats;
    }
    
    /**
     * 清除所有缓存
     */
    public void invalidateAll() {
        schemaCache.invalidateAll();
        relationshipsCache.invalidateAll();
        vectorRetrievalCache.invalidateAll();
        fuzzyVectorRetrievalCache.invalidateAll();
        semanticIndexByDatasource.clear();
        columnTranslationCache.invalidateAll();
        log.info("[MetadataCache] 所有缓存已清除（含L3语义索引 + LLM翻译缓存）");
    }
    
    // ==================== 内部类 ====================
    
    /**
     * ✅ 新增：缓存查询条目（用于L3语义索引）
     */
    @lombok.Data
    @lombok.AllArgsConstructor
    public static class CachedQueryEntry {
        private String originalQuery;      // 原始查询文本
        private List<String> tables;       // 检索到的表
        private long timestamp;            // 创建时间戳
        private long lastAccessTime;       // 最后访问时间
        private int accessCount;           // 访问次数
        
        public CachedQueryEntry(String query, List<String> tables, long timestamp) {
            this.originalQuery = query;
            this.tables = tables;
            this.timestamp = timestamp;
            this.lastAccessTime = timestamp;
            this.accessCount = 1;
        }
    }
    
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
        
        private double fuzzyVectorHitRate;
        private double fuzzyVectorMissRate;
        private long fuzzyVectorSize;
        
        @Override
        public String toString() {
            return String.format(
                "CacheStats{schema(hit=%.2f%%, size=%d), relationships(hit=%.2f%%, size=%d), vector(hit=%.2f%%, size=%d), fuzzyVector(hit=%.2f%%, size=%d)}",
                schemaHitRate * 100, schemaSize,
                relationshipsHitRate * 100, relationshipsSize,
                vectorHitRate * 100, vectorSize,
                fuzzyVectorHitRate * 100, fuzzyVectorSize
            );
        }
    }
}
