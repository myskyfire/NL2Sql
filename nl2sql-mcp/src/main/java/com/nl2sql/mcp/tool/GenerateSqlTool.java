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
 * SQL 生成 Tool - 将自然语言转换为 SQL
 */
@Slf4j
@Component
public class GenerateSqlTool extends McpToolSupport {

    private final DataSourcePoolManager poolManager;

    public GenerateSqlTool(DataSourcePoolManager poolManager) {
        this.poolManager = poolManager;
    }

    public McpServerFeatures.SyncToolSpecification buildGenerateSqlTool() {
        return buildTool(
                "generate_sql",
                "将自然语言问题转换为 SQL 查询语句（基于数据库表结构智能生成）",
                Map.of(
                        "datasource_id", param("integer", "数据源ID"),
                        "question", param("string", "自然语言问题（如：查询昨天的订单数量）")
                ),
                List.of("datasource_id", "question"),
                args -> {
                    try {
                        return generateSql(getLongParam(args, "datasource_id"), getStringParam(args, "question"));
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }
        );
    }

    private String generateSql(Long datasourceId, String question) throws Exception {
        // 获取表结构作为上下文
        List<Map<String, Object>> tables = getTableSchemas(datasourceId);

        // TODO: 调用 LLM 生成 SQL（当前返回示例）
        String sql = generateSqlByLLM(question, tables);

        return toJson(Map.of(
                "sql", sql,
                "question", question,
                "tables_used", extractTablesFromSql(sql)
        ));
    }

    private List<Map<String, Object>> getTableSchemas(Long datasourceId) throws Exception {
        List<Map<String, Object>> tables = new ArrayList<>();

        try (Connection conn = poolManager.getConnection(datasourceId)) {
            DatabaseMetaData metaData = conn.getMetaData();

            try (ResultSet rs = metaData.getTables(null, null, "%", new String[]{"TABLE"})) {
                while (rs.next()) {
                    String tableName = rs.getString("TABLE_NAME");
                    List<String> columns = new ArrayList<>();
                    try (ResultSet colRs = metaData.getColumns(null, null, tableName, null)) {
                        while (colRs.next()) {
                            columns.add(colRs.getString("COLUMN_NAME"));
                        }
                    }
                    tables.add(Map.of(
                            "table_name", tableName,
                            "columns", columns
                    ));
                }
            }
        }

        return tables;
    }

    private String generateSqlByLLM(String question, List<Map<String, Object>> tables) {
        // TODO: 集成 LangChain4j 调用 LLM
        if (question.contains("订单") || question.contains("order")) {
            return "SELECT COUNT(*) as order_count FROM orders WHERE DATE(create_time) = CURDATE() - INTERVAL 1 DAY";
        } else if (question.contains("用户") || question.contains("user")) {
            return "SELECT COUNT(*) as user_count FROM users";
        } else {
            return "-- 无法识别的查询，请提供更明确的问题\nSELECT 1";
        }
    }

    private List<String> extractTablesFromSql(String sql) {
        if (sql == null || sql.trim().isEmpty()) return List.of();

        String upperSql = sql.toUpperCase();
        int fromIndex = upperSql.indexOf("FROM");
        if (fromIndex == -1) return List.of();

        String afterFrom = sql.substring(fromIndex + 4).trim();
        int spaceIndex = afterFrom.indexOf(" ");
        String tableName = spaceIndex > 0 ? afterFrom.substring(0, spaceIndex) : afterFrom;

        return List.of(tableName.replaceAll("[;\\s]", "").toLowerCase());
    }
}
