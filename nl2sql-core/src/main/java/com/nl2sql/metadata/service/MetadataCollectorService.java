package com.nl2sql.metadata.service;

import com.nl2sql.metadata.entity.ColumnMetadata;
import com.nl2sql.metadata.entity.DataSourceConfig;
import com.nl2sql.metadata.entity.TableMetadata;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class MetadataCollectorService {
    
    private final JdbcTemplate localJdbcTemplate;
    private final DataSourceConfigService dataSourceConfigService;
    
    public MetadataCollectorService(JdbcTemplate jdbcTemplate, DataSourceConfigService dataSourceConfigService) {
        this.localJdbcTemplate = jdbcTemplate;
        this.dataSourceConfigService = dataSourceConfigService;
    }
    
    /**
     * 全量同步元数据
     */
    @Transactional
    public SyncResult syncAllMetadata(Long datasourceId, Long operatorId) {
        SyncResult result = new SyncResult();
        result.setDatasourceId(datasourceId);
        result.setStartedAt(java.time.LocalDateTime.now());
        
        try {
            // 获取数据源配置
            DataSourceConfig config = dataSourceConfigService.getConfigById(datasourceId);
            if (config == null) {
                throw new IllegalArgumentException("数据源不存在: " + datasourceId);
            }
            
            log.info("开始同步元数据: datasourceId={}, name={}", datasourceId, config.getName());
            
            // 建立远程连接
            Connection remoteConn = createRemoteConnection(config);
            
            try {
                // 1. 采集表列表
                List<TableMetadata> tables = collectTables(remoteConn, datasourceId);
                result.setTableCount(tables.size());
                
                // 2. 清空旧数据（在事务内，确保原子性）
                clearOldMetadata(datasourceId);
                
                // 3. 保存表元数据
                saveTableMetadata(tables);
                
                // 4. 采集字段和外键
                int totalColumns = 0;
                int totalForeignKeys = 0;
                
                for (TableMetadata table : tables) {
                    List<ColumnMetadata> columns = collectColumns(remoteConn, datasourceId, table.getTableName(), table.getTableComment());
                    saveColumnMetadata(columns);
                    totalColumns += columns.size();
                    
                    // TODO: 采集外键（简化版暂不实现）
                }
                
                result.setColumnCount(totalColumns);
                result.setForeignKeyCount(totalForeignKeys);
                result.setStatus("SUCCESS");
                
                log.info("元数据同步成功: tables={}, columns={}", tables.size(), totalColumns);
                
            } finally {
                closeConnection(remoteConn);
            }
            
        } catch (Exception e) {
            log.error("元数据同步失败", e);
            result.setStatus("FAILED");
            result.setErrorMessage(e.getMessage());
            throw new RuntimeException("元数据同步失败: " + e.getMessage(), e);
        } finally {
            result.setCompletedAt(java.time.LocalDateTime.now());
            result.setDurationSeconds(
                (int) java.time.Duration.between(result.getStartedAt(), result.getCompletedAt()).getSeconds()
            );
            result.setCreatedBy(operatorId);
            
            // 记录日志
            saveSyncLog(result);
        }
        
        return result;
    }
    
    /**
     * 采集表列表
     */
    private List<TableMetadata> collectTables(Connection conn, Long datasourceId) throws SQLException {
        List<TableMetadata> tables = new ArrayList<>();
        DatabaseMetaData metaData = conn.getMetaData();
        
        ResultSet rs = metaData.getTables(null, null, "%", new String[]{"TABLE", "VIEW"});
        
        while (rs.next()) {
            String tableName = rs.getString("TABLE_NAME");
            String schemaName = rs.getString("TABLE_SCHEM");
            
            // 过滤系统表
            if (isSystemTable(tableName, schemaName)) {
                continue;
            }
            
            TableMetadata table = new TableMetadata();
            table.setDatasourceId(datasourceId);
            table.setTableName(tableName);
            table.setTableType(rs.getString("TABLE_TYPE"));
            
            // 使用SQL查询获取正确的注释（避免DatabaseMetaData编码问题）
            String comment = getTableComment(conn, tableName);
            table.setTableComment(comment);
            
            table.setSchemaName(schemaName);
            tables.add(table);
        }
        
        rs.close();
        log.info("采集到 {} 个业务表（已过滤系统表）", tables.size());
        
        return tables;
    }
    
    /**
     * 判断是否为系统表
     */
    private boolean isSystemTable(String tableName, String schemaName) {
        if (tableName == null) {
            return true;
        }
        
        // MySQL系统库
        if (schemaName != null) {
            String schemaLower = schemaName.toLowerCase();
            if (schemaLower.equals("information_schema") ||
                schemaLower.equals("mysql") ||
                schemaLower.equals("performance_schema") ||
                schemaLower.equals("sys")) {
                return true;
            }
        }
        
        // 常见系统表前缀（包括MySQL sys库视图、performance_schema表）
        String tableLower = tableName.toLowerCase();
        if (tableLower.startsWith("sys_") ||
            tableLower.startsWith("x$") ||
            tableLower.startsWith("v$_") ||
            tableLower.startsWith("gv$_") ||
            tableLower.startsWith("dba_") ||
            tableLower.startsWith("all_") ||
            tableLower.equals("user_tables") ||  // Oracle系统表
            tableLower.equals("user_objects") ||  // Oracle系统表
            tableLower.equals("user_constraints") ||  // Oracle系统表
            tableLower.startsWith("user_summary") ||  // MySQL performance_schema视图
            tableLower.startsWith("session") ||
            tableLower.startsWith("global_") ||
            tableLower.startsWith("innodb_") ||
            tableLower.startsWith("memory_") ||
            tableLower.startsWith("io_") ||
            tableLower.startsWith("host_") ||
            tableLower.startsWith("latest_") ||
            tableLower.startsWith("processlist") ||
            tableLower.startsWith("schema_") ||
            tableLower.startsWith("wait") ||
            tableLower.startsWith("statement") ||
            tableLower.startsWith("metrics") ||
            tableLower.startsWith("ps_check") ||
            tableLower.startsWith("version")) {
            return true;
        }
        
        return false;
    }
    
    /**
     * 采集字段信息
     */
    private List<ColumnMetadata> collectColumns(Connection conn, Long datasourceId, String tableName, String tableComment) throws SQLException {
        List<ColumnMetadata> columns = new ArrayList<>();
        DatabaseMetaData metaData = conn.getMetaData();
        
        ResultSet rs = metaData.getColumns(null, null, tableName, "%");
        
        // 检测可用字段（兼容不同MySQL版本）
        ResultSetMetaData rsMeta = rs.getMetaData();
        java.util.Set<String> availableColumns = new java.util.HashSet<>();
        for (int i = 1; i <= rsMeta.getColumnCount(); i++) {
            availableColumns.add(rsMeta.getColumnName(i).toUpperCase());
        }
        
        while (rs.next()) {
            ColumnMetadata column = new ColumnMetadata();
            column.setDatasourceId(datasourceId);
            column.setTableName(tableName);
            column.setTableComment(tableComment);  // 设置表注释
            column.setColumnName(rs.getString("COLUMN_NAME"));
            column.setDataType(rs.getString("TYPE_NAME"));
            column.setColumnSize(rs.getInt("COLUMN_SIZE"));
            column.setDecimalDigits(rs.getInt("DECIMAL_DIGITS"));
            column.setIsNullable("YES".equals(rs.getString("IS_NULLABLE")) ? 1 : 0);
            column.setColumnDefault(rs.getString("COLUMN_DEF"));
            
            // 使用SQL查询获取正确的注释
            String columnComment = getColumnComment(conn, tableName, rs.getString("COLUMN_NAME"));
            column.setColumnComment(columnComment);
            
            column.setOrdinalPosition(rs.getInt("ORDINAL_POSITION"));
            
            // 兼容不同MySQL版本的字符集字段
            if (availableColumns.contains("CHARACTER_SET_NAME")) {
                try {
                    column.setCharacterSetName(rs.getString("CHARACTER_SET_NAME"));
                } catch (SQLException e) {
                    column.setCharacterSetName(null);
                }
            } else {
                column.setCharacterSetName(null);
            }
            
            // 排序规则字段（可选）
            if (availableColumns.contains("COLLATION_NAME")) {
                try {
                    column.setCollationName(rs.getString("COLLATION_NAME"));
                } catch (SQLException e) {
                    column.setCollationName(null);
                }
            }
            
            columns.add(column);
        }
        
        rs.close();
        
        // 标记主键
        markPrimaryKeys(conn, datasourceId, tableName, columns);
        
        return columns;
    }
    
    /**
     * 标记主键字段
     */
    private void markPrimaryKeys(Connection conn, Long datasourceId, String tableName, List<ColumnMetadata> columns) throws SQLException {
        DatabaseMetaData metaData = conn.getMetaData();
        ResultSet pkRs = metaData.getPrimaryKeys(null, null, tableName);
        
        List<String> pkColumns = new ArrayList<>();
        while (pkRs.next()) {
            pkColumns.add(pkRs.getString("COLUMN_NAME"));
        }
        pkRs.close();
        
        for (ColumnMetadata column : columns) {
            if (pkColumns.contains(column.getColumnName())) {
                column.setIsPrimaryKey(1);
            }
        }
    }
    
    /**
     * 获取表注释（通过SQL查询避免编码问题）
     */
    private String getTableComment(Connection conn, String tableName) throws SQLException {
        String sql = "SELECT TABLE_COMMENT FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?";
        try (java.sql.PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tableName);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("TABLE_COMMENT");
                }
            }
        }
        return null;
    }
    
    /**
     * 获取字段注释（通过SQL查询避免编码问题）
     */
    private String getColumnComment(Connection conn, String tableName, String columnName) throws SQLException {
        String sql = "SELECT COLUMN_COMMENT FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?";
        try (java.sql.PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tableName);
            ps.setString(2, columnName);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("COLUMN_COMMENT");
                }
            }
        }
        return null;
    }
    
    /**
     * 保存表元数据（使用 INSERT IGNORE 避免重复键冲突）
     */
    private void saveTableMetadata(List<TableMetadata> tables) {
        String sql = "INSERT IGNORE INTO table_metadata (datasource_id, table_name, table_comment, table_type, schema_name) VALUES (?, ?, ?, ?, ?)";
        
        for (TableMetadata table : tables) {
            localJdbcTemplate.update(sql,
                table.getDatasourceId(),
                table.getTableName(),
                table.getTableComment(),
                table.getTableType(),
                table.getSchemaName()
            );
        }
    }
    
    /**
     * 保存字段元数据（使用 INSERT IGNORE 避免重复键冲突）
     */
    private void saveColumnMetadata(List<ColumnMetadata> columns) {
        String sql = "INSERT IGNORE INTO column_metadata (datasource_id, table_name, table_comment, column_name, data_type, column_size, decimal_digits, is_nullable, column_default, column_comment, is_primary_key, ordinal_position, character_set_name) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        
        for (ColumnMetadata column : columns) {
            localJdbcTemplate.update(sql,
                column.getDatasourceId(),
                column.getTableName(),
                column.getTableComment(),
                column.getColumnName(),
                column.getDataType(),
                column.getColumnSize(),
                column.getDecimalDigits(),
                column.getIsNullable(),
                column.getColumnDefault(),
                column.getColumnComment(),
                column.getIsPrimaryKey() != null ? column.getIsPrimaryKey() : 0,
                column.getOrdinalPosition(),
                column.getCharacterSetName()
            );
        }
    }
    
    /**
     * 清空旧元数据
     */
    private void clearOldMetadata(Long datasourceId) {
        localJdbcTemplate.update("DELETE FROM column_metadata WHERE datasource_id = ?", datasourceId);
        localJdbcTemplate.update("DELETE FROM table_metadata WHERE datasource_id = ?", datasourceId);
        log.info("已清空数据源 {} 的旧元数据", datasourceId);
    }
    
    /**
     * 保存同步日志
     */
    private void saveSyncLog(SyncResult result) {
        String sql = "INSERT INTO metadata_sync_log (datasource_id, sync_status, table_count, column_count, foreign_key_count, error_message, started_at, completed_at, duration_seconds, created_by) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        
        localJdbcTemplate.update(sql,
            result.getDatasourceId(),
            result.getStatus(),
            result.getTableCount(),
            result.getColumnCount(),
            result.getForeignKeyCount(),
            result.getErrorMessage(),
            result.getStartedAt(),
            result.getCompletedAt(),
            result.getDurationSeconds(),
            result.getCreatedBy()
        );
    }
    
    /**
     * 创建远程数据库连接
     */
    private Connection createRemoteConnection(DataSourceConfig config) throws SQLException {
        String url = buildJdbcUrl(config);
        return DriverManager.getConnection(url, config.getUsername(), config.getPassword());
    }
    
    /**
     * 构建JDBC URL
     */
    private String buildJdbcUrl(DataSourceConfig config) {
        switch (config.getDbType().toUpperCase()) {
            case "MYSQL":
                return String.format("jdbc:mysql://%s:%d/%s?useUnicode=true&characterEncoding=utf8&connectionCollation=utf8mb4_unicode_ci&useSSL=false&serverTimezone=Asia/Shanghai",
                    config.getHost(), config.getPort(), config.getDatabaseName());
            case "ORACLE":
                return String.format("jdbc:oracle:thin:@%s:%d:%s",
                    config.getHost(), config.getPort(), config.getDatabaseName());
            case "DAMENG":
                return String.format("jdbc:dm://%s:%d/%s",
                    config.getHost(), config.getPort(), config.getDatabaseName());
            case "POSTGRESQL":
                return String.format("jdbc:postgresql://%s:%d/%s",
                    config.getHost(), config.getPort(), config.getDatabaseName());
            default:
                throw new IllegalArgumentException("不支持的数据库类型: " + config.getDbType());
        }
    }
    
    /**
     * 关闭连接
     */
    private void closeConnection(Connection conn) {
        if (conn != null) {
            try {
                conn.close();
            } catch (SQLException e) {
                log.error("关闭连接失败", e);
            }
        }
    }
    
    @Data
    public static class SyncResult {
        private Long datasourceId;
        private String status;
        private Integer tableCount;
        private Integer columnCount;
        private Integer foreignKeyCount;
        private String errorMessage;
        private java.time.LocalDateTime startedAt;
        private java.time.LocalDateTime completedAt;
        private Integer durationSeconds;
        private Long createdBy;
    }
}
