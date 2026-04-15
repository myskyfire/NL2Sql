package com.nl2sql.metadata.entity;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class TableMetadata {
    private Long id;
    private Long datasourceId;
    private String tableName;
    private String tableComment;
    private String tableType;
    private String schemaName;
    private Long rowCountEstimate;
    private Long dataSizeKb;
    private Long indexSizeKb;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
