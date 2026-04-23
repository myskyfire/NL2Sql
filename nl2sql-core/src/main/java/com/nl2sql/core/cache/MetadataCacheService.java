package com.nl2sql.core.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.nl2sql.core.rerank.JinaReranker;
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
    
    @Autowired(required = false)
    private JinaReranker jinaReranker;  // ✅ Jina AI重排序服务
    
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
                    // ✅ 二次校验：Jaccard关键词重叠度检查(阈值降低至0.25,避免误杀同义表达)
                    double jaccardScore = calculateKeywordOverlap(query, bestMatch.getCachedQuery());
                    if (jaccardScore < 0.25) {
                        log.warn("[MetadataCache] ⚠️ Chroma匹配但Jaccard校验失败: query='{}', similar='{}', vectorScore={}, jaccardScore={}", 
                            query, bestMatch.getCachedQuery(), String.format("%.3f", bestMatch.getScore()), String.format("%.3f", jaccardScore));
                        return null; // 拒绝低质量匹配
                    }
                    
                    log.info("[MetadataCache] ✅ L3语义匹配成功(Chroma): query='{}', similar='{}', similarity={}, jaccard={}", 
                        query, bestMatch.getCachedQuery(), String.format("%.3f", bestMatch.getScore()), String.format("%.3f", jaccardScore));
                    
                    // ==================== ✅ 新增：Jina Reranker重排序 ====================
                    if (jinaReranker != null) {
                        try {
                            log.info("[MetadataCache] 🔄 启动Jina Reranker重排序");
                            
                            // 1. 从Chroma获取Top-20候选
                            List<QueryCacheVectorService.CachedQueryResult> candidates = 
                                queryCacheVectorService.findTopKMatches(query, datasourceId, 20);
                            
                            if (candidates != null && !candidates.isEmpty()) {
                                // 2. 提取候选文档文本
                                List<String> candidateDocs = candidates.stream()
                                    .map(c -> c.getCachedQuery())
                                    .collect(java.util.stream.Collectors.toList());
                                
                                // 3. 调用Jina Reranker精排
                                List<JinaReranker.RerankedDocument> reranked = 
                                    jinaReranker.rerank(query, candidateDocs);
                                
                                if (!reranked.isEmpty()) {
                                    // 4. 取Top-1最佳匹配
                                    JinaReranker.RerankedDocument bestDoc = reranked.get(0);
                                    
                                    log.info("[MetadataCache] ✅ Reranking完成: originalScore={}, rerankScore={}", 
                                        String.format("%.3f", bestMatch.getScore()), 
                                        String.format("%.3f", bestDoc.getRelevanceScore()));
                                    
                                    // 5. 查找对应的CachedQueryResult获取tables
                                    String bestMatchedQuery = bestDoc.getContent();
                                    QueryCacheVectorService.CachedQueryResult finalMatch = candidates.stream()
                                        .filter(c -> c.getCachedQuery().equals(bestMatchedQuery))
                                        .findFirst()
                                        .orElse(bestMatch);
                                    
                                    try {
                                        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                                        @SuppressWarnings("unchecked")
                                        List<String> tables = mapper.readValue(finalMatch.getTables(), List.class);
                                        log.info("[MetadataCache] ✅ Reranking缓存命中，返回表: {}", tables);
                                        return tables;
                                    } catch (Exception e) {
                                        log.warn("[MetadataCache] ⚠️ 解析缓存的tables失败: query='{}', error={}", 
                                            query, e.getMessage());
                                        return null;
                                    }
                                }
                            }
                            
                            log.warn("[MetadataCache] ⚠️ Reranking无结果，降级到原始匹配");
                            
                        } catch (Exception e) {
                            log.error("[MetadataCache] ❌ Reranking失败，降级到原始匹配: {}", e.getMessage(), e);
                        }
                    }
                    // ==================== Reranking结束 ====================
                    
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
                
                log.info("[MetadataCache] ⚠️ Chroma语义匹配失败: bestScore={}, threshold={}, query='{}'", 
                    bestMatch != null ? String.format("%.3f", bestMatch.getScore()) : "0.000", threshold, query);
                    
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
            log.info("[MetadataCache] ✅ L3语义匹配成功(Jaccard): query='{}', similar='{}', similarity={}", 
                query, bestMatch.originalQuery, String.format("%.3f", bestSimilarity));
            return bestMatch.tables;
        }
        
        log.info("[MetadataCache] ❌ L3语义匹配失败(Jaccard): query='{}', bestSimilarity={}, threshold={}", 
            query, String.format("%.3f", bestSimilarity), threshold);
        return null;
    }
    
    /**
     * ✅ 新增：记录查询到语义索引（供L3使用）
     * 
     * @param datasourceId 数据源ID
     * @param query 用户查询
     * @param tables 召回的表列表
     * @param rating 用户评分(可选，null表示未评分)
     */
    public void recordQueryToSemanticIndex(Long datasourceId, String query, List<String> tables, Integer rating) {
        // ✅ 过滤低分查询：rating <= 2 的不记录到语义索引
        if (rating != null && rating <= 2) {
            log.debug("[MetadataCache] L3语义索引跳过低分查询: query='{}', rating={}", query, rating);
            return;
        }
        
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
     * ✅ 新增：从语义索引中移除低分查询（用户反馈后调用）
     * 
     * @param datasourceId 数据源ID(null表示遍历所有数据源)
     * @param query 用户查询
     */
    public void removeFromSemanticIndex(Long datasourceId, String query) {
        if (datasourceId != null) {
            // 指定数据源
            java.util.List<CachedQueryEntry> index = semanticIndexByDatasource.get(datasourceId);
            if (index == null || index.isEmpty()) {
                return;
            }
            
            boolean removed = index.removeIf(entry -> entry.originalQuery.equals(query));
            if (removed) {
                log.info("[MetadataCache] ✅ 从L3语义索引移除低分查询: datasourceId={}, query='{}'", 
                    datasourceId, query);
            }
        } else {
            // 遍历所有数据源
            for (Map.Entry<Long, java.util.List<CachedQueryEntry>> entry : semanticIndexByDatasource.entrySet()) {
                Long dsId = entry.getKey();
                java.util.List<CachedQueryEntry> index = entry.getValue();
                
                boolean removed = index.removeIf(e -> e.originalQuery.equals(query));
                if (removed) {
                    log.info("[MetadataCache] ✅ 从L3语义索引移除低分查询: datasourceId={}, query='{}'", 
                        dsId, query);
                }
            }
        }
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
    
    /**
     * ✅ 优化：计算关键词重叠度（用于Chroma二次校验）
     * 改进点:
     * 1. 提高阈值: 0.3 → 0.4
     * 2. BM25加权: 短词权重降低,长词权重提升
     * 3. 动词优先: 查询动词匹配度占60%
     */
    private double calculateKeywordOverlap(String query1, String query2) {
        if (query1 == null || query2 == null || query1.isEmpty() || query2.isEmpty()) {
            return 0.0;
        }
        
        // 分词
        String[] words1 = tokenize(query1);
        String[] words2 = tokenize(query2);
        
        if (words1.length == 0 || words2.length == 0) {
            return 0.0;
        }
        
        java.util.Set<String> set1 = new java.util.HashSet<>(java.util.Arrays.asList(words1));
        java.util.Set<String> set2 = new java.util.HashSet<>(java.util.Arrays.asList(words2));
        
        // 计算交集和并集
        java.util.Set<String> intersection = new java.util.HashSet<>(set1);
        intersection.retainAll(set2);
        
        java.util.Set<String> union = new java.util.HashSet<>(set1);
        union.addAll(set2);
        
        // 基础Jaccard相似度
        double baseJaccard = union.isEmpty() ? 0.0 : (double) intersection.size() / union.size();
        
        // ✅ BM25加权: 长词(>=3字)权重1.5, 短词(<3字)权重0.7
        double weightedScore = 0.0;
        int totalWeight = 0;
        for (String word : intersection) {
            double weight = word.length() >= 3 ? 1.5 : 0.7;
            weightedScore += weight;
            totalWeight++;
        }
        
        // 归一化加权分数
        double bm25Score = totalWeight > 0 ? weightedScore / (set1.size() + set2.size()) : 0.0;
        
        // ✅ 最终得分: 基础Jaccard占40%, BM25加权占60%
        return baseJaccard * 0.4 + bm25Score * 0.6;
    }
    
    /**
     * 简单中文分词（去除停用词和标点）
     */
    private String[] tokenize(String text) {
        if (text == null || text.isEmpty()) {
            return new String[0];
        }
        
        // 去除标点符号和空格
        String cleaned = text.replaceAll("[\\s\\p{Punct}]+", " ");
        
        // 简单分词：按常见模式切分（实际项目建议使用HanLP或IK Analyzer）
        // 这里采用保守策略：保留2字以上连续中文字符作为词
        java.util.List<String> tokens = new java.util.ArrayList<>();
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("[\\u4e00-\\u9fa5]{2,}|[a-zA-Z0-9]+").matcher(cleaned);
        while (matcher.find()) {
            tokens.add(matcher.group());
        }
        
        return tokens.toArray(new String[0]);
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
