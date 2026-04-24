package com.nl2sql.core.cache;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.chroma.ChromaApiVersion;
import dev.langchain4j.store.embedding.chroma.ChromaEmbeddingStore;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;

/**
 * 查询缓存向量服务 - 基于Chroma的语义相似度检索
 * 
 * 用于缓存NL2SQL查询结果，通过语义相似度实现"张三的订单"和"李四的订单"等相似查询的匹配
 * 
 * ✅ 降级策略：
 * 1. Chroma可用时使用向量相似度检索
 * 2. Chroma不可用时自动降级到MetadataCacheService的Jaccard算法
 */
@Slf4j
@Service
public class QueryCacheVectorService {
    
    @Value("${chroma.enabled:false}")
    private boolean chromaEnabled;
    
    @Value("${chroma.url:http://localhost:8000}")
    private String chromaUrl;
    
    @Value("${query-cache.vector.collection-name:NL2SQL_query_cache}")
    private String collectionName;
    
    @Value("${query-cache.vector.similarity-threshold:0.75}")  // ✅ 降低阈值: 0.95 → 0.75，允许时间词/人名替换
    private double similarityThreshold;
    
    @Value("${query-cache.vector.max-results:5}")
    private int maxResults;
    
    @Autowired(required = false)
    private EmbeddingModel embeddingModel; // 注入OllamaEmbeddingProvider
    
    private EmbeddingStore<TextSegment> embeddingStore;
    private boolean available = false;
    
    @PostConstruct
    public void init() {
        if (!chromaEnabled) {
            log.info("[QueryCacheVectorService] Chroma已禁用，将使用Jaccard降级方案");
            return;
        }
        
        try {
            log.info("初始化查询缓存向量数据库: url={}, collection={}", chromaUrl, collectionName);
            
            // ✅ 使用注入的OllamaEmbeddingProvider (支持配置切换模型)
            if (embeddingModel == null) {
                log.warn("[QueryCacheVectorService] EmbeddingModel未注入，Chroma不可用");
                this.available = false;
                return;
            }
            
            log.info("[QueryCacheVectorService] 使用Ollama嵌入模型: {}", embeddingModel.getClass().getSimpleName());
            
            // ✅ 直接创建集合，依赖运行时自动恢复机制处理维度问题
            this.embeddingStore = ChromaEmbeddingStore.builder()
                .baseUrl(chromaUrl)
                .collectionName(collectionName)
                .apiVersion(ChromaApiVersion.V2)
                .build();
            
            this.available = true;
            log.info("[QueryCacheVectorService] Chroma向量缓存初始化成功，阈值={}", similarityThreshold);
            
        } catch (Exception e) {
            log.warn("[QueryCacheVectorService] Chroma向量缓存初始化失败: {}", e.getMessage());
            log.info("[QueryCacheVectorService] 将使用Jaccard降级方案");
            this.available = false;
        }
    }
    

    
    /**
     * 通过HTTP API删除集合（Chroma v2）
     */
    private void deleteCollectionViaHttp() {
        try {
            // ✅ Chroma v2 删除集合: DELETE /api/v2/tenants/{tenant}/databases/{database}/collections/{name}
            String baseUrl = chromaUrl.replace("[::1]", "localhost").replace("/api/v2", "");
            String deleteUrl = String.format("%s/api/v2/tenants/default_tenant/databases/default_database/collections/%s",
                baseUrl, collectionName);
            
            log.info("[QueryCacheVectorService] 删除集合: {}", deleteUrl);
            
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(deleteUrl).openConnection();
            conn.setRequestMethod("DELETE");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            
            int responseCode = conn.getResponseCode();
            
            if (responseCode == 200 || responseCode == 204) {
                log.info("[QueryCacheVectorService] ✅ 集合删除成功");
            } else if (responseCode == 404) {
                log.info("[QueryCacheVectorService] ⚠️ 集合不存在");
            } else {
                log.warn("[QueryCacheVectorService] ⚠️ 删除失败，响应码: {}", responseCode);
            }
            
            conn.disconnect();
            
        } catch (Exception e) {
            log.warn("[QueryCacheVectorService] 删除集合异常: {}", e.getMessage());
        }
    }
    
    /**
     * 通过HTTP API创建集合（Chroma v2）
     */
    private void createCollectionViaHttp() {
        try {
            // ✅ Chroma v2 创建集合: POST /api/v2/tenants/{tenant}/databases/{database}/collections
            String baseUrl = chromaUrl.replace("[::1]", "localhost").replace("/api/v2", "");
            String createUrl = String.format("%s/api/v2/tenants/default_tenant/databases/default_database/collections",
                baseUrl);
            
            log.info("[QueryCacheVectorService] 创建集合: {}", createUrl);
            
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(createUrl).openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setDoOutput(true);
            
            // ✅ 构建请求体 - Chroma v2 需要完整的集合配置
            String jsonBody = String.format(
                "{\"name\":\"%s\",\"configuration\":{\"hnsw_configuration\":{}},\"metadata\":{}}",
                collectionName
            );
            log.debug("[QueryCacheVectorService] 创建集合请求体: {}", jsonBody);
            
            conn.getOutputStream().write(jsonBody.getBytes("UTF-8"));
            
            int responseCode = conn.getResponseCode();
            
            if (responseCode == 200 || responseCode == 201) {
                log.info("[QueryCacheVectorService] ✅ 集合创建成功");
            } else if (responseCode == 409) {
                // 集合已存在，这是正常情况
                log.info("[QueryCacheVectorService] ⚠️ 集合已存在（可忽略）");
            } else {
                // 读取错误响应
                String errorMsg = "";
                try {
                    java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(conn.getErrorStream()));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line);
                    }
                    reader.close();
                    errorMsg = sb.toString();
                } catch (Exception e) {
                    errorMsg = e.getMessage();
                }
                log.warn("[QueryCacheVectorService] ❌ 创建失败，响应码: {}, 错误: {}", responseCode, errorMsg);
            }
            
            conn.disconnect();
            
        } catch (Exception e) {
            log.error("[QueryCacheVectorService] 创建集合异常: {}", e.getMessage(), e);
        }
    }
    
    /**
     * ✅ 统一恢复方法：删除旧集合并创建新集合
     */
    private void recreateCollection() {
        deleteCollectionViaHttp();
        createCollectionViaHttp();
        
        this.embeddingStore = ChromaEmbeddingStore.builder()
            .baseUrl(chromaUrl)
            .collectionName(collectionName)
            .apiVersion(ChromaApiVersion.V2)
            .build();
        
        this.available = true;
        log.info("[QueryCacheVectorService] ✅ 集合重建完成");
    }
    
    /**
     * 检查服务是否可用
     */
    public boolean isAvailable() {
        return available && embeddingStore != null;
    }
    
    /**
     * 添加查询到向量缓存
     * 
     * @param query 原始查询文本
     * @param datasourceId 数据源ID
     * @param tables 检索到的表列表（JSON字符串）
     * @return 是否添加成功
     */
    public boolean addQueryToCache(String query, Long datasourceId, String tables) {
        if (!isAvailable()) {
            log.debug("[QueryCacheVectorService] Chroma不可用，跳过缓存写入: query={}", query);
            return false;
        }
        
        try {
            log.info("[QueryCacheVectorService] 开始写入Chroma缓存: query={}, datasourceId={}, tables={}", 
                query, datasourceId, tables);
            
            // 构建元数据
            Metadata metadata = new Metadata();
            metadata.put("datasource_id", datasourceId.toString());
            metadata.put("tables", tables);
            metadata.put("timestamp", String.valueOf(System.currentTimeMillis()));
            
            // 创建文本段
            TextSegment segment = TextSegment.from(query, metadata);
            
            // 生成向量并存储
            Embedding embedding = embeddingModel.embed(segment).content();
            String id = embeddingStore.add(embedding, segment);
            
            log.info("[QueryCacheVectorService] ✅ Chroma缓存写入成功: id={}, query={}, tables={}", 
                id, query, tables);
            return true;
            
        } catch (Exception e) {
            // ✅ 检测到维度不匹配，自动删除集合并重建
            if (e.getMessage() != null && e.getMessage().contains("dimension")) {
                log.warn("[QueryCacheVectorService] 检测到维度不匹配，自动重建: {}", e.getMessage());
                try {
                    recreateCollection();
                    return addQueryToCache(query, datasourceId, tables);
                    
                } catch (Exception rebuildError) {
                    log.error("[QueryCacheVectorService] ❌ 集合重建失败", rebuildError);
                    this.available = false;
                    return false;
                }
            }
            
            log.error("[QueryCacheVectorService] ❌ 添加查询缓存失败: query={}, error={}", 
                query, e.getMessage(), e);
            return false;
        }
    }
    
    /**
     * 语义相似度检索
     * 
     * @param query 查询文本
     * @param datasourceId 数据源ID（可选，用于过滤）
     * @return 匹配的缓存结果列表，按相似度降序排列
     */
    public List<CachedQueryResult> searchSimilarQueries(String query, Long datasourceId) {
        if (!isAvailable()) {
            log.debug("[QueryCacheVectorService] Chroma不可用，返回空结果: query={}", query);
            return new ArrayList<>();
        }
        
        try {
            log.info("[QueryCacheVectorService] 🔍 开始Chroma语义检索: query={}, datasourceId={}, threshold={}", 
                query, datasourceId, similarityThreshold);
            
            // 生成查询向量
            Embedding queryEmbedding = embeddingModel.embed(query).content();
            
            // 执行向量搜索
            dev.langchain4j.store.embedding.EmbeddingSearchRequest request = 
                dev.langchain4j.store.embedding.EmbeddingSearchRequest.builder()
                    .queryEmbedding(queryEmbedding)
                    .maxResults(maxResults)
                    .minScore(similarityThreshold)
                    .build();
            
            dev.langchain4j.store.embedding.EmbeddingSearchResult<TextSegment> searchResult = 
                embeddingStore.search(request);
            List<EmbeddingMatch<TextSegment>> matches = searchResult.matches();
            
            log.debug("[QueryCacheVectorService] Chroma原始匹配数: {}", matches.size());
            
            // 转换结果
            List<CachedQueryResult> results = new ArrayList<>();
            int filteredCount = 0;
            for (EmbeddingMatch<TextSegment> match : matches) {
                Metadata metadata = match.embedded().metadata();
                
                // 如果指定了datasourceId，进行过滤
                String cachedDatasourceId = metadata.getString("datasource_id");
                if (datasourceId != null && cachedDatasourceId != null) {
                    if (!cachedDatasourceId.equals(datasourceId.toString())) {
                        filteredCount++;
                        log.debug("[QueryCacheVectorService] 过滤不匹配数据源: cachedDsId={}, targetDsId={}", 
                            cachedDatasourceId, datasourceId);
                        continue; // 跳过不匹配的数据源
                    }
                }
                
                CachedQueryResult result = new CachedQueryResult();
                result.setCachedQuery(match.embedded().text());
                result.setTables(metadata.getString("tables"));
                result.setScore(match.score());
                result.setTimestamp(Long.parseLong(metadata.getString("timestamp")));
                
                results.add(result);
                log.debug("[QueryCacheVectorService] 匹配项: query='{}', score={}, tables={}", 
                    result.getCachedQuery(), String.format("%.3f", result.getScore()), result.getTables());
            }
            
            if (filteredCount > 0) {
                log.debug("[QueryCacheVectorService] 过滤掉 {} 个不匹配数据源的记录", filteredCount);
            }
            
            // 按相似度降序排序
            results.sort((a, b) -> Double.compare(b.getScore(), a.getScore()));
            
            if (!results.isEmpty()) {
                log.info("[QueryCacheVectorService] ✅ Chroma语义检索成功: found={} items, best_score={}, best_query='{}'", 
                    results.size(), String.format("%.3f", results.get(0).getScore()), results.get(0).getCachedQuery());
            } else {
                log.info("[QueryCacheVectorService] ⚠️ Chroma语义检索无匹配结果: query={}, threshold={}", 
                    query, similarityThreshold);
            }
            
            return results;
            
        } catch (Exception e) {
            // ✅ 检测到维度不匹配，自动删除集合并重建
            if (e.getMessage() != null && e.getMessage().contains("dimension")) {
                log.warn("[QueryCacheVectorService] 查询时维度不匹配，自动重建: {}", e.getMessage());
                try {
                    recreateCollection();
                    return searchSimilarQueries(query, datasourceId);
                    
                } catch (Exception rebuildError) {
                    log.error("[QueryCacheVectorService] ❌ 集合重建失败", rebuildError);
                    this.available = false;
                    return new ArrayList<>();
                }
            }
            
            log.error("[QueryCacheVectorService] ❌ Chroma语义检索失败: query={}, error={}", 
                query, e.getMessage(), e);
            return new ArrayList<>();
        }
    }
    
    /**
     * 获取最佳匹配结果
     * 
     * @param query 查询文本
     * @param datasourceId 数据源ID
     * @return 最佳匹配结果，未找到返回null
     */
    public CachedQueryResult findBestMatch(String query, Long datasourceId) {
        List<CachedQueryResult> results = searchSimilarQueries(query, datasourceId);
        return results.isEmpty() ? null : results.get(0);
    }
    
    /**
     * ✅ 新增：获取Top-K匹配结果（用于Reranker）
     * 
     * @param query 查询文本
     * @param datasourceId 数据源ID
     * @param topK 返回数量
     * @return Top-K匹配结果列表
     */
    public List<CachedQueryResult> findTopKMatches(String query, Long datasourceId, int topK) {
        if (!isAvailable()) {
            log.debug("[QueryCacheVectorService] Chroma不可用，返回空结果: query={}", query);
            return new ArrayList<>();
        }
        
        try {
            log.info("[QueryCacheVectorService] 🔍 开始Chroma Top-{}检索: query={}, datasourceId={}", 
                topK, query, datasourceId);
            
            // 生成查询向量
            Embedding queryEmbedding = embeddingModel.embed(query).content();
            
            // 执行向量搜索
            dev.langchain4j.store.embedding.EmbeddingSearchRequest request = 
                dev.langchain4j.store.embedding.EmbeddingSearchRequest.builder()
                    .queryEmbedding(queryEmbedding)
                    .maxResults(topK)
                    .minScore(similarityThreshold)
                    .build();
            
            dev.langchain4j.store.embedding.EmbeddingSearchResult<TextSegment> searchResult = 
                embeddingStore.search(request);
            List<EmbeddingMatch<TextSegment>> matches = searchResult.matches();
            
            log.debug("[QueryCacheVectorService] Chroma原始匹配数: {}", matches.size());
            
            // 转换结果
            List<CachedQueryResult> results = new ArrayList<>();
            for (EmbeddingMatch<TextSegment> match : matches) {
                Metadata metadata = match.embedded().metadata();
                
                // 如果指定了datasourceId，进行过滤
                String cachedDatasourceId = metadata.getString("datasource_id");
                if (datasourceId != null && cachedDatasourceId != null) {
                    if (!cachedDatasourceId.equals(datasourceId.toString())) {
                        continue;
                    }
                }
                
                CachedQueryResult result = new CachedQueryResult();
                result.setCachedQuery(match.embedded().text());
                result.setTables(metadata.getString("tables"));
                result.setScore(match.score());
                result.setTimestamp(Long.parseLong(metadata.getString("timestamp")));
                
                results.add(result);
            }
            
            // 按相似度降序排序
            results.sort((a, b) -> Double.compare(b.getScore(), a.getScore()));
            
            log.info("[QueryCacheVectorService] ✅ Chroma Top-{}检索完成: found={} items", 
                topK, results.size());
            
            return results;
            
        } catch (Exception e) {
            // ✅ 检测到维度不匹配，自动删除集合并重建
            if (e.getMessage() != null && e.getMessage().contains("dimension")) {
                log.warn("[QueryCacheVectorService] Top-K查询维度不匹配，自动重建: {}", e.getMessage());
                try {
                    recreateCollection();
                    return findTopKMatches(query, datasourceId, topK);
                    
                } catch (Exception rebuildError) {
                    log.error("[QueryCacheVectorService] ❌ 集合重建失败", rebuildError);
                    this.available = false;
                    return new ArrayList<>();
                }
            }
            
            log.error("[QueryCacheVectorService] ❌ Chroma Top-K检索失败: query={}, error={}", 
                query, e.getMessage(), e);
            return new ArrayList<>();
        }
    }
    
    /**
     * 清除所有缓存
     */
    public void clearAll() {
        if (!isAvailable()) {
            return;
        }
        
        try {
            log.info("[QueryCacheVectorService] 清空查询缓存集合: {}", collectionName);
            // Chroma不支持直接清空集合，需要通过管理界面操作
            log.warn("[QueryCacheVectorService] Chroma清空操作需要通过管理界面进行");
        } catch (Exception e) {
            log.error("[QueryCacheVectorService] 清空缓存失败: {}", e.getMessage(), e);
        }
    }
    
    /**
     * 获取配置信息
     */
    public CacheConfig getConfig() {
        CacheConfig config = new CacheConfig();
        config.setChromaEnabled(chromaEnabled);
        config.setAvailable(available);
        config.setCollectionName(collectionName);
        config.setSimilarityThreshold(similarityThreshold);
        config.setMaxResults(maxResults);
        return config;
    }
    
    // ==================== 内部类 ====================
    
    /**
     * 缓存查询结果
     */
    @Data
    public static class CachedQueryResult {
        private String cachedQuery;    // 缓存的查询文本
        private String tables;         // 检索到的表列表（JSON字符串）
        private double score;          // 相似度分数
        private long timestamp;        // 缓存时间戳
    }
    
    /**
     * 缓存配置
     */
    @Data
    public static class CacheConfig {
        private boolean chromaEnabled;
        private boolean available;
        private String collectionName;
        private double similarityThreshold;
        private int maxResults;
    }
}
