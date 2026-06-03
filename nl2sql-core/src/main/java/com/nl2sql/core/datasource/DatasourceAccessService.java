package com.nl2sql.core.datasource;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nl2sql.auth.service.RowLevelFilterService;
import com.nl2sql.core.mcp.McpClientService;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 数据源访问统一抽象层
 *
 * 根据 nl2sql.mcp.enabled 配置自动选择 MCP 或 JDBC 模式：
 * - MCP 模式：通过 MCP Server 访问远端库，本地不存凭证
 * - JDBC 模式：通过 DataSourceManager 直连远端库（现有逻辑不变）
 *
 * 所有需要访问远端库的组件统一通过此服务，不再直接使用 DataSourceManager
 */
@Slf4j
@Service
public class DatasourceAccessService {

    @Value("${nl2sql.mcp.enabled:false}")
    private boolean mcpEnabled;

    @Autowired(required = false)
    private McpClientService mcpClientService;

    @Autowired
    private DataSourceManager dataSourceManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired(required = false)
    private RowLevelFilterService rowLevelFilterService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * SQL 执行结果
     */
    @Data
    public static class SqlExecutionResult {
        private boolean success;
        private List<Map<String, Object>> data;
        private int rowCount;
        private double executionTime;
        private String error;
        private List<String> columns;
        private boolean truncated;
    }

    /**
     * SQL 验证结果
     */
    @Data
    public static class SqlValidationResult {
        private boolean valid;
        private String error;
        private String riskLevel;
        private String message;
        private boolean isReadOnly;
    }

    /**
     * 元数据查询结果
     */
    @Data
    public static class SchemaMetadataResult {
        private Long datasourceId;
        private List<TableSchema> tables;
        private int tableCount;
        private int totalColumns;
        private List<Map<String, Object>> relationships;
        private int relationshipCount;
    }

    @Data
    public static class TableSchema {
        private String tableName;
        private String tableComment;
        private List<ColumnSchema> columns;
        private List<String> primaryKeys;
        private int columnCount;
    }

    @Data
    public static class ColumnSchema {
        private String name;
        private String type;
        private int size;
        private boolean nullable;
        private String comment;
        private String defaultValue;
    }

    // ==================== SQL 执行 ====================

    /**
     * 执行只读 SQL 查询（带行级过滤）
     *
     * @param datasourceId 数据源 ID
     * @param sql          SQL 语句
     * @param userId       用户 ID（用于行级权限过滤，null 则不过滤）
     * @return 执行结果
     */
    public SqlExecutionResult executeSql(Long datasourceId, String sql, Long userId) {
        // 行级权限过滤（在SQL发送到远端之前改写）
        if (rowLevelFilterService != null && userId != null) {
            try {
                String filteredSql = rowLevelFilterService.applyRowLevelFilter(sql, userId, datasourceId);
                if (!filteredSql.equals(sql)) {
                    log.info("[DatasourceAccessService] 行级过滤改写SQL: userId={}, datasourceId={}", userId, datasourceId);
                    sql = filteredSql;
                }
            } catch (Exception e) {
                log.error("[DatasourceAccessService] 行级过滤异常，使用原始SQL: {}", e.getMessage());
            }
        }

        if (mcpEnabled && mcpClientService != null && mcpClientService.isAvailable()) {
            return executeSqlViaMcp(datasourceId, sql);
        } else {
            return executeSqlViaJdbc(datasourceId, sql);
        }
    }

    /**
     * 执行只读 SQL 查询（无行级过滤，向后兼容）
     */
    public SqlExecutionResult executeSql(Long datasourceId, String sql) {
        return executeSql(datasourceId, sql, null);
    }

    private SqlExecutionResult executeSqlViaMcp(Long datasourceId, String sql) {
        SqlExecutionResult result = new SqlExecutionResult();
        long startTime = System.currentTimeMillis();

        try {
            String response = mcpClientService.executeSql(datasourceId, sql);
            Map<String, Object> parsed = objectMapper.readValue(response, new TypeReference<>() {});

            if (parsed.containsKey("error")) {
                result.setSuccess(false);
                result.setError(String.valueOf(parsed.get("error")));
                return result;
            }

            result.setSuccess(true);
            result.setColumns((List<String>) parsed.get("columns"));
            result.setData((List<Map<String, Object>>) parsed.get("rows"));
            result.setRowCount(parsed.get("row_count") != null ? ((Number) parsed.get("row_count")).intValue() : 0);
            result.setTruncated(Boolean.TRUE.equals(parsed.get("truncated")));
            result.setExecutionTime((System.currentTimeMillis() - startTime) / 1000.0);

            log.debug("[DatasourceAccessService] MCP SQL执行成功: datasourceId={}, rows={}", datasourceId, result.getRowCount());
        } catch (Exception e) {
            log.error("[DatasourceAccessService] MCP SQL执行失败，降级到JDBC: datasourceId={}", datasourceId, e);
            return executeSqlViaJdbc(datasourceId, sql);
        }

        return result;
    }

    private SqlExecutionResult executeSqlViaJdbc(Long datasourceId, String sql) {
        SqlExecutionResult result = new SqlExecutionResult();
        long startTime = System.currentTimeMillis();

        try {
            JdbcTemplate targetJdbc = dataSourceManager.getJdbcTemplate(datasourceId);
            List<Map<String, Object>> rows = targetJdbc.queryForList(sql);

            result.setSuccess(true);
            result.setData(rows);
            result.setRowCount(rows.size());
            result.setExecutionTime((System.currentTimeMillis() - startTime) / 1000.0);

            if (!rows.isEmpty()) {
                result.setColumns(new ArrayList<>(rows.get(0).keySet()));
            }

            log.debug("[DatasourceAccessService] JDBC SQL执行成功: datasourceId={}, rows={}", datasourceId, rows.size());
        } catch (Exception e) {
            result.setSuccess(false);
            result.setError(e.getMessage());
            result.setExecutionTime((System.currentTimeMillis() - startTime) / 1000.0);
            log.error("[DatasourceAccessService] JDBC SQL执行失败: datasourceId={}", datasourceId, e);
        }

        return result;
    }

    // ==================== SQL 验证（EXPLAIN） ====================

    /**
     * 验证 SQL 语法（EXPLAIN）
     *
     * @param datasourceId 数据源 ID
     * @param sql          SQL 语句
     * @return 验证结果
     */
    public SqlValidationResult validateSql(Long datasourceId, String sql) {
        if (mcpEnabled && mcpClientService != null && mcpClientService.isAvailable()) {
            return validateSqlViaMcp(datasourceId, sql);
        } else {
            return validateSqlViaJdbc(datasourceId, sql);
        }
    }

    private SqlValidationResult validateSqlViaMcp(Long datasourceId, String sql) {
        try {
            String response = mcpClientService.validateSql(datasourceId, sql);
            Map<String, Object> parsed = objectMapper.readValue(response, new TypeReference<>() {});

            SqlValidationResult result = new SqlValidationResult();
            result.setValid(Boolean.TRUE.equals(parsed.get("valid")));
            result.setError(parsed.get("error") != null ? String.valueOf(parsed.get("error")) : null);
            result.setRiskLevel(parsed.get("risk_level") != null ? String.valueOf(parsed.get("risk_level")) : "LOW");
            result.setMessage(parsed.get("message") != null ? String.valueOf(parsed.get("message")) : null);
            result.setReadOnly(Boolean.TRUE.equals(parsed.get("is_read_only")));

            log.debug("[DatasourceAccessService] MCP SQL验证完成: datasourceId={}, valid={}", datasourceId, result.isValid());
            return result;
        } catch (Exception e) {
            log.error("[DatasourceAccessService] MCP SQL验证失败，降级到JDBC: datasourceId={}", datasourceId, e);
            return validateSqlViaJdbc(datasourceId, sql);
        }
    }

    private SqlValidationResult validateSqlViaJdbc(Long datasourceId, String sql) {
        SqlValidationResult result = new SqlValidationResult();

        try {
            JdbcTemplate targetJdbc = dataSourceManager.getJdbcTemplate(datasourceId);
            targetJdbc.queryForList("EXPLAIN " + sql);

            result.setValid(true);
            result.setRiskLevel("LOW");
            result.setMessage("SQL语法正确");
            result.setReadOnly(true);

            log.debug("[DatasourceAccessService] JDBC SQL验证通过: datasourceId={}", datasourceId);
        } catch (Exception e) {
            result.setValid(false);
            result.setError("SQL语法错误: " + e.getMessage());
            result.setRiskLevel("MEDIUM");

            log.debug("[DatasourceAccessService] JDBC SQL验证失败: datasourceId={}, error={}", datasourceId, e.getMessage());
        }

        return result;
    }

    // ==================== 元数据查询 ====================

    /**
     * 批量查询表结构（用于元数据同步）
     *
     * @param datasourceId  数据源 ID
     * @param tableNames    表名列表（为空则查询所有表）
     * @param schemaPattern Schema 匹配模式（可选）
     * @return 元数据结果
     */
    public SchemaMetadataResult batchQuerySchema(Long datasourceId, List<String> tableNames, String schemaPattern) {
        if (mcpEnabled && mcpClientService != null && mcpClientService.isAvailable()) {
            return batchQuerySchemaViaMcp(datasourceId, tableNames, schemaPattern);
        } else {
            return batchQuerySchemaViaJdbc(datasourceId, tableNames, schemaPattern);
        }
    }

    private SchemaMetadataResult batchQuerySchemaViaMcp(Long datasourceId, List<String> tableNames, String schemaPattern) {
        try {
            String response = mcpClientService.batchQuerySchema(datasourceId, tableNames, schemaPattern);
            Map<String, Object> parsed = objectMapper.readValue(response, new TypeReference<>() {});

            SchemaMetadataResult result = new SchemaMetadataResult();
            result.setDatasourceId(datasourceId);
            result.setTableCount(parsed.get("table_count") != null ? ((Number) parsed.get("table_count")).intValue() : 0);
            result.setTotalColumns(parsed.get("total_columns") != null ? ((Number) parsed.get("total_columns")).intValue() : 0);
            result.setRelationshipCount(parsed.get("relationship_count") != null ? ((Number) parsed.get("relationship_count")).intValue() : 0);
            result.setRelationships((List<Map<String, Object>>) parsed.get("relationships"));

            // 解析表结构
            List<Map<String, Object>> tablesRaw = (List<Map<String, Object>>) parsed.get("tables");
            List<TableSchema> tables = new ArrayList<>();
            if (tablesRaw != null) {
                for (Map<String, Object> tableRaw : tablesRaw) {
                    TableSchema table = new TableSchema();
                    table.setTableName(String.valueOf(tableRaw.get("table_name")));
                    table.setTableComment(tableRaw.get("table_comment") != null ? String.valueOf(tableRaw.get("table_comment")) : "");
                    table.setColumnCount(tableRaw.get("column_count") != null ? ((Number) tableRaw.get("column_count")).intValue() : 0);
                    table.setPrimaryKeys((List<String>) tableRaw.get("primary_keys"));

                    // 解析列
                    List<Map<String, Object>> columnsRaw = (List<Map<String, Object>>) tableRaw.get("columns");
                    List<ColumnSchema> columns = new ArrayList<>();
                    if (columnsRaw != null) {
                        for (Map<String, Object> colRaw : columnsRaw) {
                            ColumnSchema col = new ColumnSchema();
                            col.setName(String.valueOf(colRaw.get("name")));
                            col.setType(String.valueOf(colRaw.get("type")));
                            col.setSize(colRaw.get("size") != null ? ((Number) colRaw.get("size")).intValue() : 0);
                            col.setNullable(Boolean.TRUE.equals(colRaw.get("nullable")));
                            col.setComment(colRaw.get("comment") != null ? String.valueOf(colRaw.get("comment")) : "");
                            col.setDefaultValue(colRaw.get("default_value") != null ? String.valueOf(colRaw.get("default_value")) : null);
                            columns.add(col);
                        }
                    }
                    table.setColumns(columns);
                    tables.add(table);
                }
            }
            result.setTables(tables);

            log.info("[DatasourceAccessService] MCP 元数据查询成功: datasourceId={}, tables={}", datasourceId, result.getTableCount());
            return result;
        } catch (Exception e) {
            log.error("[DatasourceAccessService] MCP 元数据查询失败，降级到JDBC: datasourceId={}", datasourceId, e);
            return batchQuerySchemaViaJdbc(datasourceId, tableNames, schemaPattern);
        }
    }

    private SchemaMetadataResult batchQuerySchemaViaJdbc(Long datasourceId, List<String> tableNames, String schemaPattern) {
        // JDBC 模式下，元数据同步仍走 MetadataCollectorService 的原有逻辑
        // 此方法仅作为 MCP 降级后的兜底，抛出异常让调用方走原有路径
        throw new UnsupportedOperationException(
            "JDBC 模式下的元数据同步请使用 MetadataCollectorService，DatasourceAccessService 仅在 MCP 模式下提供元数据查询"
        );
    }

    // ==================== 工具方法 ====================

    /**
     * 判断是否启用 MCP 模式
     */
    public boolean isMcpEnabled() {
        return mcpEnabled && mcpClientService != null && mcpClientService.isAvailable();
    }

    /**
     * 获取数据源的 JdbcTemplate（JDBC 模式专用）
     * MCP 模式下不应调用此方法
     */
    public JdbcTemplate getJdbcTemplate(Long datasourceId) {
        return dataSourceManager.getJdbcTemplate(datasourceId);
    }
}
