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
 * 批量查询表结构 Tool
 * 
 * 一次调用返回指定数据源中所有表（或指定表列表）的完整结构信息，
 * 用于元数据同步到本地 RAG 向量库。
 */
@Slf4j
@Component
public class BatchQuerySchemaTool extends McpToolSupport {

    private final DataSourcePoolManager poolManager;

    public BatchQuerySchemaTool(DataSourcePoolManager poolManager) {
        this.poolManager = poolManager;
    }

    public McpServerFeatures.SyncToolSpecification buildBatchQuerySchemaTool() {
        return buildTool(
                "batch_query_schema",
                "批量查询指定数据源中所有表（或指定表列表）的完整结构信息，用于元数据同步",
                Map.of(
                        "datasource_id", param("integer", "数据源ID"),
                        "table_names", param("array", "表名列表（可选，为空则查询所有表）"),
                        "schema_pattern", param("string", "Schema 匹配模式（可选，如 public）")
                ),
                List.of("datasource_id"),
                args -> {
                    try {
                        return batchQuerySchema(
                                getLongParam(args, "datasource_id"),
                                getStringParam(args, "schema_pattern"),
                                getTableNames(args)
                        );
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }
        );
    }

    @SuppressWarnings("unchecked")
    private List<String> getTableNames(Map<String, Object> args) {
        Object tableNames = args.get("table_names");
        if (tableNames instanceof List<?> list) {
            return list.stream().map(Object::toString).toList();
        }
        return Collections.emptyList();
    }

    private String batchQuerySchema(Long datasourceId, String schemaPattern, List<String> tableFilter) throws Exception {
        List<Map<String, Object>> allTables = new ArrayList<>();

        try (Connection conn = poolManager.getConnection(datasourceId)) {
            DatabaseMetaData metaData = conn.getMetaData();
            String schema = (schemaPattern != null && !schemaPattern.isEmpty()) ? schemaPattern : null;

            // 1. 获取所有表名
            List<String> tableNames = new ArrayList<>();
            try (ResultSet rs = metaData.getTables(null, schema, "%", new String[]{"TABLE"})) {
                while (rs.next()) {
                    String tableName = rs.getString("TABLE_NAME");
                    if (tableFilter.isEmpty() || tableFilter.contains(tableName)) {
                        tableNames.add(tableName);
                    }
                }
            }

            log.info("[BatchQuerySchema] 数据源 {} 共 {} 张表，将查询 {} 张", datasourceId, tableNames.size(), tableNames.size());

            // 2. 批量查询每张表的结构
            int totalColumns = 0;
            for (String tableName : tableNames) {
                Map<String, Object> tableSchema = querySingleTable(metaData, tableName);
                allTables.add(tableSchema);
                totalColumns += (int) tableSchema.get("column_count");
            }

            // 3. 获取表关联关系
            List<Map<String, Object>> relationships = queryRelationships(metaData);

            Map<String, Object> result = Map.of(
                    "datasource_id", datasourceId,
                    "tables", allTables,
                    "table_count", allTables.size(),
                    "total_columns", totalColumns,
                    "relationships", relationships,
                    "relationship_count", relationships.size()
            );

            log.info("[BatchQuerySchema] 查询完成: {} 张表, {} 个字段, {} 个关联",
                    allTables.size(), totalColumns, relationships.size());

            return toJson(result);
        }
    }

    private Map<String, Object> querySingleTable(DatabaseMetaData metaData, String tableName) throws Exception {
        List<Map<String, Object>> columns = new ArrayList<>();
        List<String> primaryKeys = new ArrayList<>();
        String tableComment = "";

        // 查询列信息
        try (ResultSet rs = metaData.getColumns(null, null, tableName, null)) {
            while (rs.next()) {
                columns.add(Map.of(
                        "name", rs.getString("COLUMN_NAME"),
                        "type", rs.getString("TYPE_NAME"),
                        "size", rs.getInt("COLUMN_SIZE"),
                        "nullable", rs.getInt("NULLABLE") == DatabaseMetaData.columnNullable,
                        "comment", rs.getString("REMARKS") != null ? rs.getString("REMARKS") : "",
                        "default_value", rs.getString("COLUMN_DEF")
                ));
            }
        }

        // 查询主键
        try (ResultSet rs = metaData.getPrimaryKeys(null, null, tableName)) {
            while (rs.next()) {
                primaryKeys.add(rs.getString("COLUMN_NAME"));
            }
        }

        // 查询表注释（MySQL 通过 TABLE_REMARKS 获取）
        try (ResultSet rs = metaData.getTables(null, null, tableName, null)) {
            if (rs.next()) {
                tableComment = rs.getString("REMARKS") != null ? rs.getString("REMARKS") : "";
            }
        }

        return Map.of(
                "table_name", tableName,
                "table_comment", tableComment,
                "columns", columns,
                "primary_keys", primaryKeys,
                "column_count", columns.size()
        );
    }

    private List<Map<String, Object>> queryRelationships(DatabaseMetaData metaData) throws Exception {
        List<Map<String, Object>> relationships = new ArrayList<>();

        try (ResultSet rs = metaData.getImportedKeys(null, null, null)) {
            while (rs.next()) {
                relationships.add(Map.of(
                        "child_table", rs.getString("FKTABLE_NAME"),
                        "child_column", rs.getString("FKCOLUMN_NAME"),
                        "parent_table", rs.getString("PKTABLE_NAME"),
                        "parent_column", rs.getString("PKCOLUMN_NAME"),
                        "fk_name", rs.getString("FK_NAME") != null ? rs.getString("FK_NAME") : "",
                        "update_rule", getRuleDescription(rs.getShort("UPDATE_RULE")),
                        "delete_rule", getRuleDescription(rs.getShort("DELETE_RULE"))
                ));
            }
        }

        return relationships;
    }

    private String getRuleDescription(Short ruleCode) {
        if (ruleCode == null) return "UNKNOWN";
        return switch (ruleCode) {
            case DatabaseMetaData.importedKeyCascade -> "CASCADE";
            case DatabaseMetaData.importedKeyRestrict -> "RESTRICT";
            case DatabaseMetaData.importedKeySetNull -> "SET NULL";
            case DatabaseMetaData.importedKeySetDefault -> "SET DEFAULT";
            case DatabaseMetaData.importedKeyNoAction -> "NO ACTION";
            default -> "UNKNOWN (" + ruleCode + ")";
        };
    }
}
