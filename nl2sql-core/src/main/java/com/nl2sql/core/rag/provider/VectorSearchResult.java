package com.nl2sql.core.rag.provider;

import lombok.Data;

/**
 * 向量搜索结果
 */
@Data
public class VectorSearchResult {
    /**
     * 问题文本
     */
    private String question;
    
    /**
     * 答案
     */
    private String answer;
    
    /**
     * SQL示例
     */
    private String sqlExample;
    
    /**
     * 分类
     */
    private String category;
    
    /**
     * 相似度分数（0-1）
     */
    private double score;
}
