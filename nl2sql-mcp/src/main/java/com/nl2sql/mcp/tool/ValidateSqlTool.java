package com.nl2sql.mcp.tool;

import com.nl2sql.mcp.datasource.DataSourcePoolManager;
import io.modelcontextprotocol.server.McpServerFeatures;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.Statement;
import java.util.*;

/**
 * SQL 验证 Tool
 */
@Slf4j
@Component
public class ValidateSqlTool extends McpToolSupport {

    private final DataSourcePoolManager poolManager;

    public ValidateSqlTool(DataSourcePoolManager poolManager) {
        this.poolManager = poolManager;
    }

    public McpServerFeatures.SyncToolSpecification buildValidateSqlTool() {
        return buildTool(
                "validate_sql",
                "验证 SQL 语句的语法正确性和安全性（不实际执行，仅检查）",
                Map.of(
                        "datasource_id", param("integer", "数据源ID"),
                        "sql", param("string", "待验证的 SQL 语句")
                ),
                List.of("datasource_id", "sql"),
                args -> {
                    try {
                        return validateSql(getLongParam(args, "datasource_id"), getStringParam(args, "sql"));
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }
        );
    }

    private String validateSql(Long datasourceId, String sql) throws Exception {
        if (sql == null || sql.trim().isEmpty()) {
            return toJson(Map.of("valid", false, "error", "SQL 不能为空"));
        }

        String upperSql = sql.trim().toUpperCase();
        boolean isReadOnly = upperSql.startsWith("SELECT") || upperSql.startsWith("WITH")
                || upperSql.startsWith("SHOW") || upperSql.startsWith("DESCRIBE")
                || upperSql.startsWith("EXPLAIN");

        if (!isReadOnly) {
            return toJson(Map.of(
                    "valid", false,
                    "error", "只允许执行查询语句（SELECT/WITH/SHOW/DESCRIBE/EXPLAIN）",
                    "risk_level", "HIGH"
            ));
        }

        try (Connection conn = poolManager.getConnection(datasourceId);
             Statement stmt = conn.createStatement()) {

            stmt.execute("EXPLAIN " + sql);

            return toJson(Map.of(
                    "valid", true,
                    "message", "SQL 语法正确",
                    "risk_level", "LOW",
                    "is_read_only", true
            ));

        } catch (Exception e) {
            return toJson(Map.of(
                    "valid", false,
                    "error", "SQL 语法错误: " + e.getMessage(),
                    "risk_level", "MEDIUM"
            ));
        }
    }
}
