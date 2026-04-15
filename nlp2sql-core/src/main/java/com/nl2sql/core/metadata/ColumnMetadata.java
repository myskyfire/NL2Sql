package com.nl2sql.core.metadata;

import lombok.Data;

@Data
public class ColumnMetadata {
    private String columnName;
    private String dataType;
    private String columnComment;
    private boolean isPrimary;
    private String foreignKey;
}
