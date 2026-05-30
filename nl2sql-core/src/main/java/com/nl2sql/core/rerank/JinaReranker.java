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
 * Jina AI Reranker - 使用 Jina Cloud API 进行重排序
 * 
 * API文档: https://jina.ai/reranker/
 * 免费额度: 每月200万次请求
 */
@Slf4j
public class JinaReranker implements Reranker {
    
    private static final String JINA_RERANK_URL = "https://api.jina.ai/v1/rerank";
    
    private final String apiKey;
    private final String model;
    private final int topK;
    private final double threshold;
    
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    public JinaReranker(String apiKey, String model, int topK, double threshold) {
        this.apiKey = apiKey;
        this.model = model;
        this.topK = topK;
        this.threshold = threshold;
        this.httpClient = HttpClient.newBuilder()
            .proxy(java.net.ProxySelector.getDefault())
            .build();
    }
    
    @Override
    public String getName() {
        return "jina";
    }
    
    @Override
    public boolean isAvailable() {
        return apiKey != null && !apiKey.isEmpty();
    }
    
    @Override
    public List<RerankedDocument> rerank(String query, List<String> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return new ArrayList<>();
        }
        
        if (!isAvailable()) {
            log.warn("[JinaReranker] API Key未配置，跳过重排序");
            return candidates.stream()
                .limit(topK)
                .map(doc -> new RerankedDocument(doc, 0.0, 0))
                .collect(Collectors.toList());
        }
        
        try {
            log.info("[JinaReranker] 开始重排序: query='{}', candidates={}", query, candidates.size());
            
            String requestBody = objectMapper.writeValueAsString(
                java.util.Map.of(
                    "model", model,
                    "query", query,
                    "documents", candidates,
                    "top_n", candidates.size()
                )
            );
            
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(JINA_RERANK_URL))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();
            
            HttpResponse<String> response = httpClient.send(
                request, 
                HttpResponse.BodyHandlers.ofString()
            );
            
            if (response.statusCode() != 200) {
                throw new RuntimeException("Jina API error: " + response.body());
            }
            
            JsonNode root = objectMapper.readTree(response.body());
            JsonNode resultsNode = root.get("results");
            
            if (resultsNode == null || !resultsNode.isArray()) {
                throw new RuntimeException("Invalid rerank response from Jina");
            }
            
            List<RerankedDocument> ranked = new ArrayList<>();
            for (JsonNode result : resultsNode) {
                int index = result.get("index").asInt();
                double score = result.get("relevance_score").asDouble();
                
                if (score >= threshold) {
                    ranked.add(new RerankedDocument(
                        candidates.get(index),
                        score,
                        index
                    ));
                }
            }
            
            ranked.sort(Comparator.comparingDouble(RerankedDocument::getRelevanceScore).reversed());
            
            List<RerankedDocument> topKResults = ranked.stream()
                .limit(this.topK)
                .collect(Collectors.toList());
            
            log.info("[JinaReranker] 重排序完成: input={}, output={}, avgScore={}", 
                candidates.size(), topKResults.size(),
                String.format("%.3f", topKResults.isEmpty() ? 0 : topKResults.stream().mapToDouble(RerankedDocument::getRelevanceScore).average().orElse(0)));
            
            return topKResults;
            
        } catch (Exception e) {
            log.error("[JinaReranker] 重排序失败: {}", e.getMessage(), e);
            return candidates.stream()
                .limit(topK)
                .map(doc -> new RerankedDocument(doc, 0.0, 0))
                .collect(Collectors.toList());
        }
    }
}
