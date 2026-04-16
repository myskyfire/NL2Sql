package com.nl2sql.core.rag.dto;

import lombok.Data;

/**
 * QA对导入请求
 */
@Data
public class QAImportRequest {
    
    /**
     * 用户问题（必填）
     */
    private String question;
    
    /**
     * SQL语句（必填）
     */
    private String sql;
    
    /**
     * AI回答模板（可选，为空时自动生成）
     */
    private String answer;
    
    /**
     * 分类标签（可选）
     */
    private String category;
    
    /**
     * 质量评分 0.0-1.0（可选，默认0.9）
     */
    private Float qualityScore;
}
