package com.nl2sql.metadata.entity;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class ColumnMetadata {
    private Long id;
    private Long datasourceId;
    private String tableName;
    private String tableComment;  // 表注释
    private String columnName;
    private String dataType;
    private Integer columnSize;
    private Integer decimalDigits;
    private Integer isNullable;
    private String columnDefault;
    private String columnComment;
    private Integer isPrimaryKey;
    private Integer isUnique;
    private Integer ordinalPosition;
    private String characterSetName;
    private String collationName;
    private String extraInfo;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
