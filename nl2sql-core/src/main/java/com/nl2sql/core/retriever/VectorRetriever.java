package com.nl2sql.core.retriever;

import com.nl2sql.core.cache.MetadataCacheService;
import com.nl2sql.core.cache.QueryCacheVectorService;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.allminilml6v2.AllMiniLmL6V2EmbeddingModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Component
public class VectorRetriever {
    
    @Autowired(required = false)
    private MetadataCacheService cacheService;
    
    @Autowired(required = false)
    private QueryCacheVectorService queryCacheVectorService;
    
    private final EmbeddingModel embeddingModel;
    // ✅ 按数据源隔离的向量索引: datasourceId -> (tableName -> Embedding)
    private final Map<Long, Map<String, Embedding>> tableEmbeddingsByDatasource = new ConcurrentHashMap<>();
    private final Map<Long, Map<String, Embedding>> columnEmbeddingsByDatasource = new ConcurrentHashMap<>();
    
    public VectorRetriever() {
        this.embeddingModel = new AllMiniLmL6V2EmbeddingModel();
    }
    
    @PostConstruct
    public void init() {
        log.info("向量检索器初始化完成");
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
            log.info("[VectorRetriever] 🔍 L3语义检索开始: query='{}', datasourceId={}", query, datasourceId);
            List<String> semanticResult = cacheService.findSimilarQueryBySemantic(query, datasourceId, 0.85);
            if (semanticResult != null && !semanticResult.isEmpty()) {
                log.info("[VectorRetriever] ✅ L3缓存命中(语义): datasourceId={}, query='{}', tables={}", 
                    datasourceId, query, semanticResult);
                
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
        
        // ✅ 优化：固定返回Top-10表 + 相似度阈值过滤（保底机制）
        int maxTables = Math.min(similarities.size(), 10); // 提高到10个表，避免遗漏关键表
        double similarityThreshold = 0.15; // 降低阈值，避免误杀
        
        for (int i = 0; i < maxTables; i++) {
            Map.Entry<String, Double> entry = similarities.get(i);
            if (entry.getValue() >= similarityThreshold) {
                result.add(entry.getKey());
            } else {
                log.info("[VectorRetriever] 过滤低相关性表: {} (相似度: {} < 阈值: {})", 
                    entry.getKey(), String.format("%.3f", entry.getValue()), String.format("%.3f", similarityThreshold));
                break; // 由于已排序，后续表的相关性更低，直接跳出
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
            if (cacheService != null && !result.isEmpty()) {
                cacheService.recordQueryToSemanticIndex(datasourceId, query, result);
                log.debug("[VectorRetriever] Jaccard语义索引已更新: datasourceId={}, query='{}'", 
                    datasourceId, query);
            }
            
            // ✅ 新增：自动写入Chroma向量缓存（如果可用）
            if (queryCacheVectorService != null && queryCacheVectorService.isAvailable() && !result.isEmpty()) {
                try {
                    log.info("[VectorRetriever] 💾 开始写入Chroma查询缓存: query='{}', datasourceId={}, tables={}", 
                        query, datasourceId, result);
                    
                    // 将表列表转换为JSON字符串
                    com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                    String tablesJson = mapper.writeValueAsString(result);
                    
                    boolean success = queryCacheVectorService.addQueryToCache(query, datasourceId, tablesJson);
                    if (success) {
                        log.info("[VectorRetriever] ✅ Chroma查询缓存写入成功: query='{}', tables={}", 
                            query, result);
                    } else {
                        log.warn("[VectorRetriever] ⚠️ Chroma查询缓存写入返回false: query='{}'", query);
                    }
                } catch (Exception e) {
                    log.warn("[VectorRetriever] ⚠️ Chroma查询缓存写入失败: query='{}', error={}", 
                        query, e.getMessage());
                }
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
     * ✅ 新增：查询文本归一化 - 去除可变实体，保留查询结构
     */
    private String normalizeQuery(String query) {
        if (query == null || query.isEmpty()) {
            return query;
        }
        
        String normalized = query;
        
        // 1. 去除人名（中文2-4字姓名 + 的/先生/女士等后缀）
        normalized = normalized.replaceAll("[\\u4e00-\\u9fa5]{2,4}(?=的|先生|女士|同学|老师|经理|总)", "{PERSON}");
        
        // 2. 去除地名（省市县）
        String[] provinces = {"北京", "上海", "天津", "重庆", "广东", "江苏", "浙江", "四川", "湖南", "湖北", 
                             "河南", "河北", "山东", "山西", "陕西", "安徽", "福建", "江西", "辽宁", "黑龙江", 
                             "吉林", "甘肃", "青海", "云南", "贵州", "海南", "台湾", "内蒙古", "广西", "宁夏", 
                             "新疆", "西藏"};
        for (String province : provinces) {
            normalized = normalized.replaceAll(province + "(省|市|自治区|地区|县)?", "{LOCATION}");
        }
        
        // 3. 去除时间（日期、月份）
        normalized = normalized.replaceAll("\\d{4}年\\d{1,2}月?", "{DATE}");
        normalized = normalized.replaceAll("\\d{4}-\\d{2}-\\d{2}", "{DATE}");
        normalized = normalized.replaceAll("\\d{4}/\\d{1,2}/\\d{1,2}", "{DATE}");
        
        // 4. 去除数字ID
        normalized = normalized.replaceAll("ID[为是]?\\d+", "ID{NUM}");
        normalized = normalized.replaceAll("编号[为是]?\\w+", "编号{NUM}");
        normalized = normalized.replaceAll("订单号[为是]?\\w+", "订单号{NUM}");
        
        // 5. 去除具体金额
        normalized = normalized.replaceAll("\\d+[万千元亿]?(?:以上|以下|之间)?", "{AMOUNT}");
        
        // 6. 去除商品品牌/型号（常见品牌）
        String[] brands = {"苹果", "华为", "小米", "OPPO", "vivo", "三星", "索尼", "海尔", "美的", "格力",
                          "联想", "戴尔", "惠普", "华硕", "ThinkPad", "MacBook", "iPhone", "iPad"};
        for (String brand : brands) {
            normalized = normalized.replaceAll(brand, "{BRAND}");
        }
        
        return normalized.trim();
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
