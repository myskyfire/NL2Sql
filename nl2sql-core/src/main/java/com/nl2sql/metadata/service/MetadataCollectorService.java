package com.nl2sql.metadata.service;

import com.nl2sql.core.llm.MultiModelService;
import com.nl2sql.core.mapper.MetadataCollectorMapper;
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
import java.util.HashMap;
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
    
    @Autowired
    private MetadataCollectorMapper metadataCollectorMapper;
    
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
                
                // ✅ 新增：异步增强表描述（不阻塞主流程）
                enhanceTableDescriptionsAsync(datasourceId);
                // ❌ 已移除：列增强改为按需触发（反馈驱动 + 定时批量）
                
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
            
            // ✅ 新增：如果无注释，尝试自动推断（零成本）
            String commentSource = "MANUAL";
            if (columnComment == null || columnComment.trim().isEmpty()) {
                columnComment = inferColumnComment(rs.getString("COLUMN_NAME"), rs.getString("TYPE_NAME"));
                if (columnComment != null) {
                    commentSource = "INFERRED";  // 标记为规则推断
                }
            }
            
            column.setColumnComment(columnComment);
            column.setCommentSource(commentSource);
            
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
        for (TableMetadata table : tables) {
            metadataCollectorMapper.insertTableMetadata(
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
        for (ColumnMetadata column : columns) {
            metadataCollectorMapper.insertColumnMetadata(
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
                column.getCharacterSetName(),
                column.getCommentSource() != null ? column.getCommentSource() : "MANUAL"
            );
        }
    }
    
    /**
     * 清空旧元数据
     */
    private void clearOldMetadata(Long datasourceId) {
        metadataCollectorMapper.clearColumnMetadata(datasourceId);
        metadataCollectorMapper.clearTableMetadata(datasourceId);
        log.info("已清空数据源 {} 的旧元数据", datasourceId);
    }
    
    /**
     * 保存同步日志
     */
    private void saveSyncLog(SyncResult result) {
        metadataCollectorMapper.insertSyncLog(
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
                    
                // ✅ 使用Mapper获取所有表
                List<Map<String, Object>> tables = metadataCollectorMapper.getTableMetadata(datasourceId);
                    
                if (tables.isEmpty()) {
                    log.warn("[元数据增强] 数据源 {} 没有表元数据", datasourceId);
                    return;
                }
                    
                log.info("[元数据增强] 共找到 {} 个表", tables.size());
                    
                for (Map<String, Object> table : tables) {
                    try {
                        String tableName = (String) table.get("table_name");
                        String currentComment = (String) table.get("table_comment");
                            
                        // ✅ 使用Mapper获取字段信息
                        List<Map<String, Object>> columns = metadataCollectorMapper.getColumnMetadata(datasourceId);
                        // 过滤出当前表的字段
                        columns = columns.stream()
                            .filter(col -> tableName.equals(col.get("table_name")))
                            .sorted((a, b) -> Integer.compare(
                                ((Number) a.get("ordinal_position")).intValue(),
                                ((Number) b.get("ordinal_position")).intValue()
                            ))
                            .collect(java.util.stream.Collectors.toList());
                            
                        if (columns.isEmpty()) {
                            continue;
                        }
                            
                        // 构建LLM提示词 - 提供完整表元数据
                        StringBuilder prompt = new StringBuilder();
                        prompt.append("你是数据库专家和业务分析师。根据表结构信息，生成简洁的业务化表描述(50字以内)。\n\n");
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
                            
                        prompt.append("\n要求:\n");
                        prompt.append("1. **识别核心业务指标**: 从字段中识别关键业务概念(如订单量、销售额、用户数等)\n");
                        prompt.append("2. **说明表的业务用途**: 这张表在业务系统中的角色\n");
                        prompt.append("3. **突出关联关系**: 如果有外键或关联字段,说明与其他表的关系\n");
                        prompt.append("4. **包含检索关键词**: 确保描述包含用户可能查询的业务术语\n");
                        prompt.append("5. **简洁专业**: 50字以内,便于向量检索匹配\n");
                        prompt.append("6. **只返回描述文本**: 不要任何解释、前缀或其他内容\n\n");
                        prompt.append("生成的描述:");
                            
                        // 调用LLM生成描述
                        String enhancedDesc = callLLM(prompt.toString());
                        
                        // ✅ 修复：LLM返回null或内容过短时跳过更新
                        if (enhancedDesc != null && enhancedDesc.length() > 10) {
                            updateTableComment(datasourceId, tableName, enhancedDesc);
                            log.info("[元数据增强] 表 {} 描述已更新: {}", tableName, enhancedDesc);
                        } else {
                            log.warn("[元数据增强] 表 {} LLM返回无效内容，跳过更新", tableName);
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
                    
                // ✅ 使用Mapper获取所有表
                List<Map<String, Object>> tables = metadataCollectorMapper.getTableMetadata(datasourceId);
                    
                if (tables.isEmpty()) {
                    log.warn("[列元数据增强] 数据源 {} 没有表元数据", datasourceId);
                    return;
                }
                    
                int totalEnhanced = 0;
                    
                for (Map<String, Object> table : tables) {
                    String tableName = (String) table.get("table_name");
                    String tableComment = (String) table.get("table_comment");
                        
                    try {
                        // ✅ 使用Mapper获取字段信息
                        List<Map<String, Object>> columns = metadataCollectorMapper.getColumnMetadata(datasourceId);
                        // 过滤出当前表的字段
                        columns = columns.stream()
                            .filter(col -> tableName.equals(col.get("table_name")))
                            .collect(java.util.stream.Collectors.toList());
                            
                        if (columns.isEmpty()) {
                            continue;
                        }
                            
                        // 对每个字段调用LLM增强
                        for (Map<String, Object> col : columns) {
                            String columnName = (String) col.get("column_name");
                            String currentComment = (String) col.get("column_comment");
                            String dataType = (String) col.get("data_type");
                                
                            // ✅ 优化1：跳过已有优质注释的字段
                            if (currentComment != null && !currentComment.trim().isEmpty() && !currentComment.equals("无说明")) {
                                continue;
                            }
                            
                            // ✅ 优化2：跳过常见技术字段（无需业务解释）
                            if (shouldSkipColumn(columnName)) {
                                log.debug("[列元数据增强] {}.{} 为技术字段，跳过", tableName, columnName);
                                continue;
                            }
                            
                            // ✅ 优化3：尝试规则推断（零成本）
                            String inferredComment = inferColumnComment(columnName, dataType);
                            if (inferredComment != null) {
                                updateColumnCommentWithSource(datasourceId, tableName, columnName, inferredComment, "INFERRED");
                                totalEnhanced++;
                                log.debug("[列元数据增强] {}.{} 规则推断: {}", tableName, columnName, inferredComment);
                                continue;
                            }
                                
                            try {
                                // 构建LLM提示词
                                StringBuilder prompt = new StringBuilder();
                                prompt.append("你是数据库专家。根据以下信息，为该字段生成简洁的中文业务注释(20字以内)。\n\n");
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
                                
                                // ✅ 修复：LLM返回null或长度不符合要求时跳过
                                if (enhancedComment != null && enhancedComment.length() > 2 && enhancedComment.length() <= 20) {
                                    updateColumnComment(datasourceId, tableName, columnName, enhancedComment);
                                    totalEnhanced++;
                                    log.debug("[列元数据增强] {}.{} → {}", tableName, columnName, enhancedComment);
                                } else {
                                    log.debug("[列元数据增强] {}.{} LLM返回无效内容，跳过", tableName, columnName);
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
        metadataCollectorMapper.updateTableComment(datasourceId, tableName, comment);
    }
    
    /**
     * 更新字段注释
     */
    private void updateColumnComment(Long datasourceId, String tableName, String columnName, String comment) {
        metadataCollectorMapper.updateColumnComment(datasourceId, tableName, columnName, comment);
    }
    
    /**
     * ✅ 新增：判断是否跳过字段增强（技术字段无需LLM）
     */
    private boolean shouldSkipColumn(String columnName) {
        if (columnName == null || columnName.isEmpty()) {
            return false;
        }
        
        String lowerName = columnName.toLowerCase();
        
        // 1. 纯ID类字段（已有注释说明是主键/外键）
        if (lowerName.matches("^(id|uuid|guid)$")) {
            return true;
        }
        
        // 2. 时间戳字段（命名规范清晰）
        if (lowerName.matches("^(created_at|create_time|updated_at|update_time|modified_at|deleted_at)$")) {
            return true;
        }
        
        // 3. 多语言后缀字段（如name_en, name_zh）
        if (lowerName.matches(".*_(en|zh|cn|us|jp|kr)$")) {
            return true;
        }
        
        // 4. 布尔标志位（is_/has_/can_开头）
        if (lowerName.matches("^(is_|has_|can_|should_|must_).*")) {
            return true;
        }
        
        // 5. 版本号、排序号等技术字段
        if (lowerName.matches("^(version|sort_order|display_order|row_num)$")) {
            return true;
        }
        
        return false;
    }
    
    /**
     * ✅ 新增：基于规则推断字段注释（零成本）
     */
    private String inferColumnComment(String columnName, String dataType) {
        if (columnName == null || columnName.isEmpty()) {
            return null;
        }
        
        String lowerName = columnName.toLowerCase();
        
        // 常见字段名映射（可扩展）
        Map<String, String> patterns = Map.ofEntries(
            // === 通用字段 ===
            Map.entry("id", "主键ID"),
            Map.entry("uuid", "唯一标识"),
            Map.entry("code", "编码"),
            Map.entry("no", "编号"),
            Map.entry("number", "号码"),
            Map.entry("name", "名称"),
            Map.entry("title", "标题"),
            Map.entry("type", "类型"),
            Map.entry("status", "状态"),
            Map.entry("state", "状态"),
            Map.entry("remark", "备注"),
            Map.entry("description", "描述"),
            Map.entry("desc", "描述"),
            Map.entry("note", "说明"),
            Map.entry("content", "内容"),
            Map.entry("sort", "排序"),
            Map.entry("order", "顺序"),
            Map.entry("level", "级别"),
            Map.entry("priority", "优先级"),
            Map.entry("category", "分类"),
            Map.entry("group", "分组"),
            Map.entry("tag", "标签"),
            
            // === 时间字段 ===
            Map.entry("created_at", "创建时间"),
            Map.entry("create_time", "创建时间"),
            Map.entry("created_on", "创建日期"),
            Map.entry("updated_at", "更新时间"),
            Map.entry("update_time", "更新时间"),
            Map.entry("modified_at", "修改时间"),
            Map.entry("deleted_at", "删除时间"),
            Map.entry("expired_at", "过期时间"),
            Map.entry("start_time", "开始时间"),
            Map.entry("end_time", "结束时间"),
            Map.entry("begin_date", "开始日期"),
            Map.entry("end_date", "结束日期"),
            
            // === 人员字段 ===
            Map.entry("created_by", "创建人"),
            Map.entry("creator", "创建人"),
            Map.entry("updated_by", "更新人"),
            Map.entry("modifier", "修改人"),
            Map.entry("deleted_by", "删除人"),
            Map.entry("owner", "所有者"),
            Map.entry("assignee", "负责人"),
            Map.entry("operator", "操作人"),
            
            // === 电商领域 ===
            Map.entry("user_id", "用户ID"),
            Map.entry("customer_id", "客户ID"),
            Map.entry("member_id", "会员ID"),
            Map.entry("order_id", "订单ID"),
            Map.entry("order_no", "订单号"),
            Map.entry("order_sn", "订单流水号"),
            Map.entry("product_id", "商品ID"),
            Map.entry("sku_id", "SKU ID"),
            Map.entry("spu_id", "SPU ID"),
            Map.entry("category_id", "分类ID"),
            Map.entry("brand_id", "品牌ID"),
            Map.entry("shop_id", "店铺ID"),
            Map.entry("cart_id", "购物车ID"),
            Map.entry("coupon_id", "优惠券ID"),
            Map.entry("amount", "金额"),
            Map.entry("price", "价格"),
            Map.entry("original_price", "原价"),
            Map.entry("sale_price", "售价"),
            Map.entry("discount", "折扣"),
            Map.entry("quantity", "数量"),
            Map.entry("stock", "库存"),
            Map.entry("sales", "销量"),
            Map.entry("weight", "重量"),
            Map.entry("volume", "体积"),
            
            // === 金融领域 ===
            Map.entry("account_id", "账户ID"),
            Map.entry("card_no", "卡号"),
            Map.entry("balance", "余额"),
            Map.entry("principal", "本金"),
            Map.entry("interest", "利息"),
            Map.entry("fee", "手续费"),
            Map.entry("tax", "税费"),
            Map.entry("income", "收入"),
            Map.entry("expense", "支出"),
            Map.entry("profit", "利润"),
            Map.entry("loss", "亏损"),
            Map.entry("asset", "资产"),
            Map.entry("liability", "负债"),
            Map.entry("equity", "权益"),
            Map.entry("transaction_id", "交易ID"),
            Map.entry("payment_id", "支付ID"),
            Map.entry("refund_id", "退款ID"),
            
            // === 联系信息 ===
            Map.entry("phone", "手机号"),
            Map.entry("mobile", "手机号"),
            Map.entry("telephone", "电话"),
            Map.entry("email", "邮箱"),
            Map.entry("mail", "邮箱"),
            Map.entry("address", "地址"),
            Map.entry("location", "位置"),
            Map.entry("city", "城市"),
            Map.entry("province", "省份"),
            Map.entry("country", "国家"),
            Map.entry("zipcode", "邮编"),
            Map.entry("postal_code", "邮政编码"),
            
            // === 网络相关 ===
            Map.entry("ip", "IP地址"),
            Map.entry("url", "链接地址"),
            Map.entry("link", "链接"),
            Map.entry("domain", "域名"),
            Map.entry("host", "主机"),
            Map.entry("port", "端口"),
            Map.entry("protocol", "协议"),
            Map.entry("path", "路径"),
            
            // === 其他 ===
            Map.entry("version", "版本号"),
            Map.entry("config", "配置"),
            Map.entry("setting", "设置"),
            Map.entry("option", "选项"),
            Map.entry("value", "值"),
            Map.entry("key", "键"),
            Map.entry("flag", "标志"),
            Map.entry("is_deleted", "是否删除"),
            Map.entry("is_active", "是否激活"),
            Map.entry("is_enabled", "是否启用"),
            Map.entry("is_valid", "是否有效")
        );
        
        // 精确匹配
        if (patterns.containsKey(lowerName)) {
            return patterns.get(lowerName);
        }
        
        // 模糊匹配（包含关系）
        for (Map.Entry<String, String> entry : patterns.entrySet()) {
            if (lowerName.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        
        return null; // 无法推断
    }
    
    /**
     * ✅ 新增：按需增强指定表的字段注释（反馈驱动）
     */
    public void enhanceColumnDescriptionsForTable(Long datasourceId, String tableName) {
        new Thread(() -> {
            try {
                log.info("[按需增强] 开始增强表 {}.{} 的字段注释", datasourceId, tableName);
                
                // 1. 检查是否需要增强（覆盖率 < 50%）
                if (!shouldEnhanceTable(datasourceId, tableName)) {
                    log.info("[按需增强] 表 {}.{} 注释覆盖率已达标，跳过", datasourceId, tableName);
                    return;
                }
                
                // 2. ✅ 使用Mapper查询需要增强的字段列表
                List<Map<String, Object>> columns = metadataCollectorMapper.getColumnsWithoutComment(datasourceId, tableName);
                
                if (columns.isEmpty()) {
                    log.info("[按需增强] 表 {}.{} 所有字段已有注释", datasourceId, tableName);
                    return;
                }
                
                log.info("[按需增强] 表 {}.{} 共 {} 个字段需要增强", datasourceId, tableName, columns.size());
                
                // 3. 批量构建 Prompt（一次调用处理所有字段）
                StringBuilder prompt = new StringBuilder();
                prompt.append("你是数据库专家。为以下字段生成简洁的中文业务注释（每字段20字以内）。\n\n");
                prompt.append("表名: ").append(tableName).append("\n\n");
                prompt.append("字段列表:\n");
                
                for (Map<String, Object> col : columns) {
                    prompt.append("- ").append(col.get("column_name"))
                          .append(" (").append(col.get("data_type"));
                    
                    if ("1".equals(String.valueOf(col.get("is_primary_key")))) {
                        prompt.append(", 主键");
                    }
                    if ("0".equals(String.valueOf(col.get("is_nullable")))) {
                        prompt.append(", 非空");
                    }
                    prompt.append(")\n");
                }
                
                prompt.append("\n要求:\n");
                prompt.append("1. 根据字段名推测业务含义\n");
                prompt.append("2. 简洁准确，20字以内\n");
                prompt.append("3. 返回 JSON 格式：{\"columnName\": \"comment\"}\n");
                prompt.append("4. 只返回 JSON，不要其他内容\n\n");
                prompt.append("生成的 JSON:");
                
                // 4. 调用 LLM
                String response = callLLM(prompt.toString());
                if (response == null || response.isEmpty()) {
                    log.warn("[按需增强] LLM 返回为空");
                    return;
                }
                
                // 5. 解析并更新
                Map<String, String> comments = parseJsonResponse(response);
                int updatedCount = 0;
                for (Map.Entry<String, String> entry : comments.entrySet()) {
                    String columnName = entry.getKey();
                    String comment = entry.getValue();
                    
                    if (comment != null && comment.length() > 2 && comment.length() <= 20) {
                        updateColumnCommentWithSource(datasourceId, tableName, columnName, comment, "LLM");
                        updatedCount++;
                    }
                }
                
                log.info("[按需增强] 表 {}.{} 完成，更新 {} 个字段", datasourceId, tableName, updatedCount);
                
            } catch (Exception e) {
                log.error("[按需增强] 异常", e);
            }
        }, "on-demand-enhance-thread").start();
    }
    
    /**
     * 检查表是否需要增强
     */
    private boolean shouldEnhanceTable(Long datasourceId, String tableName) {
        // ✅ 使用Mapper统计注释覆盖率
        Map<String, Object> result = metadataCollectorMapper.getColumnCommentStats(datasourceId, tableName);
        int total = ((Number) result.get("total")).intValue();
        int commented = ((Number) result.get("commented")).intValue();
        
        if (total == 0) {
            return false;
        }
        
        double coverageRate = (double) commented / total;
        log.debug("[按需增强] 表 {}.{} 注释覆盖率: {:.2f}%", datasourceId, tableName, coverageRate * 100);
        
        return coverageRate < 0.5; // 覆盖率 < 50% 才增强
    }
    
    /**
     * 解析 LLM 返回的 JSON
     */
    private Map<String, String> parseJsonResponse(String json) {
        try {
            // 清理 Markdown 格式
            json = json.replaceAll("```json\\s*", "").replaceAll("```\\s*", "").trim();
            
            // 使用 Jackson 解析
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            return mapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<Map<String, String>>() {});
        } catch (Exception e) {
            log.error("[按需增强] JSON 解析失败: {}", json, e);
            return Map.of();
        }
    }
    
    /**
     * 更新字段注释并记录来源
     */
    private void updateColumnCommentWithSource(Long datasourceId, String tableName, String columnName, String comment, String source) {
        metadataCollectorMapper.updateColumnCommentWithSource(datasourceId, tableName, columnName, comment, source);
    }
    
    /**
     * ✅ 新增：离线导入表结构（解析SQL建表语句）
     * 
     * @param datasourceId 数据源ID
     * @param sqlContent SQL建表语句（支持多个，分号分隔）
     * @return 导入结果统计
     */
    @Transactional
    public Map<String, Object> importOfflineMetadata(Long datasourceId, String sqlContent) {
        Map<String, Object> result = new HashMap<>();
        int tableCount = 0;
        int columnCount = 0;
        List<String> errors = new ArrayList<>();
        
        try {
            log.info("[离线导入] 开始解析 SQL 内容，长度: {}", sqlContent.length());
            
            // 1. 按分号分割SQL语句
            String[] statements = sqlContent.split(";");
            log.info("[离线导入] 检测到 {} 条 SQL 语句", statements.length);
            
            // 2. 逐条解析
            for (int i = 0; i < statements.length; i++) {
                String statement = statements[i].trim();
                if (statement.isEmpty()) {
                    continue;
                }
                
                try {
                    // 只处理 CREATE TABLE 语句
                    if (!statement.toUpperCase().startsWith("CREATE TABLE")) {
                        log.debug("[离线导入] 跳过非建表语句: {}", statement.substring(0, Math.min(50, statement.length())));
                        continue;
                    }
                    
                    // 解析建表语句
                    ParsedTable parsedTable = parseCreateTable(statement);
                    if (parsedTable == null) {
                        errors.add("第" + (i + 1) + "条语句解析失败");
                        continue;
                    }
                    
                    // 3. 保存表元数据
                    TableMetadata tableMeta = new TableMetadata();
                    tableMeta.setDatasourceId(datasourceId);
                    tableMeta.setTableName(parsedTable.tableName);
                    tableMeta.setTableComment(parsedTable.tableComment != null ? parsedTable.tableComment : "");
                    tableMeta.setTableType("TABLE");
                    saveTableMetadata(List.of(tableMeta));
                    tableCount++;
                    
                    // 4. 保存字段元数据
                    List<ColumnMetadata> columns = new ArrayList<>();
                    for (ParsedColumn parsedCol : parsedTable.columns) {
                        ColumnMetadata colMeta = new ColumnMetadata();
                        colMeta.setDatasourceId(datasourceId);
                        colMeta.setTableName(parsedTable.tableName);
                        colMeta.setColumnName(parsedCol.columnName);
                        colMeta.setDataType(parsedCol.dataType);
                        colMeta.setColumnSize(parsedCol.columnSize);
                        colMeta.setIsNullable(parsedCol.isNullable ? 1 : 0);
                        colMeta.setColumnComment(parsedCol.comment != null ? parsedCol.comment : "");
                        colMeta.setIsPrimaryKey(parsedCol.isPrimaryKey ? 1 : 0);
                        colMeta.setOrdinalPosition(parsedCol.ordinalPosition);
                        columns.add(colMeta);
                    }
                    saveColumnMetadata(columns);
                    columnCount += columns.size();
                    
                    log.info("[离线导入] 成功导入表: {}, 字段数: {}", parsedTable.tableName, columns.size());
                    
                } catch (Exception e) {
                    log.error("[离线导入] 解析第{}条语句失败", i + 1, e);
                    errors.add("第" + (i + 1) + "条语句: " + e.getMessage());
                }
            }
            
            // 5. 构建返回结果
            result.put("status", "SUCCESS");
            result.put("tableCount", tableCount);
            result.put("columnCount", columnCount);
            result.put("errorCount", errors.size());
            if (!errors.isEmpty()) {
                result.put("errors", errors);
            }
            
            log.info("[离线导入] 完成: 表={}, 字段={}, 错误={}", tableCount, columnCount, errors.size());
            
        } catch (Exception e) {
            log.error("[离线导入] 整体失败", e);
            result.put("status", "FAILED");
            result.put("errorMessage", e.getMessage());
            throw new RuntimeException("离线导入失败: " + e.getMessage(), e);
        }
        
        return result;
    }
    
    /**
     * 解析 CREATE TABLE 语句
     */
    private ParsedTable parseCreateTable(String sql) {
        try {
            ParsedTable parsed = new ParsedTable();
            
            // 1. 提取表名
            // 匹配: CREATE TABLE `table_name` 或 CREATE TABLE table_name
            java.util.regex.Pattern tablePattern = java.util.regex.Pattern.compile(
                "CREATE\\s+TABLE\\s+[`\"]?(\\w+)[`\"]?", 
                java.util.regex.Pattern.CASE_INSENSITIVE
            );
            java.util.regex.Matcher tableMatcher = tablePattern.matcher(sql);
            if (!tableMatcher.find()) {
                throw new IllegalArgumentException("无法提取表名");
            }
            parsed.tableName = tableMatcher.group(1);
            
            // 2. 提取表注释（MySQL: COMMENT='xxx'）
            java.util.regex.Pattern commentPattern = java.util.regex.Pattern.compile(
                "COMMENT\\s*=\\s*['\"]([^'\"]+)['\"]", 
                java.util.regex.Pattern.CASE_INSENSITIVE
            );
            java.util.regex.Matcher commentMatcher = commentPattern.matcher(sql);
            if (commentMatcher.find()) {
                parsed.tableComment = commentMatcher.group(1);
            }
            
            // 3. 提取字段定义
            // 找到第一个 ( 和最后一个 ) 之间的内容
            int startIdx = sql.indexOf('(');
            int endIdx = sql.lastIndexOf(')');
            if (startIdx == -1 || endIdx == -1 || startIdx >= endIdx) {
                throw new IllegalArgumentException("无法解析字段定义");
            }
            
            String fieldsSection = sql.substring(startIdx + 1, endIdx).trim();
            
            // 按逗号分割字段（注意：要跳过括号内的逗号）
            List<String> fieldDefinitions = splitFieldDefinitions(fieldsSection);
            
            int ordinalPosition = 1;
            for (String fieldDef : fieldDefinitions) {
                fieldDef = fieldDef.trim();
                if (fieldDef.isEmpty()) continue;
                
                // 跳过 PRIMARY KEY、INDEX、KEY 等非字段定义
                if (fieldDef.toUpperCase().matches("^(PRIMARY\\s+KEY|KEY|INDEX|UNIQUE|CONSTRAINT|FOREIGN\\s+KEY).*")) {
                    continue;
                }
                
                try {
                    ParsedColumn col = parseColumnDefinition(fieldDef, ordinalPosition);
                    if (col != null) {
                        parsed.columns.add(col);
                        ordinalPosition++;
                    }
                } catch (Exception e) {
                    log.warn("[离线导入] 解析字段失败: {}", fieldDef, e);
                }
            }
            
            if (parsed.columns.isEmpty()) {
                throw new IllegalArgumentException("未解析到任何字段");
            }
            
            return parsed;
            
        } catch (Exception e) {
            log.error("[离线导入] 解析 CREATE TABLE 失败", e);
            throw new IllegalArgumentException("SQL解析失败: " + e.getMessage());
        }
    }
    
    /**
     * 智能分割字段定义（跳过括号内的逗号）
     */
    private List<String> splitFieldDefinitions(String fieldsSection) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int parenDepth = 0;
        
        for (char c : fieldsSection.toCharArray()) {
            if (c == '(') {
                parenDepth++;
                current.append(c);
            } else if (c == ')') {
                parenDepth--;
                current.append(c);
            } else if (c == ',' && parenDepth == 0) {
                result.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        
        if (current.length() > 0) {
            result.add(current.toString().trim());
        }
        
        return result;
    }
    
    /**
     * 解析单个字段定义
     */
    private ParsedColumn parseColumnDefinition(String fieldDef, int ordinalPosition) {
        ParsedColumn col = new ParsedColumn();
        col.ordinalPosition = ordinalPosition;
        
        // 提取字段名（第一个单词，去除引号）
        java.util.regex.Pattern namePattern = java.util.regex.Pattern.compile("^[`\"]?(\\w+)[`\"]?\\s+(.*)", java.util.regex.Pattern.DOTALL);
        java.util.regex.Matcher nameMatcher = namePattern.matcher(fieldDef);
        if (!nameMatcher.find()) {
            return null;
        }
        
        col.columnName = nameMatcher.group(1);
        String rest = nameMatcher.group(2).trim();
        
        // 提取数据类型（包括长度）
        java.util.regex.Pattern typePattern = java.util.regex.Pattern.compile("^(\\w+(?:\\([^)]+\\))?)(.*)", java.util.regex.Pattern.DOTALL | java.util.regex.Pattern.CASE_INSENSITIVE);
        java.util.regex.Matcher typeMatcher = typePattern.matcher(rest);
        if (!typeMatcher.find()) {
            return null;
        }
        
        col.dataType = typeMatcher.group(1).toUpperCase();
        String remaining = typeMatcher.group(2).trim();
        
        // 提取字段大小（如 VARCHAR(100) 中的 100）
        java.util.regex.Pattern sizePattern = java.util.regex.Pattern.compile("\\((\\d+)") ;
        java.util.regex.Matcher sizeMatcher = sizePattern.matcher(col.dataType);
        if (sizeMatcher.find()) {
            col.columnSize = Integer.parseInt(sizeMatcher.group(1));
        }
        
        // 检查是否为主键
        if (remaining.toUpperCase().contains("PRIMARY KEY") || remaining.toUpperCase().contains("NOT NULL AUTO_INCREMENT")) {
            col.isPrimaryKey = true;
        }
        
        // 检查是否为NULL
        col.isNullable = !remaining.toUpperCase().contains("NOT NULL");
        
        // 提取注释
        java.util.regex.Pattern colCommentPattern = java.util.regex.Pattern.compile(
            "COMMENT\\s+['\"]([^'\"]+)['\"]", 
            java.util.regex.Pattern.CASE_INSENSITIVE
        );
        java.util.regex.Matcher colCommentMatcher = colCommentPattern.matcher(remaining);
        if (colCommentMatcher.find()) {
            col.comment = colCommentMatcher.group(1);
        }
        
        return col;
    }
    
    /**
     * 内部类：解析后的表结构
     */
    private static class ParsedTable {
        String tableName;
        String tableComment;
        List<ParsedColumn> columns = new ArrayList<>();
    }
    
    /**
     * 内部类：解析后的字段
     */
    private static class ParsedColumn {
        String columnName;
        String dataType;
        Integer columnSize;
        boolean isNullable = true;
        String comment;
        boolean isPrimaryKey = false;
        int ordinalPosition;
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
