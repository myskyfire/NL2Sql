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
 * Jina AI Reranker Service - 使用Jina Cloud API进行重排序
 * 
 * API文档: https://jina.ai/reranker/
 * 免费额度: 每月200万次请求
 */
@Slf4j
@Service
public class JinaReranker {
    
    private static final String JINA_RERANK_URL = "https://api.jina.ai/v1/rerank";
    
    @Value("${reranker.jina.api-key:}")
    private String apiKey;
    
    @Value("${reranker.jina.model:jina-reranker-v2-base-multilingual}")
    private String model;
    
    @Value("${reranker.top-k:5}")
    private int topK;
    
    @Value("${reranker.threshold:0.5}")
    private double threshold;
    
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    public JinaReranker() {
        // ✅ 创建支持代理的HttpClient
        this.httpClient = HttpClient.newBuilder()
            .proxy(java.net.ProxySelector.getDefault())  // 使用系统代理
            .build();
    }
    
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
        
        if (apiKey == null || apiKey.isEmpty()) {
            log.warn("[JinaReranker] API Key未配置，跳过重排序");
            return candidates.stream()
                .limit(topK)
                .map(doc -> new RerankedDocument(doc, 0.0, 0))
                .collect(Collectors.toList());
        }
        
        try {
            log.info("[JinaReranker] 开始重排序: query='{}', candidates={}", query, candidates.size());
            
            // 构建请求体
            String requestBody = objectMapper.writeValueAsString(
                java.util.Map.of(
                    "model", model,
                    "query", query,
                    "documents", candidates,
                    "top_n", candidates.size()
                )
            );
            
            // 发送HTTP请求
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
            
            // 解析响应
            JsonNode root = objectMapper.readTree(response.body());
            JsonNode resultsNode = root.get("results");
            
            if (resultsNode == null || !resultsNode.isArray()) {
                throw new RuntimeException("Invalid rerank response from Jina");
            }
            
            // 转换为RerankedDocument列表
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
            
            // 按分数降序排序
            ranked.sort(Comparator.comparingDouble(RerankedDocument::getRelevanceScore).reversed());
            
            // 取Top-K
            List<RerankedDocument> topKResults = ranked.stream()
                .limit(this.topK)
                .collect(Collectors.toList());
            
            log.info("[JinaReranker] 重排序完成: input={}, output={}, avgScore={}", 
                candidates.size(), topKResults.size(),
                String.format("%.3f", topKResults.isEmpty() ? 0 : topKResults.stream().mapToDouble(RerankedDocument::getRelevanceScore).average().orElse(0)));
            
            return topKResults;
            
        } catch (Exception e) {
            log.error("[JinaReranker] 重排序失败: {}", e.getMessage(), e);
            // 降级: 返回原始顺序
            return candidates.stream()
                .limit(topK)
                .map(doc -> new RerankedDocument(doc, 0.0, 0))
                .collect(Collectors.toList());
        }
    }
    
    /**
     * 重排序结果
     */
    @Data
    public static class RerankedDocument {
        private final String content;
        private final double relevanceScore;
        private final int originalPosition;
    }
}
