package com.nl2sql.mcp.tool;

import com.nl2sql.mcp.datasource.DataSourcePoolManager;
import io.modelcontextprotocol.server.McpServerFeatures;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.*;

/**
 * 查询表结构 Tool
 */
@Slf4j
@Component
public class QuerySchemaTool extends McpToolSupport {

    private final DataSourcePoolManager poolManager;

    public QuerySchemaTool(DataSourcePoolManager poolManager) {
        this.poolManager = poolManager;
    }

    public McpServerFeatures.SyncToolSpecification buildQuerySchemaTool() {
        return buildTool(
                "query_schema",
                "查询指定表的完整结构信息（字段名、类型、注释、主键等）",
                Map.of(
                        "datasource_id", param("integer", "数据源ID"),
                        "table_name", param("string", "表名")
                ),
                List.of("datasource_id", "table_name"),
                args -> {
                    try {
                        return queryTableSchema(getLongParam(args, "datasource_id"), getStringParam(args, "table_name"));
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }
        );
    }

    private String queryTableSchema(Long datasourceId, String tableName) throws Exception {
        List<Map<String, Object>> columns = new ArrayList<>();
        List<String> primaryKeys = new ArrayList<>();

        try (Connection conn = poolManager.getConnection(datasourceId)) {
            DatabaseMetaData metaData = conn.getMetaData();

            try (ResultSet rs = metaData.getColumns(null, null, tableName, null)) {
                while (rs.next()) {
                    columns.add(Map.of(
                            "name", rs.getString("COLUMN_NAME"),
                            "type", rs.getString("TYPE_NAME"),
                            "size", rs.getInt("COLUMN_SIZE"),
                            "nullable", rs.getInt("NULLABLE") == DatabaseMetaData.columnNullable,
                            "comment", rs.getString("REMARKS"),
                            "default_value", rs.getString("COLUMN_DEF")
                    ));
                }
            }

            try (ResultSet rs = metaData.getPrimaryKeys(null, null, tableName)) {
                while (rs.next()) {
                    primaryKeys.add(rs.getString("COLUMN_NAME"));
                }
            }
        }

        return toJson(Map.of(
                "table_name", tableName,
                "columns", columns,
                "primary_keys", primaryKeys,
                "column_count", columns.size()
        ));
    }
}
