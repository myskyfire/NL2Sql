package com.nl2sql.core.retriever;

import com.nl2sql.core.cache.MetadataCacheService;
import com.nl2sql.core.cache.QueryCacheVectorService;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.ollama.OllamaEmbeddingModel;
import com.nl2sql.common.util.QueryNormalizer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Component
public class VectorRetriever {
    
    @Autowired(required = false)
    private MetadataCacheService cacheService;
    
    @Autowired(required = false)
    private QueryCacheVectorService queryCacheVectorService;
    
    @Value("${ollama.base-url:http://localhost:11434}")
    private String ollamaBaseUrl;
    
    @Value("${ollama.embedding-model:bge-m3}")
    private String embeddingModelName;
    
    private EmbeddingModel embeddingModel;
    // ✅ 按数据源隔离的向量索引: datasourceId -> (tableName -> Embedding)
    private final Map<Long, Map<String, Embedding>> tableEmbeddingsByDatasource = new ConcurrentHashMap<>();
    private final Map<Long, Map<String, Embedding>> columnEmbeddingsByDatasource = new ConcurrentHashMap<>();
    
    // ✅ 当前查询文本(用于意图检测)
    private String currentQuery;
    
    public VectorRetriever() {
        // 构造函数中不初始化,等待@PostConstruct
    }
    
    @PostConstruct
    public void init() {
        // ✅ 使用Ollama bge-m3模型(1024维,中文优化)
        this.embeddingModel = OllamaEmbeddingModel.builder()
            .baseUrl(ollamaBaseUrl)
            .modelName(embeddingModelName)
            .timeout(Duration.ofSeconds(30))
            .build();
        log.info("向量检索器初始化完成,使用模型: {}", embeddingModelName);
    }
    
    /**
     * 构建指定数据源的向量索引
     */
    public void buildIndex(Long datasourceId, Map<String, com.nl2sql.core.metadata.TableMetadata> metadata) {
        log.info("开始构建数据源 {} 的向量索引...", datasourceId);
        
        Map<String, Embedding> tableEmbeddings = new ConcurrentHashMap<>();
        Map<String, Embedding> columnEmbeddings = new ConcurrentHashMap<>();
        
        for (Map.Entry<String, com.nl2sql.core.metadata.TableMetadata> entry : metadata.entrySet()) {
            String tableName = entry.getKey();
            com.nl2sql.core.metadata.TableMetadata tableMeta = entry.getValue();
            
            // 增强向量化文本：表名 + 注释 + 所有字段名 + 所有字段注释
            StringBuilder tableTextBuilder = new StringBuilder();
            tableTextBuilder.append(tableName).append(" ");
            
            if (tableMeta.getTableComment() != null && !tableMeta.getTableComment().isEmpty()) {
                tableTextBuilder.append(tableMeta.getTableComment()).append(" ");
            }
            
            // 添加所有字段信息（提升字段级语义匹配）
            for (com.nl2sql.core.metadata.ColumnMetadata col : tableMeta.getColumns()) {
                tableTextBuilder.append(col.getColumnName()).append(" ");
                if (col.getColumnComment() != null && !col.getColumnComment().isEmpty()) {
                    tableTextBuilder.append(col.getColumnComment()).append(" ");
                }
            }
            
            String tableText = tableTextBuilder.toString().trim();
            tableEmbeddings.put(tableName, embeddingModel.embed(tableText).content());
            
            // 字段级索引也增强
            for (com.nl2sql.core.metadata.ColumnMetadata col : tableMeta.getColumns()) {
                String colKey = tableName + "." + col.getColumnName();
                StringBuilder colTextBuilder = new StringBuilder();
                colTextBuilder.append(tableName).append(".").append(col.getColumnName()).append(" ");
                
                if (col.getColumnComment() != null && !col.getColumnComment().isEmpty()) {
                    colTextBuilder.append(col.getColumnComment()).append(" ");
                }
                
                // 添加表注释作为上下文
                if (tableMeta.getTableComment() != null && !tableMeta.getTableComment().isEmpty()) {
                    colTextBuilder.append(tableMeta.getTableComment());
                }
                
                String colText = colTextBuilder.toString().trim();
                columnEmbeddings.put(colKey, embeddingModel.embed(colText).content());
            }
        }
        
        // ✅ 保存到对应数据源的索引中
        tableEmbeddingsByDatasource.put(datasourceId, tableEmbeddings);
        columnEmbeddingsByDatasource.put(datasourceId, columnEmbeddings);
        
        log.info("数据源 {} 向量索引构建完成，表: {}, 字段: {}", datasourceId, tableEmbeddings.size(), columnEmbeddings.size());
    }
    
    /**
     * 检索指定数据源的相关表
     */
    public List<String> retrieveTopTables(String query, Long datasourceId, int topK) {
        // ✅ 设置当前查询(用于意图检测)
        this.currentQuery = query;
        
        // ⚠️ P0优化：L1 精确匹配缓存
        if (cacheService != null) {
            String cacheKey = String.format("%d:%s", datasourceId, query);
            List<String> cachedResult = cacheService.getVectorRetrieval(cacheKey);
            if (cachedResult != null && !cachedResult.isEmpty()) {
                log.info("[VectorRetriever] ✅ L1缓存命中(精确): datasourceId={}, query='{}', tables={}", 
                    datasourceId, query, cachedResult);
                return cachedResult;
            }
        }
        
        // ✅ 新增：L2 模糊匹配缓存（归一化后）
        if (cacheService != null) {
            String normalizedQuery = normalizeQuery(query);
            String fuzzyCacheKey = String.format("%d:%s", datasourceId, normalizedQuery);
            List<String> fuzzyCachedResult = cacheService.getFuzzyVectorRetrieval(fuzzyCacheKey);
            if (fuzzyCachedResult != null && !fuzzyCachedResult.isEmpty()) {
                log.info("[VectorRetriever] ✅ L2缓存命中(模糊): datasourceId={}, original='{}', normalized='{}', tables={}", 
                    datasourceId, query, normalizedQuery, fuzzyCachedResult);
                
                // 同时写入L1缓存，加速下次相同查询
                cacheService.putVectorRetrieval(String.format("%d:%s", datasourceId, query), fuzzyCachedResult);
                
                return fuzzyCachedResult;
            }
        }
        
        // ✅ 新增：L3 语义相似度匹配（阈值0.85）
        if (cacheService != null) {
            // ✅ 关键修复：使用归一化后的查询进行L3检索，避免人名/地名干扰
            String normalizedQuery = normalizeQuery(query);
            log.info("[VectorRetriever] 🔍 L3语义检索开始: query='{}', normalized='{}', datasourceId={}", 
                query, normalizedQuery, datasourceId);
            List<String> semanticResult = cacheService.findSimilarQueryBySemantic(normalizedQuery, datasourceId, 0.85);
            if (semanticResult != null && !semanticResult.isEmpty()) {
                log.info("[VectorRetriever] ✅ L3缓存命中(语义): datasourceId={}, query='{}', normalized='{}', tables={}", 
                    datasourceId, query, normalizedQuery, semanticResult);
                
                // 写入L1和L2缓存，加速后续查询
                cacheService.putVectorRetrieval(String.format("%d:%s", datasourceId, query), semanticResult);
                cacheService.putFuzzyVectorRetrieval(
                    String.format("%d:%s", datasourceId, normalizeQuery(query)), 
                    semanticResult
                );
                log.debug("[VectorRetriever] L3结果已同步到L1/L2缓存");
                
                return semanticResult;
            } else {
                log.info("[VectorRetriever] ⚠️ L3语义缓存未命中，继续执行向量检索");
            }
        }
        
        // ✅ 获取指定数据源的索引
        Map<String, Embedding> tableEmbeddings = tableEmbeddingsByDatasource.get(datasourceId);
        if (tableEmbeddings == null || tableEmbeddings.isEmpty()) {
            log.warn("[VectorRetriever] 数据源 {} 的向量索引不存在", datasourceId);
            return Collections.emptyList();
        }
        
        Embedding queryEmbedding = embeddingModel.embed(query).content();
        
        List<Map.Entry<String, Double>> similarities = new ArrayList<>();
        for (Map.Entry<String, Embedding> entry : tableEmbeddings.entrySet()) {
            double sim = cosineSimilarity(queryEmbedding.vectorAsList(), entry.getValue().vectorAsList());
            similarities.add(new AbstractMap.SimpleEntry<>(entry.getKey(), sim));
        }
        
        similarities.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        
        // 记录所有表的匹配详情（便于调试）
        log.info("向量检索匹配 [query={}]: {}", query, 
            similarities.stream().limit(10).map(e -> String.format("%s(%.3f)", e.getKey(), e.getValue())).collect(Collectors.joining(", ")));
        
        List<String> result = new ArrayList<>();
        
        // ✅ 动态阈值：根据查询长度和最高相似度自适应调整
        double similarityThreshold = calculateDynamicThreshold(query, similarities);
        log.info("[VectorRetriever] 动态阈值计算: query='{}', length={}, maxSimilarity={}, threshold={}",
            query, query.length(), 
            similarities.isEmpty() ? 0 : String.format("%.3f", similarities.get(0).getValue()),
            String.format("%.3f", similarityThreshold));
        
        // ✅ 优化策略：保底返回Top-5表 + 高置信度标记（不再硬性截断）
        int guaranteedCount = Math.min(similarities.size(), 5); // 保底至少返回5个表
        
        for (int i = 0; i < guaranteedCount; i++) {
            Map.Entry<String, Double> entry = similarities.get(i);
            result.add(entry.getKey());
            
            if (entry.getValue() >= similarityThreshold) {
                log.debug("[VectorRetriever] 高置信度表: {} (相似度: {})", 
                    entry.getKey(), String.format("%.3f", entry.getValue()));
            } else {
                log.debug("[VectorRetriever] 低置信度表(保底召回): {} (相似度: {} < 阈值: {})", 
                    entry.getKey(), String.format("%.3f", entry.getValue()), String.format("%.3f", similarityThreshold));
            }
        }
        
        // ⚠️ 保底机制：如果过滤后结果为空，强制返回Top-3（不管阈值）
        if (result.isEmpty() && !similarities.isEmpty()) {
            int fallbackCount = Math.min(similarities.size(), 3);
            for (int i = 0; i < fallbackCount; i++) {
                result.add(similarities.get(i).getKey());
            }
            log.warn("[VectorRetriever] 阈值过滤后结果为空，启用保底机制返回Top-{}表", fallbackCount);
        }
        
        if (result.isEmpty()) {
            log.warn("向量检索未找到任何相关表，请检查元数据是否已加载 (datasourceId={})", datasourceId);
        } else {
            log.info("[表召回] datasourceId={} 最终返回 {} 个表: {}", datasourceId, result.size(), result);
            
            // ⚠️ P0优化：L1 写入精确缓存
            if (cacheService != null && !result.isEmpty()) {
                String cacheKey = String.format("%d:%s", datasourceId, query);
                cacheService.putVectorRetrieval(cacheKey, result);
            }
            
            // ✅ 新增：L2 写入模糊缓存（归一化后）
            if (cacheService != null && !result.isEmpty()) {
                String normalizedQuery = normalizeQuery(query);
                String fuzzyCacheKey = String.format("%d:%s", datasourceId, normalizedQuery);
                cacheService.putFuzzyVectorRetrieval(fuzzyCacheKey, result);
                log.debug("[VectorRetriever] L2缓存写入: normalized={}", normalizedQuery);
            }
            
            // ✅ 新增：L3 记录到语义索引（Jaccard降级方案）
            // ⚠️ 注意：向量检索阶段还不知道用户评分，传null表示未评分，允许记录
            if (cacheService != null && !result.isEmpty()) {
                cacheService.recordQueryToSemanticIndex(datasourceId, query, result, null);
                log.debug("[VectorRetriever] Jaccard语义索引已更新: datasourceId={}, query='{}'", 
                    datasourceId, query);
            }
            
            // ✅ 新增：异步写入Chroma向量缓存（如果可用）
            if (queryCacheVectorService != null && queryCacheVectorService.isAvailable() && !result.isEmpty()) {
                // ✅ 关键修复：使用归一化后的查询写入Chroma，提高泛化能力
                final String normalizedQuery = normalizeQuery(query);
                final Long asyncDatasourceId = datasourceId;
                final List<String> asyncTables = new ArrayList<>(result);
                
                CompletableFuture.runAsync(() -> {
                    try {
                        log.info("[VectorRetriever] 💾 [异步] 开始写入Chroma查询缓存: query='{}', normalized='{}', datasourceId={}, tables={}", 
                            query, normalizedQuery, asyncDatasourceId, asyncTables);
                        
                        // 将表列表转换为JSON字符串
                        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                        String tablesJson = mapper.writeValueAsString(asyncTables);
                        
                        boolean success = queryCacheVectorService.addQueryToCache(normalizedQuery, asyncDatasourceId, tablesJson);
                        if (success) {
                            log.info("[VectorRetriever] ✅ [异步] Chroma查询缓存写入成功: normalized='{}', tables={}", 
                                normalizedQuery, asyncTables);
                        } else {
                            log.warn("[VectorRetriever] ⚠️ [异步] Chroma查询缓存写入返回false: normalized='{}'", normalizedQuery);
                        }
                    } catch (Exception e) {
                        log.warn("[VectorRetriever] ⚠️ [异步] Chroma查询缓存写入失败: normalized='{}', error={}", 
                            normalizedQuery, e.getMessage());
                    }
                });
            } else {
                if (queryCacheVectorService == null) {
                    log.debug("[VectorRetriever] QueryCacheVectorService未注入，跳过Chroma缓存写入");
                } else if (!queryCacheVectorService.isAvailable()) {
                    log.debug("[VectorRetriever] QueryCacheVectorService不可用，跳过Chroma缓存写入");
                }
            }
        }
        
        return result;
    }
    
    /**
     * 检索指定数据源的字段
     */
    public List<String> retrieveTopColumns(String query, Long datasourceId, List<String> tables, int topK) {
        // ✅ 获取指定数据源的列索引
        Map<String, Embedding> columnEmbeddings = columnEmbeddingsByDatasource.get(datasourceId);
        if (columnEmbeddings == null || columnEmbeddings.isEmpty()) {
            log.warn("[VectorRetriever] 数据源 {} 的列向量索引不存在", datasourceId);
            return Collections.emptyList();
        }
        
        Embedding queryEmbedding = embeddingModel.embed(query).content();
        
        List<Map.Entry<String, Double>> similarities = new ArrayList<>();
        for (String table : tables) {
            for (Map.Entry<String, Embedding> entry : columnEmbeddings.entrySet()) {
                if (entry.getKey().startsWith(table + ".")) {
                    double sim = cosineSimilarity(queryEmbedding.vectorAsList(), entry.getValue().vectorAsList());
                    similarities.add(new AbstractMap.SimpleEntry<>(entry.getKey(), sim));
                }
            }
        }
        
        similarities.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        
        List<String> result = new ArrayList<>();
        for (int i = 0; i < Math.min(similarities.size(), topK); i++) {
            result.add(similarities.get(i).getKey());
        }
        return result;
    }
    
    /**
     * ✅ 新增：动态计算相似度阈值
     * 
     * @param query 用户查询
     * @param similarities 所有表的相似度列表(已排序)
     * @return 动态阈值
     */
    private double calculateDynamicThreshold(String query, List<Map.Entry<String, Double>> similarities) {
        // ✅ 基础阈值提高: 0.45 → 0.50 (更严格过滤边缘相关表)
        double baseThreshold = 0.50;
        
        if (similarities.isEmpty()) {
            return baseThreshold;
        }
        
        // 1. 根据查询长度调整
        int queryLength = query.length();
        if (queryLength <= 5) {
            // 短查询：降低阈值，容忍度高
            baseThreshold -= 0.10;
        } else if (queryLength > 15) {
            // 长查询：提高阈值，更严格
            baseThreshold += 0.10;
        }
        
        // 2. 根据最高相似度调整
        double maxSimilarity = similarities.get(0).getValue();
        if (maxSimilarity > 0.7) {
            // 最高相似度高：说明匹配明确，可以降低阈值召回更多相关表
            baseThreshold -= 0.05;
        } else if (maxSimilarity < 0.4) {
            // 最高相似度低：说明匹配不明确，提高阈值避免误召回
            baseThreshold += 0.10;
        }
        
        // 3. 确保阈值在合理范围 [0.30, 0.60]
        return Math.max(0.30, Math.min(0.60, baseThreshold));
    }
    
    /**
     * ✅ 新增：查询文本归一化 - 去除可变实体，保留查询结构
     * 业界标准：https://help.aliyun.com/zh/polardb/polardb-for-mysql/llm-based-nl2sql
     */
    private String normalizeQuery(String query) {
        return QueryNormalizer.normalize(query);
    }
    
    private double cosineSimilarity(List<Float> vec1, List<Float> vec2) {
        if (vec1.size() != vec2.size()) {
            return 0.0;
        }
        
        double dotProduct = 0.0;
        double norm1 = 0.0;
        double norm2 = 0.0;
        
        for (int i = 0; i < vec1.size(); i++) {
            dotProduct += vec1.get(i) * vec2.get(i);
            norm1 += vec1.get(i) * vec1.get(i);
            norm2 += vec2.get(i) * vec2.get(i);
        }
        
        if (norm1 == 0 || norm2 == 0) {
            return 0.0;
        }
        
        return dotProduct / (Math.sqrt(norm1) * Math.sqrt(norm2));
    }
}
