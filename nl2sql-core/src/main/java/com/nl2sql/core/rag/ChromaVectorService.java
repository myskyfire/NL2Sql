package com.nl2sql.core.rag;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.allminilml6v2.AllMiniLmL6V2EmbeddingModel;
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
import java.util.stream.Collectors;

/**
 * Chroma向量数据库服务 - 用于RAG知识库的向量检索
 * ✅ 使用 LangChain4j 原生 Chroma V2 API 支持
 */
@Slf4j
@Service
public class ChromaVectorService {
    
    @Value("${chroma.enabled:false}")
    private boolean chromaEnabled;
    
    @Value("${chroma.url:http://localhost:8000}")
    private String chromaUrl;
    
    @Value("${chroma.collection-name:NL2SQL_rag}")
    private String collectionName;
    
    private EmbeddingStore<TextSegment> embeddingStore;
    
    @Autowired(required = false)
    private EmbeddingModel embeddingModel; // 注入OllamaEmbeddingProvider
    
    @PostConstruct
    public void init() {
        if (!chromaEnabled) {
            log.info("[ChromaVectorService] Chroma 已禁用，将使用 MySQL RAG");
            return;
        }
        
        try {
            log.info("初始化Chroma向量数据库 (V2 API): url={}, collection={}", chromaUrl, collectionName);
            
            // ✅ 使用注入的OllamaEmbeddingProvider (支持配置切换模型)
            if (embeddingModel == null) {
                log.warn("[ChromaVectorService] EmbeddingModel未注入，Chroma不可用");
                return;
            }
            
            log.info("[ChromaVectorService] 使用Ollama嵌入模型: {}", embeddingModel.getClass().getSimpleName());
            
            // ✅ 使用 LangChain4j 原生 V2 API
            this.embeddingStore = ChromaEmbeddingStore.builder()
                .baseUrl(chromaUrl)
                .collectionName(collectionName)
                .apiVersion(ChromaApiVersion.V2)  // ⚠️ 关键：指定使用 V2 API
                .build();
            
            log.info("Chroma V2 向量数据库初始化成功");
        } catch (Exception e) {
            log.warn("Chroma向量数据库不可用: {}", e.getMessage());
            log.info("将使用MySQL全文检索作为RAG后端");
            // 不抛出异常,允许Spring继续启动
        }
    }
    
    /**
     * 添加知识到向量库
     */
    public void addKnowledge(String question, String answer, String sqlExample, String category) {
        if (embeddingStore == null) {
            log.debug("[ChromaVectorService] Chroma 未初始化，跳过知识添加");
            return;
        }
        
        try {
            dev.langchain4j.data.document.Metadata metadata = new dev.langchain4j.data.document.Metadata();
            metadata.put("answer", answer != null ? answer : "");
            metadata.put("sql_example", sqlExample != null ? sqlExample : "");
            metadata.put("category", category != null ? category : "");
            TextSegment segment = TextSegment.from(question, metadata);
            
            Embedding embedding = embeddingModel.embed(segment).content();
            embeddingStore.add(embedding, segment);
            
            log.debug("添加知识到Chroma V2: question={}", question);
        } catch (Exception e) {
            log.error("添加知识到Chroma失败: {}", e.getMessage(), e);
        }
    }
    
    /**
     * 搜索相似问题
     */
    public List<RagResult> searchSimilar(String question, int maxResults, double minScore) {
        if (embeddingStore == null) {
            log.debug("[ChromaVectorService] Chroma 未初始化，跳过向量搜索");
            return new ArrayList<>();
        }
        
        try {
            log.debug("Chroma V2 向量搜索: question={}, maxResults={}, minScore={}", 
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
            
            List<RagResult> results = new ArrayList<>();
            for (EmbeddingMatch<TextSegment> match : matches) {
                RagResult result = new RagResult();
                result.setQuestion(match.embedded().text());
                
                Metadata metadata = match.embedded().metadata();
                result.setAnswer(metadata.getString("answer"));
                result.setSqlExample(metadata.getString("sql_example"));
                result.setCategory(metadata.getString("category"));
                result.setScore(match.score());
                
                results.add(result);
            }
            
            log.info("Chroma V2 向量搜索完成: found={} items", results.size());
            return results;
            
        } catch (Exception e) {
            log.error("Chroma向量搜索失败: {}", e.getMessage(), e);
            return new ArrayList<>();
        }
    }
    
    /**
     * 删除所有知识
     */
    public void clearAll() {
        try {
            log.info("清空Chroma集合: {}", collectionName);
            // Chroma不支持直接清空集合，需要删除后重建
            // 这里暂时不实现，可以通过管理界面操作
            log.warn("Chroma清空操作需要通过管理界面进行");
        } catch (Exception e) {
            log.error("清空Chroma失败: {}", e.getMessage(), e);
        }
    }
    
    /**
     * RAG搜索结果
     */
    @Data
    public static class RagResult {
        private String question;
        private String answer;
        private String sqlExample;
        private String category;
        private double score;
    }
}
