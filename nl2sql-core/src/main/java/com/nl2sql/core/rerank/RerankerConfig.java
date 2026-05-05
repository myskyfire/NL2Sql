package com.nl2sql.core.rerank;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Reranker 配置工厂
 * 根据 application.yml 中的 reranker.provider 配置，自动选择对应的 Reranker 实现
 * 
 * 支持的 provider:
 * - jina: 使用 Jina Cloud API（需要 API Key）
 * - cross-encoder: 使用 Ollama 本地 bge-reranker 模型（内网部署推荐）
 * - none: 不启用重排序
 */
@Slf4j
@Configuration
public class RerankerConfig {
    
    @Value("${reranker.provider:none}")
    private String provider;
    
    @Value("${reranker.top-k:5}")
    private int topK;
    
    @Value("${reranker.threshold:0.5}")
    private double threshold;
    
    @Value("${reranker.jina.api-key:}")
    private String jinaApiKey;
    
    @Value("${reranker.jina.model:jina-reranker-v2-base-multilingual}")
    private String jinaModel;
    
    @Value("${ollama.base-url:http://localhost:11434}")
    private String ollamaBaseUrl;
    
    @Value("${reranker.cross-encoder.model:bge-reranker-v2-m3}")
    private String crossEncoderModel;
    
    @Bean
    public Reranker reranker() {
        log.info("[RerankerConfig] 初始化 Reranker, provider={}", provider);
        
        switch (provider.toLowerCase()) {
            case "jina":
                return createJinaReranker();
            case "cross-encoder":
            case "ollama":
                return createCrossEncoderReranker();
            case "none":
            default:
                log.info("[RerankerConfig] Reranker 未启用 (provider=none)");
                return new DisabledReranker();
        }
    }
    
    private Reranker createJinaReranker() {
        log.info("[RerankerConfig] 创建 Jina Reranker, model={}", jinaModel);
        return new JinaReranker(jinaApiKey, jinaModel, topK, threshold);
    }
    
    private Reranker createCrossEncoderReranker() {
        log.info("[RerankerConfig] 创建 Cross-Encoder Reranker, model={}, url={}", 
            crossEncoderModel, ollamaBaseUrl);
        return new CrossEncoderReranker(ollamaBaseUrl, crossEncoderModel, topK, threshold);
    }
    
    /**
     * 空实现 Reranker，用于未启用重排序时降级
     */
    static class DisabledReranker implements Reranker {
        
        @Override
        public String getName() {
            return "none";
        }
        
        @Override
        public boolean isAvailable() {
            return false;
        }
        
        @Override
        public java.util.List<RerankedDocument> rerank(String query, java.util.List<String> candidates) {
            return candidates.stream()
                .limit(5)
                .map(doc -> new RerankedDocument(doc, 0.0, 0))
                .collect(java.util.stream.Collectors.toList());
        }
    }
}
