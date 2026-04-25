package com.nl2sql.core.rag.provider;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.chroma.ChromaApiVersion;
import dev.langchain4j.store.embedding.chroma.ChromaEmbeddingStore;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Chroma向量数据库提供者实现
 */
@Slf4j
public class ChromaVectorProvider implements VectorStoreProvider {
    
    private final String baseUrl;
    private final String collectionName;
    private final int timeout;
    
    private EmbeddingStore<TextSegment> embeddingStore;
    private EmbeddingModel embeddingModel;
    private boolean available = false;
    
    public ChromaVectorProvider(String baseUrl, String collectionName, int timeout, EmbeddingModel embeddingModel) {
        this.baseUrl = baseUrl;
        this.collectionName = collectionName;
        this.timeout = timeout;
        this.embeddingModel = embeddingModel;
        initialize();
    }
    
    private void initialize() {
        try {
            log.info("初始化Chroma向量数据库: url={}, collection={}", baseUrl, collectionName);
            
            if (embeddingModel == null) {
                log.warn("[ChromaVectorProvider] EmbeddingModel未注入，Chroma不可用");
                this.available = false;
                return;
            }
            
            log.info("[ChromaVectorProvider] 使用Ollama嵌入模型: {}", embeddingModel.getClass().getSimpleName());
            
            // 使用 LangChain4j 原生 V2 API
            this.embeddingStore = ChromaEmbeddingStore.builder()
                .baseUrl(baseUrl)
                .collectionName(collectionName)
                .apiVersion(ChromaApiVersion.V2)
                .build();
            
            this.available = true;
            log.info("Chroma向量数据库初始化成功");
        } catch (Exception e) {
            log.warn("Chroma向量数据库初始化失败: {}", e.getMessage());
            this.available = false;
        }
    }
    
    @Override
    public String getName() {
        return "chroma";
    }
    
    @Override
    public boolean isAvailable() {
        return available && embeddingStore != null;
    }
    
    @Override
    public String addKnowledge(String question, String answer, String sqlExample, String category, float qualityScore) {
        if (!isAvailable()) {
            log.debug("Chroma不可用，跳过知识添加");
            return null;
        }
        
        try {
            Metadata metadata = new Metadata();
            metadata.put("answer", answer != null ? answer : "");
            metadata.put("sql_example", sqlExample != null ? sqlExample : "");
            metadata.put("category", category != null ? category : "");
            metadata.put("quality_score", qualityScore); // ✅ 使用传入的质量评分
            TextSegment segment = TextSegment.from(question, metadata);
            
            Embedding embedding = embeddingModel.embed(segment).content();
            String id = embeddingStore.add(embedding, segment);
            
            log.debug("添加知识到Chroma: question={}, qualityScore={}, id={}", question, qualityScore, id);
            return id;
        } catch (Exception e) {
            log.error("添加知识到Chroma失败: {}", e.getMessage(), e);
            return null;
        }
    }
    
    @Override
    public List<VectorSearchResult> searchSimilar(String question, int maxResults, double minScore) {
        if (!isAvailable()) {
            log.debug("Chroma不可用，跳过向量搜索");
            return new ArrayList<>();
        }
        
        try {
            log.debug("Chroma向量搜索: question={}, maxResults={}, minScore={}", 
                question, maxResults, minScore);
            
            Embedding queryEmbedding = embeddingModel.embed(question).content();
            
            dev.langchain4j.store.embedding.EmbeddingSearchRequest request = 
                dev.langchain4j.store.embedding.EmbeddingSearchRequest.builder()
                    .queryEmbedding(queryEmbedding)
                    .maxResults(maxResults)
                    .minScore(minScore)
                    .build();
            
            dev.langchain4j.store.embedding.EmbeddingSearchResult<TextSegment> searchResult = 
                embeddingStore.search(request);
            List<EmbeddingMatch<TextSegment>> matches = searchResult.matches();
            
            List<VectorSearchResult> results = new ArrayList<>();
            for (EmbeddingMatch<TextSegment> match : matches) {
                VectorSearchResult result = new VectorSearchResult();
                result.setQuestion(match.embedded().text());
                
                Metadata metadata = match.embedded().metadata();
                result.setAnswer(metadata.getString("answer"));
                result.setSqlExample(metadata.getString("sql_example"));
                result.setCategory(metadata.getString("category"));
                result.setScore(match.score());
                
                // ✅ 从 Chroma metadata 读取 quality_score（兼容 Double/String 类型）
                try {
                    String qualityScoreStr = metadata.getString("quality_score");
                    if (qualityScoreStr != null) {
                        result.setQualityScore(Float.parseFloat(qualityScoreStr));
                    } else {
                        result.setQualityScore(0.9f); // 历史数据默认高质量
                    }
                } catch (Exception e) {
                    // getString失败时，尝试其他方法获取
                    log.debug("[ChromaVectorProvider] quality_score读取异常，使用默认值: {}", e.getMessage());
                    result.setQualityScore(0.9f);
                }
                
                results.add(result);
            }
            
            log.debug("Chroma向量搜索完成: found={} items", results.size());
            return results;
            
        } catch (Exception e) {
            log.error("Chroma向量搜索失败: {}", e.getMessage(), e);
            return new ArrayList<>();
        }
    }
    
    @Override
    public void clearAll() {
        if (!isAvailable()) {
            return;
        }
        
        try {
            log.info("清空Chroma集合: {}", collectionName);
            // Chroma不支持直接清空集合，需要删除后重建
            log.warn("Chroma清空操作需要通过管理界面进行");
        } catch (Exception e) {
            log.error("清空Chroma失败: {}", e.getMessage(), e);
        }
    }
    
    @Override
    public Map<String, Object> getConfig() {
        Map<String, Object> config = new HashMap<>();
        config.put("baseUrl", baseUrl);
        config.put("collectionName", collectionName);
        config.put("timeout", timeout);
        config.put("available", available);
        return config;
    }
}
