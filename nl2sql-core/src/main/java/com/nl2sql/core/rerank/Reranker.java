package com.nl2sql.core.rerank;

import java.util.List;

/**
 * Reranker 统一接口
 * 支持多种重排序模型：Jina Cloud API、本地 Cross-Encoder 等
 */
public interface Reranker {
    
    /**
     * 重排序主方法
     * 
     * @param query 用户查询
     * @param candidates 候选文档列表
     * @return 重排序后的 Top-K 文档
     */
    List<RerankedDocument> rerank(String query, List<String> candidates);
    
    /**
     * 获取 Reranker 名称
     */
    String getName();
    
    /**
     * 是否可用
     */
    boolean isAvailable();
    
    /**
     * 重排序结果
     */
    class RerankedDocument {
        private final String content;
        private final double relevanceScore;
        private final int originalPosition;
        
        public RerankedDocument(String content, double relevanceScore, int originalPosition) {
            this.content = content;
            this.relevanceScore = relevanceScore;
            this.originalPosition = originalPosition;
        }
        
        public String getContent() {
            return content;
        }
        
        public double getRelevanceScore() {
            return relevanceScore;
        }
        
        public int getOriginalPosition() {
            return originalPosition;
        }
    }
}
