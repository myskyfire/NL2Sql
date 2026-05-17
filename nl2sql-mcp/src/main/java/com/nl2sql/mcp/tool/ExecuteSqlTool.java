package com.nl2sql.mcp.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nl2sql.mcp.datasource.DataSourcePoolManager;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.sql.*;
import java.util.*;

/**
 * 执行只读 SQL Tool
 * 
 * 输入：datasource_id, sql, jdbc_url, username, password
 * 输出：SQL 执行结果（限制返回行数）
 */
@Slf4j
@Component
public class ExecuteSqlTool {

    private final DataSourcePoolManager poolManager;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private static final int MAX_ROWS = 1000; // 最大返回行数

    public ExecuteSqlTool(DataSourcePoolManager poolManager) {
        this.poolManager = poolManager;
    }

    /**
     * 构建 ExecuteSql Tool Specification
     */
    public McpServerFeatures.SyncToolSpecification buildExecuteSqlTool() {
        Map<String, Object> properties = new HashMap<>();
        properties.put("datasource_id", Map.of(
            "type", "integer",
            "description", "数据源ID（从可用数据源列表中选择）"
        ));
        properties.put("sql", Map.of(
            "type", "string",
            "description", "SQL 查询语句（仅支持 SELECT/WITH/SHOW/DESCRIBE）"
        ));
        
        Map<String, Object> inputSchemaMap = new HashMap<>();
        inputSchemaMap.put("type", "object");
        inputSchemaMap.put("properties", properties);
        inputSchemaMap.put("required", List.of("datasource_id", "sql"));
        
        String inputSchemaJson;
        try {
            inputSchemaJson = objectMapper.writeValueAsString(inputSchemaMap);
        } catch (Exception e) {
            inputSchemaJson = "{}";
        }
        
        return new McpServerFeatures.SyncToolSpecification(
            new McpSchema.Tool(
                "execute_sql",
                "执行只读 SQL 查询并返回结果",
                inputSchemaJson
            ),
            (exchange, args) -> {
                try {
                    Long datasourceId = ((Number) args.get("datasource_id")).longValue();
                    String sql = (String) args.get("sql");

                    log.info("执行 execute_sql: datasourceId={}, sql={}", datasourceId, sql);

                    // 安全检查：只允许 SELECT 语句
                    if (!isReadOnlyQuery(sql)) {
                        return McpSchema.CallToolResult.builder()
                            .content(List.of(new McpSchema.TextContent("{\"error\": \"只允许执行 SELECT 查询\"}")))
                            .isError(true)
                            .build();
                    }

                    Map<String, Object> result = executeQuery(datasourceId, sql);

                    return McpSchema.CallToolResult.builder()
                        .content(List.of(new McpSchema.TextContent(objectMapper.writeValueAsString(result))))
                        .isError(false)
                        .build();
                } catch (Exception e) {
                    log.error("execute_sql 执行失败", e);
                    return McpSchema.CallToolResult.builder()
                        .content(List.of(new McpSchema.TextContent("{\"error\": \"" + e.getMessage() + "\"}")))
                        .isError(true)
                        .build();
                }
            }
        );
    }

    /**
     * 检查是否为只读查询
     */
    private boolean isReadOnlyQuery(String sql) {
        if (sql == null || sql.trim().isEmpty()) {
            return false;
        }
        String trimmed = sql.trim().toUpperCase();
        return trimmed.startsWith("SELECT") || trimmed.startsWith("WITH") || trimmed.startsWith("SHOW") || trimmed.startsWith("DESCRIBE");
    }

    /**
     * 执行 SQL 查询
     */
    private Map<String, Object> executeQuery(Long datasourceId, String sql) throws Exception {
        Map<String, Object> result = new HashMap<>();
        List<Map<String, Object>> rows = new ArrayList<>();

        try (Connection conn = poolManager.getConnection(datasourceId);
             Statement stmt = conn.createStatement()) {
            
            stmt.setMaxRows(MAX_ROWS);
            
            try (ResultSet rs = stmt.executeQuery(sql)) {
                ResultSetMetaData metaData = rs.getMetaData();
                int columnCount = metaData.getColumnCount();

                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= columnCount; i++) {
                        String columnName = metaData.getColumnName(i);
                        Object value = rs.getObject(i);
                        row.put(columnName, value);
                    }
                    rows.add(row);
                }

                result.put("columns", getColumnNames(metaData));
                result.put("rows", rows);
                result.put("row_count", rows.size());
                result.put("truncated", rows.size() >= MAX_ROWS);
            }

        } catch (Exception e) {
            log.error("SQL 执行失败: sql={}", sql, e);
            throw e;
        }

        return result;
    }

    /**
     * 获取列名列表
     */
    private List<String> getColumnNames(ResultSetMetaData metaData) throws SQLException {
        List<String> columns = new ArrayList<>();
        int columnCount = metaData.getColumnCount();
        for (int i = 1; i <= columnCount; i++) {
            columns.add(metaData.getColumnName(i));
        }
        return columns;
    }
}
