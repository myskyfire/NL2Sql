package com.nl2sql.core.rag;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.allminilml6v2.AllMiniLmL6V2EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.chroma.ChromaEmbeddingStore;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Chroma向量数据库服务 - 用于RAG知识库的向量检索
 */
@Slf4j
@Service
public class ChromaVectorService {
    
    @Value("${chroma.url:http://localhost:8000}")
    private String chromaUrl;
    
    @Value("${chroma.collection-name:nlp2sql_rag}")
    private String collectionName;
    
    private EmbeddingStore<TextSegment> embeddingStore;
    private EmbeddingModel embeddingModel;
    
    @PostConstruct
    public void init() {
        try {
            log.info("初始化Chroma向量数据库: url={}, collection={}", chromaUrl, collectionName);
            
            // 初始化Embedding模型
            this.embeddingModel = new AllMiniLmL6V2EmbeddingModel();
            
            // 初始化Chroma向量存储
            this.embeddingStore = ChromaEmbeddingStore.builder()
                .baseUrl(chromaUrl)
                .collectionName(collectionName)
                .build();
            
            log.info("Chroma向量数据库初始化成功");
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
        // ✅ 防御性检查：如果 Chroma 未初始化，静默跳过
        if (embeddingStore == null) {
            log.debug("[ChromaVectorService] Chroma 未初始化，跳过知识添加");
            return;
        }
        
        try {
            // 创建文本片段
            dev.langchain4j.data.document.Metadata metadata = new dev.langchain4j.data.document.Metadata();
            metadata.put("answer", answer != null ? answer : "");
            metadata.put("sql_example", sqlExample != null ? sqlExample : "");
            metadata.put("category", category != null ? category : "");
            TextSegment segment = TextSegment.from(question, metadata);
            
            // 生成向量
            Embedding embedding = embeddingModel.embed(segment).content();
            
            // 存储到Chroma
            embeddingStore.add(embedding, segment);
            
            log.debug("添加知识到Chroma: question={}", question);
        } catch (Exception e) {
            log.error("添加知识到Chroma失败: {}", e.getMessage(), e);
            // ✅ 降级处理：不抛出异常，避免影响主流程
        }
    }
    
    /**
     * 搜索相似问题
     * 
     * @param question 查询问题
     * @param maxResults 最大返回结果数
     * @param minScore 最小相似度分数(0-1)
     * @return 相似结果列表
     */
    public List<RagResult> searchSimilar(String question, int maxResults, double minScore) {
        // ✅ 防御性检查：如果 Chroma 未初始化，返回空列表
        if (embeddingStore == null) {
            log.debug("[ChromaVectorService] Chroma 未初始化，跳过向量搜索");
            return new ArrayList<>();
        }
        
        try {
            log.debug("Chroma向量搜索: question={}, maxResults={}, minScore={}", 
                question, maxResults, minScore);
            
            // 生成查询向量
            Embedding queryEmbedding = embeddingModel.embed(question).content();
            
            // 执行相似度搜索
            dev.langchain4j.store.embedding.EmbeddingSearchRequest request = 
                dev.langchain4j.store.embedding.EmbeddingSearchRequest.builder()
                    .queryEmbedding(queryEmbedding)
                    .maxResults(maxResults)
                    .minScore(minScore)
                    .build();
            
            dev.langchain4j.store.embedding.EmbeddingSearchResult<TextSegment> searchResult = 
                embeddingStore.search(request);
            List<EmbeddingMatch<TextSegment>> matches = searchResult.matches();
            
            // 转换结果
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
            
            log.info("Chroma向量搜索完成: found={} items", results.size());
            return results;
            
        } catch (Exception e) {
            log.error("Chroma向量搜索失败: {}", e.getMessage(), e);
            // ✅ 降级处理：返回空列表而不是抛出异常
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
