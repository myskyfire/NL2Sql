package com.nl2sql.core.rag.dto;

import lombok.Data;

/**
 * RAG 问答对 DTO
 */
@Data
public class RagQAPairDTO {
    private Long id;
    private String question;
    private String answer;
    private String sqlExample;
    private String category;
    private Float qualityScore;
}
