package com.nl2sql.core.metadata;

import lombok.Data;
import java.util.List;

@Data
public class TableMetadata {
    private String tableName;
    private String tableComment;
    private List<ColumnMetadata> columns;
}
