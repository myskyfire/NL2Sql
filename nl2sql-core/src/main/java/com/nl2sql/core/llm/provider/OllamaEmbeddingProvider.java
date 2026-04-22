package com.nl2sql.core.llm.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

/**
 * Ollama Embedding Provider - 支持多种嵌入模型
 * 
 * 支持的模型:
 * - bge-m3: BAAI多语言模型,1024维,中文SOTA (推荐)
 * - text-embedding-ada-002: OpenAI标准模型,1536维
 * - m3e-base: MokaAI中文模型,768维
 * - nomic-embed-text: Nomic AI模型,768维
 */
@Slf4j
@Component
public class OllamaEmbeddingProvider implements EmbeddingModel {
    
    @Value("${ollama.base-url:http://localhost:11434}")
    private String baseUrl;
    
    @Value("${ollama.embedding-model:bge-m3}")
    private String embeddingModel;  // ✅ 从配置文件读取,支持动态切换
    
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    /**
     * 生成单个文本的向量
     */
    @Override
    public dev.langchain4j.model.output.Response<Embedding> embed(String text) {
        try {
            log.debug("[OllamaEmbedding] 生成向量: model={}, textLength={}", embeddingModel, text.length());
            
            List<Float> vector = generateEmbedding(text);
            Embedding embedding = Embedding.from(vector);
            
            return dev.langchain4j.model.output.Response.from(embedding);
            
        } catch (Exception e) {
            log.error("[OllamaEmbedding] 向量生成失败: {}", e.getMessage(), e);
            throw new RuntimeException("Ollama Embedding failed: " + e.getMessage(), e);
        }
    }
    
    /**
     * 批量生成向量（默认实现：逐个调用）
     */
    @Override
    public dev.langchain4j.model.output.Response<List<Embedding>> embedAll(List<TextSegment> textSegments) {
        List<Embedding> embeddings = new ArrayList<>();
        for (TextSegment segment : textSegments) {
            embeddings.add(embed(segment.text()).content());
        }
        return dev.langchain4j.model.output.Response.from(embeddings);
    }
    
    /**
     * 调用Ollama API生成向量
     */
    private List<Float> generateEmbedding(String text) throws Exception {
        String url = baseUrl + "/api/embeddings";
        
        // 构建请求体
        String requestBody = objectMapper.writeValueAsString(
            java.util.Map.of(
                "model", embeddingModel,
                "prompt", text
            )
        );
        
        // 发送HTTP请求
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .build();
        
        HttpResponse<String> response = httpClient.send(
            request, 
            HttpResponse.BodyHandlers.ofString()
        );
        
        if (response.statusCode() != 200) {
            throw new RuntimeException("Ollama API error: " + response.body());
        }
        
        // 解析响应
        JsonNode jsonNode = objectMapper.readTree(response.body());
        JsonNode embeddingNode = jsonNode.get("embedding");
        
        if (embeddingNode == null || !embeddingNode.isArray()) {
            throw new RuntimeException("Invalid embedding response from Ollama");
        }
        
        // 转换为List<Float>
        List<Float> vector = new ArrayList<>();
        for (JsonNode node : embeddingNode) {
            vector.add((float) node.asDouble());
        }
        
        log.debug("[OllamaEmbedding] 向量生成成功: dimension={}", vector.size());
        return vector;
    }
}
