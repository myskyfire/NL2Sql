package com.nl2sql.metadata.entity;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class TableRelationship {
    private Long id;
    private Long datasourceId;
    private String sourceTable;
    private String sourceColumn;
    private String targetTable;
    private String targetColumn;
    private String relationshipType; // ONE_TO_ONE, MANY_TO_ONE, MANY_TO_MANY
    private Float confidence;
    private String description;
    private Integer isActive;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
