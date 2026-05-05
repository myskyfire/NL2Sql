package com.nl2sql.core.rerank;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Cross-Encoder Reranker - 使用 Ollama bge-reranker 进行本地精排
 * 
 * 工作原理:
 * 1. 接收 Query 和候选文档列表
 * 2. 将 (Query, Doc) 对送入 Cross-Encoder 模型
 * 3. 模型输出相关性分数 (0-1)
 * 4. 按分数降序排序，返回 Top-K
 */
@Slf4j
public class CrossEncoderReranker implements Reranker {
    
    private final String baseUrl;
    private final String rerankerModel;
    private final int topK;
    private final double threshold;
    
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    public CrossEncoderReranker(String baseUrl, String rerankerModel, int topK, double threshold) {
        this.baseUrl = baseUrl;
        this.rerankerModel = rerankerModel;
        this.topK = topK;
        this.threshold = threshold;
    }
    
    @Override
    public String getName() {
        return "cross-encoder";
    }
    
    @Override
    public boolean isAvailable() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/tags"))
                .GET()
                .build();
            HttpResponse<String> response = httpClient.send(
                request, HttpResponse.BodyHandlers.ofString()
            );
            return response.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }
    
    @Override
    public List<RerankedDocument> rerank(String query, List<String> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return new ArrayList<>();
        }
        
        try {
            log.info("[CrossEncoderReranker] 开始重排序: query='{}', candidates={}", query, candidates.size());
            
            List<QueryDocPair> pairs = new ArrayList<>();
            for (int i = 0; i < candidates.size(); i++) {
                pairs.add(new QueryDocPair(query, candidates.get(i), i));
            }
            
            List<RerankResult> results = batchRerank(pairs);
            
            results.sort(Comparator.comparingDouble(RerankResult::getScore).reversed());
            
            List<RerankedDocument> ranked = results.stream()
                .filter(r -> r.getScore() >= threshold)
                .limit(topK)
                .map(r -> new RerankedDocument(
                    candidates.get(r.getOriginalIndex()),
                    r.getScore(),
                    r.getOriginalIndex()
                ))
                .collect(Collectors.toList());
            
            log.info("[CrossEncoderReranker] 重排序完成: input={}, output={}, avgScore={}", 
                candidates.size(), ranked.size(),
                String.format("%.3f", ranked.isEmpty() ? 0 : ranked.stream().mapToDouble(RerankedDocument::getRelevanceScore).average().orElse(0)));
            
            return ranked;
            
        } catch (Exception e) {
            log.error("[CrossEncoderReranker] 重排序失败: {}", e.getMessage(), e);
            return candidates.stream()
                .limit(topK)
                .map(doc -> new RerankedDocument(doc, 0.0, 0))
                .collect(Collectors.toList());
        }
    }
    
    private List<RerankResult> batchRerank(List<QueryDocPair> pairs) throws Exception {
        String url = baseUrl + "/api/rerank";
        
        List<List<String>> documents = pairs.stream()
            .map(p -> List.of(p.query, p.document))
            .collect(Collectors.toList());
        
        String requestBody = objectMapper.writeValueAsString(
            java.util.Map.of(
                "model", rerankerModel,
                "documents", documents
            )
        );
        
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
    
    private static class RerankResult {
        private final int originalIndex;
        private final double score;
        
        RerankResult(int originalIndex, double score) {
            this.originalIndex = originalIndex;
            this.score = score;
        }
        
        public int getOriginalIndex() {
            return originalIndex;
        }
        
        public double getScore() {
            return score;
        }
    }
}
