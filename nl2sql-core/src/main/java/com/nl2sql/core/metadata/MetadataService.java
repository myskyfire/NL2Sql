package com.nl2sql.core.metadata;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class MetadataService {
    
    private final JdbcTemplate jdbcTemplate;
    private final Map<String, TableMetadata> metadataCache = new ConcurrentHashMap<>();
    
    public MetadataService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }
    
    @PostConstruct
    public void init() {
        refreshMetadata();
    }
    
    public void refreshMetadata() {
        log.info("开始刷新数据库元数据...");
        metadataCache.clear();
        
        try {
            // 尝试从本地已同步的元数据表读取
            List<Map<String, Object>> tables;
            try {
                // 检查是否有column_metadata表（已同步的元数据）
                tables = jdbcTemplate.queryForList(
                    "SELECT DISTINCT datasource_id, table_name " +
                    "FROM column_metadata " +
                    "WHERE datasource_id IS NOT NULL"
                );
                log.info("从本地元数据表读取到 {} 条记录", tables.size());
            } catch (Exception e) {
                // 如果没有column_metadata表，直接从information_schema读取
                log.warn("本地元数据表不存在，从information_schema读取: {}", e.getMessage());
                String dbSchema = jdbcTemplate.getDataSource().getConnection().getCatalog();
                tables = jdbcTemplate.queryForList(
                    "SELECT NULL as datasource_id, table_name FROM information_schema.tables WHERE table_schema = ?",
                    dbSchema
                );
            }
            
            for (Map<String, Object> table : tables) {
                String tableName = (String) table.get("table_name");
                Long datasourceId = table.get("datasource_id") != null ? 
                    ((Number) table.get("datasource_id")).longValue() : null;
                
                // 优先从 column_metadata 表读取表注释，如果没有则从目标数据库获取
                String tableComment = tableName; // 默认使用表名
                try {
                    if (datasourceId != null) {
                        // 尝试从 column_metadata 表读取 table_comment
                        List<Map<String, Object>> localTableInfo = jdbcTemplate.queryForList(
                            "SELECT DISTINCT table_comment FROM column_metadata " +
                            "WHERE datasource_id = ? AND table_name = ? LIMIT 1",
                            datasourceId, tableName
                        );
                        
                        if (!localTableInfo.isEmpty() && localTableInfo.get(0).get("table_comment") != null) {
                            // 本地已有表注释，直接使用
                            tableComment = (String) localTableInfo.get(0).get("table_comment");
                        } else {
                            // 本地没有，从目标数据库获取
                            Map<String, Object> dsConfig = jdbcTemplate.queryForMap(
                                "SELECT host, port, database_name FROM datasource_config WHERE id = ?",
                                datasourceId
                            );
                            String dbName = (String) dsConfig.get("database_name");
                            
                            List<Map<String, Object>> remoteTableInfo = jdbcTemplate.queryForList(
                                "SELECT TABLE_COMMENT FROM information_schema.TABLES " +
                                "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?",
                                dbName, tableName
                            );
                            if (!remoteTableInfo.isEmpty() && remoteTableInfo.get(0).get("TABLE_COMMENT") != null) {
                                tableComment = (String) remoteTableInfo.get(0).get("TABLE_COMMENT");
                            }
                        }
                    }
                } catch (Exception e) {
                    log.warn("获取表 {} 的注释失败: {}", tableName, e.getMessage());
                }
                
                TableMetadata tableMeta = new TableMetadata();
                tableMeta.setTableName(tableName);
                tableMeta.setTableComment(tableComment);
                
                List<Map<String, Object>> columns;
                if (datasourceId != null) {
                    // 从column_metadata表读取
                    columns = jdbcTemplate.queryForList(
                        "SELECT column_name, data_type, column_comment, is_primary_key " +
                        "FROM column_metadata " +
                        "WHERE datasource_id = ? AND table_name = ? " +
                        "ORDER BY ordinal_position",
                        datasourceId, tableName
                    );
                } else {
                    // 从information_schema读取
                    String dbSchema = jdbcTemplate.getDataSource().getConnection().getCatalog();
                    columns = jdbcTemplate.queryForList(
                        "SELECT column_name, data_type, column_comment, column_key " +
                        "FROM information_schema.columns " +
                        "WHERE table_schema = ? AND table_name = ? " +
                        "ORDER BY ordinal_position",
                        dbSchema, tableName
                    );
                }
                
                List<ColumnMetadata> columnMetas = new ArrayList<>();
                for (Map<String, Object> col : columns) {
                    ColumnMetadata colMeta = new ColumnMetadata();
                    colMeta.setColumnName((String) col.get("column_name"));
                    colMeta.setDataType((String) col.get("data_type"));
                    colMeta.setColumnComment((String) col.get("column_comment"));
                    
                    // 兼容两种数据源
                    Object pkObj = col.get("is_primary_key");
                    if (pkObj == null) {
                        pkObj = col.get("column_key");
                    }
                    colMeta.setPrimary(pkObj != null && ("1".equals(pkObj.toString()) || "PRI".equals(pkObj.toString())));
                    
                    columnMetas.add(colMeta);
                }
                
                tableMeta.setColumns(columnMetas);
                metadataCache.put(tableName, tableMeta);
            }
            
            log.info("元数据刷新完成，共 {} 张表", metadataCache.size());
        } catch (Exception e) {
            log.error("刷新元数据失败", e);
            throw new RuntimeException("刷新元数据失败", e);
        }
    }
    
    public Map<String, TableMetadata> getAllMetadata() {
        return new HashMap<>(metadataCache);
    }
    
    /**
     * ✅ 获取指定数据源的元数据
     */
    public Map<String, TableMetadata> getAllMetadataByDatasource(Long datasourceId) {
        if (datasourceId == null) {
            log.warn("datasourceId 为 null，返回空元数据");
            return Collections.emptyMap();
        }
        
        Map<String, TableMetadata> result = new HashMap<>();
        
        try {
            // 从 column_metadata 表查询该数据源的所有表
            List<Map<String, Object>> tables = jdbcTemplate.queryForList(
                "SELECT DISTINCT table_name FROM column_metadata WHERE datasource_id = ?",
                datasourceId
            );
            
            for (Map<String, Object> table : tables) {
                String tableName = (String) table.get("table_name");
                
                // 获取表注释
                String tableComment = jdbcTemplate.queryForObject(
                    "SELECT DISTINCT table_comment FROM column_metadata WHERE datasource_id = ? AND table_name = ? LIMIT 1",
                    String.class, datasourceId, tableName
                );
                
                TableMetadata tableMeta = new TableMetadata();
                tableMeta.setTableName(tableName);
                tableMeta.setTableComment(tableComment != null ? tableComment : tableName);
                
                // 获取字段信息
                List<Map<String, Object>> columns = jdbcTemplate.queryForList(
                    "SELECT column_name, data_type, column_comment, is_primary_key " +
                    "FROM column_metadata " +
                    "WHERE datasource_id = ? AND table_name = ? " +
                    "ORDER BY ordinal_position",
                    datasourceId, tableName
                );
                
                List<ColumnMetadata> columnMetas = new ArrayList<>();
                for (Map<String, Object> col : columns) {
                    ColumnMetadata colMeta = new ColumnMetadata();
                    colMeta.setColumnName((String) col.get("column_name"));
                    colMeta.setDataType((String) col.get("data_type"));
                    colMeta.setColumnComment((String) col.get("column_comment"));
                    
                    Object pkObj = col.get("is_primary_key");
                    colMeta.setPrimary(pkObj != null && ("1".equals(pkObj.toString()) || "PRI".equals(pkObj.toString())));
                    
                    columnMetas.add(colMeta);
                }
                
                tableMeta.setColumns(columnMetas);
                result.put(tableName, tableMeta);
            }
            
            log.debug("数据源 {} 的元数据加载完成，共 {} 张表", datasourceId, result.size());
        } catch (Exception e) {
            log.error("加载数据源 {} 的元数据失败", datasourceId, e);
        }
        
        return result;
    }
    
    public TableMetadata getTableMetadata(String tableName) {
        return metadataCache.get(tableName);
    }
    
    public List<String> getAllTableNames() {
        return new ArrayList<>(metadataCache.keySet());
    }
}
