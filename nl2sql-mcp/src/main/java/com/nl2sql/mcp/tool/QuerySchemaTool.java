package com.nl2sql.mcp.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nl2sql.mcp.datasource.DataSourcePoolManager;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 查询表结构 Tool
 * 
 * 输入：datasource_id, table_name
 * 输出：表的完整结构信息（字段名、类型、注释、主键等）
 */
@Slf4j
@Component
public class QuerySchemaTool {

    private final DataSourcePoolManager poolManager;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public QuerySchemaTool(DataSourcePoolManager poolManager) {
        this.poolManager = poolManager;
    }

    /**
     * 构建 QuerySchema Tool Specification
     */
    public McpServerFeatures.SyncToolSpecification buildQuerySchemaTool() {
        // 定义 Input Schema
        Map<String, Object> properties = new HashMap<>();
        properties.put("datasource_id", Map.of(
            "type", "integer",
            "description", "数据源ID（从可用数据源列表中选择）"
        ));
        properties.put("table_name", Map.of(
            "type", "string",
            "description", "表名"
        ));
        
        Map<String, Object> inputSchemaMap = new HashMap<>();
        inputSchemaMap.put("type", "object");
        inputSchemaMap.put("properties", properties);
        inputSchemaMap.put("required", List.of("datasource_id", "table_name"));
        
        String inputSchemaJson;
        try {
            inputSchemaJson = objectMapper.writeValueAsString(inputSchemaMap);
        } catch (Exception e) {
            inputSchemaJson = "{}";
        }
        
        return new McpServerFeatures.SyncToolSpecification(
            new McpSchema.Tool(
                "query_schema",
                "查询指定表的完整结构信息",
                inputSchemaJson
            ),
            (exchange, args) -> {
                try {
                    Long datasourceId = ((Number) args.get("datasource_id")).longValue();
                    String tableName = (String) args.get("table_name");

                    log.info("执行 query_schema: datasourceId={}, table={}", datasourceId, tableName);

                    Map<String, Object> result = queryTableSchema(datasourceId, tableName);

                    return McpSchema.CallToolResult.builder()
                        .content(List.of(new McpSchema.TextContent(objectMapper.writeValueAsString(result))))
                        .isError(false)
                        .build();
                } catch (Exception e) {
                    log.error("query_schema 执行失败", e);
                    return McpSchema.CallToolResult.builder()
                        .content(List.of(new McpSchema.TextContent("{\"error\": \"" + e.getMessage() + "\"}")))
                        .isError(true)
                        .build();
                }
            }
        );
    }

    /**
     * 查询表结构
     */
    private Map<String, Object> queryTableSchema(Long datasourceId, String tableName) throws Exception {
        Map<String, Object> result = new HashMap<>();
        List<Map<String, Object>> columns = new ArrayList<>();
        List<String> primaryKeys = new ArrayList<>();

        try (Connection conn = poolManager.getConnection(datasourceId)) {
            DatabaseMetaData metaData = conn.getMetaData();

            // 查询列信息
            try (ResultSet rs = metaData.getColumns(null, null, tableName, null)) {
                while (rs.next()) {
                    Map<String, Object> column = new HashMap<>();
                    column.put("name", rs.getString("COLUMN_NAME"));
                    column.put("type", rs.getString("TYPE_NAME"));
                    column.put("size", rs.getInt("COLUMN_SIZE"));
                    column.put("nullable", rs.getInt("NULLABLE") == DatabaseMetaData.columnNullable);
                    column.put("comment", rs.getString("REMARKS"));
                    column.put("default_value", rs.getString("COLUMN_DEF"));
                    columns.add(column);
                }
            }

            // 查询主键
            try (ResultSet rs = metaData.getPrimaryKeys(null, null, tableName)) {
                while (rs.next()) {
                    primaryKeys.add(rs.getString("COLUMN_NAME"));
                }
            }

            result.put("table_name", tableName);
            result.put("columns", columns);
            result.put("primary_keys", primaryKeys);
            result.put("column_count", columns.size());

        } catch (Exception e) {
            log.error("查询表结构失败: table={}", tableName, e);
            throw e;
        }

        return result;
    }
}
