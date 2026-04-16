package com.nl2sql.core.retriever;

import com.nl2sql.core.cache.MetadataCacheService;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.allminilml6v2.AllMiniLmL6V2EmbeddingModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Component
public class VectorRetriever {
    
    @Autowired(required = false)
    private MetadataCacheService cacheService;
    
    private final EmbeddingModel embeddingModel;
    private final Map<String, Embedding> tableEmbeddings = new ConcurrentHashMap<>();
    private final Map<String, Embedding> columnEmbeddings = new ConcurrentHashMap<>();
    
    public VectorRetriever() {
        this.embeddingModel = new AllMiniLmL6V2EmbeddingModel();
    }
    
    @PostConstruct
    public void init() {
        log.info("向量检索器初始化完成");
    }
    
    public void buildIndex(Map<String, com.nl2sql.core.metadata.TableMetadata> metadata) {
        log.info("开始构建向量索引...");
        tableEmbeddings.clear();
        columnEmbeddings.clear();
        
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
        
        log.info("向量索引构建完成，表: {}, 字段: {}", tableEmbeddings.size(), columnEmbeddings.size());
    }
    
    public List<String> retrieveTopTables(String query, int topK) {
        // ⚠️ P0优化：尝试从缓存获取
        if (cacheService != null) {
            List<String> cachedResult = cacheService.getVectorRetrieval(query);
            if (cachedResult != null && !cachedResult.isEmpty()) {
                log.debug("[VectorRetriever] 缓存命中: {}", query);
                return cachedResult;
            }
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
                log.info("[VectorRetriever] 过滤低相关性表: {} (相似度: {:.3f} < 阈值: {:.3f})", 
                    entry.getKey(), entry.getValue(), similarityThreshold);
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
            log.warn("向量检索未找到任何相关表，请检查元数据是否已加载");
        } else {
            log.info("[表召回] 最终返回 {} 个表: {}", result.size(), result);
            
            // ⚠️ P0优化：写入缓存
            if (cacheService != null && !result.isEmpty()) {
                cacheService.putVectorRetrieval(query, result);
            }
        }
        
        return result;
    }
    
    public List<String> retrieveTopColumns(String query, List<String> tables, int topK) {
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
