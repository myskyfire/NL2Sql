package com.nl2sql.core.rerank;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Cross-Encoder Reranker Service - 使用Ollama bge-reranker进行精排
 * 
 * 工作原理:
 * 1. 接收Query和候选文档列表
 * 2. 将(Query, Doc)对送入Cross-Encoder模型
 * 3. 模型输出相关性分数(0-1)
 * 4. 按分数降序排序,返回Top-K
 */
@Slf4j
@Service
public class CrossEncoderReranker {
    
    @Value("${ollama.base-url:http://localhost:11434}")
    private String baseUrl;
    
    @Value("${reranker.model:bge-reranker-v2-m3}")
    private String rerankerModel;
    
    @Value("${reranker.top-k:5}")
    private int topK;
    
    @Value("${reranker.threshold:0.5}")
    private double threshold;
    
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    /**
     * 重排序主方法
     * 
     * @param query 用户查询
     * @param candidates 候选文档列表
     * @return 重排序后的Top-K文档
     */
    public List<RerankedDocument> rerank(String query, List<String> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return new ArrayList<>();
        }
        
        try {
            log.info("[Reranker] 开始重排序: query='{}', candidates={}", query, candidates.size());
            
            // 构建(Query, Doc)对
            List<QueryDocPair> pairs = new ArrayList<>();
            for (int i = 0; i < candidates.size(); i++) {
                pairs.add(new QueryDocPair(query, candidates.get(i), i));
            }
            
            // 批量调用Ollama Reranker API
            List<RerankResult> results = batchRerank(pairs);
            
            // 按分数降序排序
            results.sort(Comparator.comparingDouble(RerankResult::getScore).reversed());
            
            // 阈值过滤 + Top-K
            List<RerankedDocument> ranked = results.stream()
                .filter(r -> r.getScore() >= threshold)
                .limit(topK)
                .map(r -> new RerankedDocument(
                    candidates.get(r.getOriginalIndex()),
                    r.getScore(),
                    r.getOriginalIndex()
                ))
                .collect(Collectors.toList());
            
            log.info("[Reranker] 重排序完成: input={}, output={}, avgScore={}", 
                candidates.size(), ranked.size(),
                String.format("%.3f", ranked.isEmpty() ? 0 : ranked.stream().mapToDouble(RerankedDocument::getRelevanceScore).average().orElse(0)));
            
            return ranked;
            
        } catch (Exception e) {
            log.error("[Reranker] 重排序失败: {}", e.getMessage(), e);
            // 降级: 返回原始顺序
            return candidates.stream()
                .limit(topK)
                .map(doc -> new RerankedDocument(doc, 0.0, 0))
                .collect(Collectors.toList());
        }
    }
    
    /**
     * 批量重排序
     */
    private List<RerankResult> batchRerank(List<QueryDocPair> pairs) throws Exception {
        String url = baseUrl + "/api/rerank";
        
        // 构建请求体
        List<List<String>> documents = pairs.stream()
            .map(p -> List.of(p.query, p.document))
            .collect(Collectors.toList());
        
        String requestBody = objectMapper.writeValueAsString(
            java.util.Map.of(
                "model", rerankerModel,
                "documents", documents
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
            throw new RuntimeException("Ollama Rerank API error: " + response.body());
        }
        
        // 解析响应
        JsonNode jsonNode = objectMapper.readTree(response.body());
        JsonNode resultsNode = jsonNode.get("results");
        
        if (resultsNode == null || !resultsNode.isArray()) {
            throw new RuntimeException("Invalid rerank response from Ollama");
        }
        
        List<RerankResult> results = new ArrayList<>();
        for (int i = 0; i < resultsNode.size(); i++) {
            JsonNode resultNode = resultsNode.get(i);
            double score = resultNode.get("score").asDouble();
            results.add(new RerankResult(pairs.get(i).originalIndex, score));
        }
        
        return results;
    }
    
    // ==================== 内部类 ====================
    
    @Data
    private static class QueryDocPair {
        private final String query;
        private final String document;
        private final int originalIndex;
        
        QueryDocPair(String query, String document, int index) {
            this.query = query;
            this.document = document;
            this.originalIndex = index;
        }
    }
    
    @Data
    private static class RerankResult {
        private final int originalIndex;
        private final double score;
    }
    
    @Data
    public static class RerankedDocument {
        private final String content;
        private final double relevanceScore;
        private final int originalPosition;
    }
}
