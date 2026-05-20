package com.nl2sql.mcp.tool;

import com.nl2sql.mcp.datasource.DataSourcePoolManager;
import io.modelcontextprotocol.server.McpServerFeatures;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.sql.*;
import java.util.*;

/**
 * 执行只读 SQL Tool
 */
@Slf4j
@Component
public class ExecuteSqlTool extends McpToolSupport {

    private final DataSourcePoolManager poolManager;
    private static final int MAX_ROWS = 1000;

    public ExecuteSqlTool(DataSourcePoolManager poolManager) {
        this.poolManager = poolManager;
    }

    public McpServerFeatures.SyncToolSpecification buildExecuteSqlTool() {
        return buildTool(
                "execute_sql",
                "执行只读 SQL 查询并返回结果",
                Map.of(
                        "datasource_id", param("integer", "数据源ID"),
                        "sql", param("string", "SQL 查询语句（仅支持 SELECT/WITH/SHOW/DESCRIBE）")
                ),
                List.of("datasource_id", "sql"),
                args -> executeQuery(getLongParam(args, "datasource_id"), getStringParam(args, "sql"))
        );
    }

    private String executeQuery(Long datasourceId, String sql) {
        if (!isReadOnlyQuery(sql)) {
            return "{\"error\": \"只允许执行 SELECT 查询\"}";
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        List<String> columns = new ArrayList<>();

        try (Connection conn = poolManager.getConnection(datasourceId);
             Statement stmt = conn.createStatement()) {

            stmt.setMaxRows(MAX_ROWS);

            try (ResultSet rs = stmt.executeQuery(sql)) {
                ResultSetMetaData metaData = rs.getMetaData();
                int columnCount = metaData.getColumnCount();

                for (int i = 1; i <= columnCount; i++) {
                    columns.add(metaData.getColumnName(i));
                }

                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= columnCount; i++) {
                        row.put(metaData.getColumnName(i), rs.getObject(i));
                    }
                    rows.add(row);
                }
            }

            Map<String, Object> result = Map.of(
                    "columns", columns,
                    "rows", rows,
                    "row_count", rows.size(),
                    "truncated", rows.size() >= MAX_ROWS
            );
            return toJson(result);

        } catch (Exception e) {
            log.error("SQL 执行失败: sql={}", sql, e);
            throw new RuntimeException(e);
        }
    }

    private boolean isReadOnlyQuery(String sql) {
        if (sql == null || sql.trim().isEmpty()) return false;
        String trimmed = sql.trim().toUpperCase();
        return trimmed.startsWith("SELECT") || trimmed.startsWith("WITH")
                || trimmed.startsWith("SHOW") || trimmed.startsWith("DESCRIBE");
    }
}
