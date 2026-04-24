package com.nl2sql.core.rag.dto;

import lombok.Data;

/**
 * SQL反馈请求
 */
@Data
public class SQLFeedbackRequest {
    
    /**
     * 关联的知识库ID（可选，如果该SQL已存入知识库）
     */
    private Long knowledgeId;
    
    /**
     * 评分: 1=很差, 2=较差, 3=一般, 4=较好, 5=很好
     */
    private Integer rating;
    
    /**
     * 反馈文本（说明打分原因）
     */
    private String feedbackText;
    
    /**
     * 原始问题
     */
    private String question;
    
    /**
     * 生成的SQL
     */
    private String generatedSql;
    
    /**
     * 实际执行的SQL（可能经过修正）
     */
    private String executedSql;
    
    /**
     * 是否执行成功
     */
    private Boolean executionSuccess;
    
    /**
     * 会话ID
     */
    private String sessionId;
    
    /**
     * ✅ 新增：最终使用的表列表（由NL2SQLService传入）
     * 优先级高于从SQL提取的表
     */
    private java.util.List<String> usedTables;
}
