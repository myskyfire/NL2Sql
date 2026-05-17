package com.nl2sql.mcp.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nl2sql.mcp.datasource.DataSourcePoolManager;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * SQL 验证 Tool
 * 
 * 输入：datasource_id, sql
 * 输出：验证结果（语法是否正确、是否能执行、风险评估）
 */
@Slf4j
@Component
public class ValidateSqlTool {

    private final DataSourcePoolManager poolManager;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ValidateSqlTool(DataSourcePoolManager poolManager) {
        this.poolManager = poolManager;
    }

    /**
     * 构建 ValidateSql Tool Specification
     */
    public McpServerFeatures.SyncToolSpecification buildValidateSqlTool() {
        Map<String, Object> properties = new HashMap<>();
        properties.put("datasource_id", Map.of(
            "type", "integer",
            "description", "数据源ID"
        ));
        properties.put("sql", Map.of(
            "type", "string",
            "description", "待验证的 SQL 语句"
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
                "validate_sql",
                "验证 SQL 语句的语法正确性和安全性（不实际执行，仅检查）",
                inputSchemaJson
            ),
            (exchange, args) -> {
                try {
                    Long datasourceId = ((Number) args.get("datasource_id")).longValue();
                    String sql = (String) args.get("sql");

                    log.info("执行 validate_sql: datasourceId={}, sql={}", datasourceId, sql);

                    Map<String, Object> result = validateSql(datasourceId, sql);

                    return McpSchema.CallToolResult.builder()
                        .content(List.of(new McpSchema.TextContent(objectMapper.writeValueAsString(result))))
                        .isError(false)
                        .build();
                } catch (Exception e) {
                    log.error("validate_sql 执行失败", e);
                    return McpSchema.CallToolResult.builder()
                        .content(List.of(new McpSchema.TextContent("{\"error\": \"" + e.getMessage() + "\"}")))
                        .isError(true)
                        .build();
                }
            }
        );
    }

    /**
     * 验证 SQL
     */
    private Map<String, Object> validateSql(Long datasourceId, String sql) throws Exception {
        Map<String, Object> result = new HashMap<>();
        
        // 1. 基本语法检查
        if (sql == null || sql.trim().isEmpty()) {
            result.put("valid", false);
            result.put("error", "SQL 不能为空");
            return result;
        }
        
        // 2. 安全检查（只允许 SELECT）
        String upperSql = sql.trim().toUpperCase();
        boolean isReadOnly = upperSql.startsWith("SELECT") || 
                            upperSql.startsWith("WITH") ||
                            upperSql.startsWith("SHOW") ||
                            upperSql.startsWith("DESCRIBE") ||
                            upperSql.startsWith("EXPLAIN");
        
        if (!isReadOnly) {
            result.put("valid", false);
            result.put("error", "只允许执行查询语句（SELECT/WITH/SHOW/DESCRIBE/EXPLAIN）");
            result.put("risk_level", "HIGH");
            return result;
        }
        
        // 3. 尝试解析（使用 EXPLAIN 验证语法）
        try (Connection conn = poolManager.getConnection(datasourceId);
             Statement stmt = conn.createStatement()) {
            
            // 使用 EXPLAIN 验证语法（不会真正执行）
            String explainSql = "EXPLAIN " + sql;
            stmt.execute(explainSql);
            
            result.put("valid", true);
            result.put("message", "SQL 语法正确");
            result.put("risk_level", "LOW");
            result.put("is_read_only", true);
            
        } catch (Exception e) {
            result.put("valid", false);
            result.put("error", "SQL 语法错误: " + e.getMessage());
            result.put("risk_level", "MEDIUM");
        }
        
        return result;
    }
}
