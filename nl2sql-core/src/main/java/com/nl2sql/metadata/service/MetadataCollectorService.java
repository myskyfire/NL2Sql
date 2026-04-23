package com.nl2sql.metadata.service;

import com.nl2sql.core.llm.MultiModelService;
import com.nl2sql.metadata.entity.ColumnMetadata;
import com.nl2sql.metadata.entity.DataSourceConfig;
import com.nl2sql.metadata.entity.TableMetadata;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.*;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
public class MetadataCollectorService {
    
    private final JdbcTemplate localJdbcTemplate;
    private final DataSourceConfigService dataSourceConfigService;
    
    @Autowired(required = false)
    private MultiModelService multiModelService;
    
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
                
                // ✅ 新增：异步增强表描述和列描述（不阻塞主流程）
                enhanceTableDescriptionsAsync(datasourceId);
                // 延迟30秒后启动列增强，确保表增强已完成
                new Thread(() -> {
                    try {
                        Thread.sleep(30000); // 等待30秒
                        enhanceColumnDescriptionsAsync(datasourceId);
                    } catch (InterruptedException e) {
                        log.warn("[元数据增强] 列增强延迟启动被中断", e);
                    }
                }, "column-enhance-delay-thread").start();
                
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
        
        // ✅ 关键修复：获取当前连接的数据库名，只采集该库的表
        String currentSchema = conn.getCatalog();
        log.info("开始采集元数据: datasourceId={}, schema={}", datasourceId, currentSchema);
        
        // 指定 schema 参数，避免跨库污染
        ResultSet rs = metaData.getTables(currentSchema, currentSchema, "%", new String[]{"TABLE", "VIEW"});
        
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
        
        // ✅ 指定 schema，避免跨库
        String currentSchema = conn.getCatalog();
        ResultSet rs = metaData.getColumns(currentSchema, currentSchema, tableName, "%");
        
        // 检测可用字段（兼容不同MySQL版本）
        ResultSetMetaData rsMeta = rs.getMetaData();
        Set<String> availableColumns = new HashSet<>();
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
        
        // ✅ 指定 schema，避免跨库
        String currentSchema = conn.getCatalog();
        ResultSet pkRs = metaData.getPrimaryKeys(currentSchema, currentSchema, tableName);
        
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
    
    /**
     * ✅ 新增：异步增强表描述（使用LLM生成业务化表描述）
     */
    public void enhanceTableDescriptionsAsync(Long datasourceId) {
        // 异步执行，不阻塞主流程
        new Thread(() -> {
            try {
                log.info("[元数据增强] 开始为数据源 {} 执行表描述增强", datasourceId);
                    
                // 获取所有表
                String sql = "SELECT table_name, table_comment FROM table_metadata WHERE datasource_id = ?";
                List<Map<String, Object>> tables = localJdbcTemplate.queryForList(sql, datasourceId);
                    
                if (tables.isEmpty()) {
                    log.warn("[元数据增强] 数据源 {} 没有表元数据", datasourceId);
                    return;
                }
                    
                log.info("[元数据增强] 共找到 {} 个表", tables.size());
                    
                for (Map<String, Object> table : tables) {
                    try {
                        String tableName = (String) table.get("table_name");
                        String currentComment = (String) table.get("table_comment");
                            
                        // 获取字段信息(包含完整元数据)
                        String colSql = "SELECT column_name, data_type, column_size, is_nullable, column_default, column_comment, is_primary_key, ordinal_position " +
                                       "FROM column_metadata WHERE datasource_id = ? AND table_name = ? ORDER BY ordinal_position";
                        List<Map<String, Object>> columns = localJdbcTemplate.queryForList(colSql, datasourceId, tableName);
                            
                        if (columns.isEmpty()) {
                            continue;
                        }
                            
                        // 构建LLM提示词 - 提供完整表元数据
                        StringBuilder prompt = new StringBuilder();
                        prompt.append("你是一个数据库专家和业务分析师。请根据以下完整的表结构信息，生成一段简洁的业务化表描述(50字以内)。\n\n");
                        prompt.append("=== 表基本信息 ===\n");
                        prompt.append("表名: ").append(tableName).append("\n");
                        prompt.append("当前描述: ").append(currentComment != null ? currentComment : "无").append("\n\n");
                            
                        prompt.append("=== 字段详细信息 ===\n");
                        for (Map<String, Object> col : columns) {
                            prompt.append(String.format("- %s (%s", 
                                col.get("column_name"), 
                                col.get("data_type")));
                                
                            // 添加字段长度
                            if (col.get("column_size") != null) {
                                prompt.append(", 长度:").append(col.get("column_size"));
                            }
                                
                            // 是否主键
                            if ("1".equals(String.valueOf(col.get("is_primary_key")))) {
                                prompt.append(", [主键]");
                            }
                                
                            // 是否可空
                            if ("0".equals(String.valueOf(col.get("is_nullable")))) {
                                prompt.append(", 非空");
                            }
                                
                            // 默认值
                            if (col.get("column_default") != null) {
                                prompt.append(", 默认:").append(col.get("column_default"));
                            }
                                
                            prompt.append(")");
                                
                            // 字段说明
                            if (col.get("column_comment") != null && !col.get("column_comment").toString().isEmpty()) {
                                prompt.append(": ").append(col.get("column_comment"));
                            } else {
                                prompt.append(": 无说明");
                            }
                                
                            prompt.append("\n");
                        }
                            
                        prompt.append("\n=== 生成要求 ===\n");
                        prompt.append("1. **识别核心业务指标**: 从字段中识别关键业务概念(如订单量、销售额、用户数、库存量等)\n");
                        prompt.append("2. **说明表的业务用途**: 这张表在业务系统中扮演什么角色\n");
                        prompt.append("3. **突出关联关系**: 如果有外键或关联字段,说明与其他表的关系\n");
                        prompt.append("4. **包含检索关键词**: 确保描述包含用户可能查询的业务术语\n");
                        prompt.append("5. **简洁专业**: 50字以内,便于向量检索匹配\n");
                        prompt.append("6. **只返回描述文本**: 不要任何解释、前缀或其他内容\n\n");
                        prompt.append("生成的描述:");
                            
                        // 调用LLM生成描述
                        String enhancedDesc = callLLM(prompt.toString());
                        if (enhancedDesc != null && enhancedDesc.length() > 10) {
                            updateTableComment(datasourceId, tableName, enhancedDesc);
                            log.info("[元数据增强] 表 {} 描述已更新: {}", tableName, enhancedDesc);
                        }
                            
                    } catch (Exception e) {
                        log.warn("[元数据增强] 表增强失败: {}", e.getMessage());
                    }
                }
                    
                log.info("[元数据增强] 完成");
                    
            } catch (Exception e) {
                log.error("[元数据增强] 异常", e);
            }
        }, "metadata-enhance-thread").start();
    }
        
    /**
     * ✅ 新增：异步增强列描述（使用LLM生成业务化字段注释）
     */
    public void enhanceColumnDescriptionsAsync(Long datasourceId) {
        // 异步执行，不阻塞主流程
        new Thread(() -> {
            try {
                log.info("[列元数据增强] 开始为数据源 {} 执行字段描述增强", datasourceId);
                    
                // 获取所有表
                String tableSql = "SELECT table_name, table_comment FROM table_metadata WHERE datasource_id = ?";
                List<Map<String, Object>> tables = localJdbcTemplate.queryForList(tableSql, datasourceId);
                    
                if (tables.isEmpty()) {
                    log.warn("[列元数据增强] 数据源 {} 没有表元数据", datasourceId);
                    return;
                }
                    
                int totalEnhanced = 0;
                    
                for (Map<String, Object> table : tables) {
                    String tableName = (String) table.get("table_name");
                    String tableComment = (String) table.get("table_comment");
                        
                    try {
                        // 获取字段信息
                        String colSql = "SELECT column_name, data_type, column_size, is_nullable, column_default, column_comment, is_primary_key " +
                                       "FROM column_metadata WHERE datasource_id = ? AND table_name = ? ORDER BY ordinal_position";
                        List<Map<String, Object>> columns = localJdbcTemplate.queryForList(colSql, datasourceId, tableName);
                            
                        if (columns.isEmpty()) {
                            continue;
                        }
                            
                        // 对每个字段调用LLM增强
                        for (Map<String, Object> col : columns) {
                            String columnName = (String) col.get("column_name");
                            String currentComment = (String) col.get("column_comment");
                            String dataType = (String) col.get("data_type");
                                
                            // 如果已有注释且不为空，跳过（保留手动修改）
                            if (currentComment != null && !currentComment.trim().isEmpty() && !currentComment.equals("无说明")) {
                                continue;
                            }
                                
                            try {
                                // 构建LLM提示词
                                StringBuilder prompt = new StringBuilder();
                                prompt.append("你是一个数据库专家。请根据以下信息，为该字段生成一个简洁的中文业务注释(20字以内)。\n\n");
                                prompt.append("表名: ").append(tableName).append("\n");
                                if (tableComment != null && !tableComment.trim().isEmpty()) {
                                    prompt.append("表说明: ").append(tableComment).append("\n");
                                }
                                prompt.append("字段名: ").append(columnName).append("\n");
                                prompt.append("数据类型: ").append(dataType).append("\n");
                                    
                                if (col.get("column_size") != null) {
                                    prompt.append("字段长度: ").append(col.get("column_size")).append("\n");
                                }
                                if ("1".equals(String.valueOf(col.get("is_primary_key")))) {
                                    prompt.append("约束: 主键\n");
                                }
                                if ("0".equals(String.valueOf(col.get("is_nullable")))) {
                                    prompt.append("约束: 非空\n");
                                }
                                if (col.get("column_default") != null) {
                                    prompt.append("默认值: ").append(col.get("column_default")).append("\n");
                                }
                                    
                                prompt.append("\n要求:\n");
                                prompt.append("1. 根据字段名推测业务含义(如order_amount→订单金额,user_name→用户名)\n");
                                prompt.append("2. 结合表说明理解字段的业务场景\n");
                                prompt.append("3. 简洁准确,20字以内\n");
                                prompt.append("4. 只返回注释文本,不要其他内容\n\n");
                                prompt.append("生成的注释:");
                                    
                                // 调用LLM
                                String enhancedComment = callLLM(prompt.toString());
                                if (enhancedComment != null && enhancedComment.length() > 2 && enhancedComment.length() <= 20) {
                                    updateColumnComment(datasourceId, tableName, columnName, enhancedComment);
                                    totalEnhanced++;
                                    log.debug("[列元数据增强] {}.{} → {}", tableName, columnName, enhancedComment);
                                }
                                    
                                // 避免频繁调用LLM
                                Thread.sleep(500);
                                    
                            } catch (Exception e) {
                                log.warn("[列元数据增强] 字段 {}.{} 增强失败: {}", tableName, columnName, e.getMessage());
                            }
                        }
                            
                    } catch (Exception e) {
                        log.warn("[列元数据增强] 表 {} 处理失败: {}", tableName, e.getMessage());
                    }
                }
                    
                log.info("[列元数据增强] 完成，共增强 {} 个字段", totalEnhanced);
                    
            } catch (Exception e) {
                log.error("[列元数据增强] 异常", e);
            }
        }, "column-enhance-thread").start();
    }
    
    /**
     * 调用LLM生成表描述
     */
    private String callLLM(String prompt) {
        if (multiModelService == null) {
            log.warn("[元数据增强] MultiModelService未注入,跳过LLM调用");
            return null;
        }
        
        try {
            // 使用Ollama本地模型 - 直接调用llmService
            String response = multiModelService.getLlmService().generateAnswer(prompt);
            return response != null ? response.trim() : null;
        } catch (Exception e) {
            log.error("[元数据增强] LLM调用失败", e);
            return null;
        }
    }
    
    /**
     * 更新表注释
     */
    private void updateTableComment(Long datasourceId, String tableName, String comment) {
        String sql = "UPDATE table_metadata SET table_comment = ? WHERE datasource_id = ? AND table_name = ?";
        localJdbcTemplate.update(sql, comment, datasourceId, tableName);
    }
    
    /**
     * 更新字段注释
     */
    private void updateColumnComment(Long datasourceId, String tableName, String columnName, String comment) {
        String sql = "UPDATE column_metadata SET column_comment = ? WHERE datasource_id = ? AND table_name = ? AND column_name = ?";
        localJdbcTemplate.update(sql, comment, datasourceId, tableName, columnName);
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
