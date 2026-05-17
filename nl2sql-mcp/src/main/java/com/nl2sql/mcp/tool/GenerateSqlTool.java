package com.nl2sql.mcp.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nl2sql.mcp.datasource.DataSourcePoolManager;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.*;
import java.util.stream.Collectors;

/**
 * NL2SQL 生成 Tool - 核心能力
 * 
 * 输入：datasource_id, question（自然语言问题）
 * 输出：生成的 SQL 语句
 */
@Slf4j
@Component
public class GenerateSqlTool {

    private final DataSourcePoolManager poolManager;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public GenerateSqlTool(DataSourcePoolManager poolManager) {
        this.poolManager = poolManager;
    }

    /**
     * 构建 GenerateSql Tool Specification
     */
    public McpServerFeatures.SyncToolSpecification buildGenerateSqlTool() {
        Map<String, Object> properties = new HashMap<>();
        properties.put("datasource_id", Map.of(
            "type", "integer",
            "description", "数据源ID"
        ));
        properties.put("question", Map.of(
            "type", "string",
            "description", "自然语言问题（如：查询昨天的订单数量）"
        ));
        
        Map<String, Object> inputSchemaMap = new HashMap<>();
        inputSchemaMap.put("type", "object");
        inputSchemaMap.put("properties", properties);
        inputSchemaMap.put("required", List.of("datasource_id", "question"));
        
        String inputSchemaJson;
        try {
            inputSchemaJson = objectMapper.writeValueAsString(inputSchemaMap);
        } catch (Exception e) {
            inputSchemaJson = "{}";
        }
        
        return new McpServerFeatures.SyncToolSpecification(
            new McpSchema.Tool(
                "generate_sql",
                "将自然语言问题转换为 SQL 查询语句（基于数据库表结构智能生成）",
                inputSchemaJson
            ),
            (exchange, args) -> {
                try {
                    Long datasourceId = ((Number) args.get("datasource_id")).longValue();
                    String question = (String) args.get("question");

                    log.info("执行 generate_sql: datasourceId={}, question={}", datasourceId, question);

                    // 获取表结构作为上下文
                    List<Map<String, Object>> tables = getTableSchemas(datasourceId);
                    
                    // TODO: 调用 LLM 生成 SQL（当前返回示例）
                    String sql = generateSqlByLLM(question, tables);

                    Map<String, Object> result = new HashMap<>();
                    result.put("sql", sql);
                    result.put("question", question);
                    result.put("tables_used", extractTablesFromSql(sql));

                    return McpSchema.CallToolResult.builder()
                        .content(List.of(new McpSchema.TextContent(objectMapper.writeValueAsString(result))))
                        .isError(false)
                        .build();
                } catch (Exception e) {
                    log.error("generate_sql 执行失败", e);
                    return McpSchema.CallToolResult.builder()
                        .content(List.of(new McpSchema.TextContent("{\"error\": \"" + e.getMessage() + "\"}")))
                        .isError(true)
                        .build();
                }
            }
        );
    }

    /**
     * 获取所有表结构（简化版，只取表名和字段名）
     */
    private List<Map<String, Object>> getTableSchemas(Long datasourceId) throws Exception {
        List<Map<String, Object>> tables = new ArrayList<>();
        
        try (Connection conn = poolManager.getConnection(datasourceId)) {
            DatabaseMetaData metaData = conn.getMetaData();
            
            // 获取所有表
            try (ResultSet rs = metaData.getTables(null, null, "%", new String[]{"TABLE"})) {
                while (rs.next()) {
                    String tableName = rs.getString("TABLE_NAME");
                    Map<String, Object> table = new HashMap<>();
                    table.put("table_name", tableName);
                    
                    // 获取字段列表
                    List<String> columns = new ArrayList<>();
                    try (ResultSet colRs = metaData.getColumns(null, null, tableName, null)) {
                        while (colRs.next()) {
                            columns.add(colRs.getString("COLUMN_NAME"));
                        }
                    }
                    table.put("columns", columns);
                    
                    tables.add(table);
                }
            }
        }
        
        return tables;
    }

    /**
     * 调用 LLM 生成 SQL（占位实现，后续集成 LangChain4j）
     */
    private String generateSqlByLLM(String question, List<Map<String, Object>> tables) {
        // TODO: 集成 LangChain4j 调用 LLM
        // 当前返回示例 SQL
        
        if (question.contains("订单") || question.contains("order")) {
            return "SELECT COUNT(*) as order_count FROM orders WHERE DATE(create_time) = CURDATE() - INTERVAL 1 DAY";
        } else if (question.contains("用户") || question.contains("user")) {
            return "SELECT COUNT(*) as user_count FROM users";
        } else {
            return "-- 无法识别的查询，请提供更明确的问题\nSELECT 1";
        }
    }

    /**
     * 从 SQL 中提取表名
     */
    private List<String> extractTablesFromSql(String sql) {
        if (sql == null || sql.trim().isEmpty()) {
            return List.of();
        }
        
        // 简单提取 FROM 后面的表名
        String upperSql = sql.toUpperCase();
        int fromIndex = upperSql.indexOf("FROM");
        if (fromIndex == -1) {
            return List.of();
        }
        
        String afterFrom = sql.substring(fromIndex + 4).trim();
        int spaceIndex = afterFrom.indexOf(" ");
        String tableName = spaceIndex > 0 ? afterFrom.substring(0, spaceIndex) : afterFrom;
        
        return List.of(tableName.replaceAll("[;\\s]", "").toLowerCase());
    }
}
