package com.nl2sql.metadata.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.nl2sql.metadata.entity.ColumnMetadata;
import com.nl2sql.metadata.entity.TableMetadata;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class MetadataQueryService {
    
    private final JdbcTemplate jdbcTemplate;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    
    private static final long CACHE_TTL_HOURS = 24;
    
    public MetadataQueryService(JdbcTemplate jdbcTemplate, StringRedisTemplate redisTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.redisTemplate = redisTemplate;
        this.objectMapper = new ObjectMapper();
        // 注册JavaTimeModule以支持LocalDateTime序列化
        this.objectMapper.registerModule(new JavaTimeModule());
        this.objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }
    
    /**
     * 获取所有表（带缓存）
     */
    public List<TableMetadata> getAllTables(Long datasourceId) {
        String cacheKey = "metadata:tables:" + datasourceId;
        
        try {
            // 尝试从缓存获取
            String cached = redisTemplate.opsForValue().get(cacheKey);
            if (cached != null) {
                log.debug("缓存命中: {}", cacheKey);
                return objectMapper.readValue(cached, new TypeReference<List<TableMetadata>>() {});
            }
        } catch (Exception e) {
            log.warn("缓存读取失败，从数据库加载", e);
        }
        
        // 从数据库加载
        String sql = "SELECT * FROM table_metadata WHERE datasource_id = ? ORDER BY table_name";
        List<TableMetadata> tables = jdbcTemplate.query(sql, new TableRowMapper(), datasourceId);
        
        // 写入缓存
        try {
            String json = objectMapper.writeValueAsString(tables);
            redisTemplate.opsForValue().set(cacheKey, json, CACHE_TTL_HOURS, TimeUnit.HOURS);
        } catch (Exception e) {
            log.warn("缓存写入失败", e);
        }
        
        return tables;
    }
    
    /**
     * 获取表详情（含字段）
     */
    public TableDetail getTableDetail(Long datasourceId, String tableName) {
        // 获取表信息
        String tableSql = "SELECT * FROM table_metadata WHERE datasource_id = ? AND table_name = ?";
        List<TableMetadata> tables = jdbcTemplate.query(tableSql, new TableRowMapper(), datasourceId, tableName);
        
        if (tables.isEmpty()) {
            return null;
        }
        
        TableDetail detail = new TableDetail();
        detail.setTable(tables.get(0));
        
        // 获取字段信息
        String columnSql = "SELECT * FROM column_metadata WHERE datasource_id = ? AND table_name = ? ORDER BY ordinal_position";
        List<ColumnMetadata> columns = jdbcTemplate.query(columnSql, new ColumnRowMapper(), datasourceId, tableName);
        detail.setColumns(columns);
        
        return detail;
    }
    
    /**
     * 搜索表名
     */
    public List<TableMetadata> searchTables(Long datasourceId, String keyword) {
        String sql = "SELECT * FROM table_metadata WHERE datasource_id = ? AND table_name LIKE ? ORDER BY table_name LIMIT 50";
        return jdbcTemplate.query(sql, new TableRowMapper(), datasourceId, "%" + keyword + "%");
    }
    
    /**
     * 清除缓存
     */
    public void clearCache(Long datasourceId) {
        String pattern = "metadata:*:" + datasourceId + "*";
        redisTemplate.keys(pattern).forEach(redisTemplate::delete);
        log.info("已清除数据源 {} 的元数据缓存", datasourceId);
    }
    
    /**
     * 获取本地修改的元数据统计
     */
    public Map<String, Object> getLocalModifiedCount(Long datasourceId) {
        Map<String, Object> result = new java.util.HashMap<>();
        
        try {
            // 统计本地修改的表数量
            String tableSql = "SELECT COUNT(*) FROM table_metadata WHERE datasource_id = ? AND is_local_modified = 1";
            Integer tableCount = jdbcTemplate.queryForObject(tableSql, Integer.class, datasourceId);
            result.put("tableCount", tableCount != null ? tableCount : 0);
            
            // 统计本地修改的字段数量
            String columnSql = "SELECT COUNT(*) FROM column_metadata WHERE datasource_id = ? AND is_local_modified = 1";
            Integer columnCount = jdbcTemplate.queryForObject(columnSql, Integer.class, datasourceId);
            result.put("columnCount", columnCount != null ? columnCount : 0);
            
            log.debug("数据源 {} 本地修改统计: 表={}, 字段={}", datasourceId, result.get("tableCount"), result.get("columnCount"));
            
        } catch (Exception e) {
            log.warn("获取本地修改统计失败（可能字段不存在，需先执行DDL）: {}", e.getMessage());
            result.put("tableCount", 0);
            result.put("columnCount", 0);
        }
        
        return result;
    }
    
    /**
     * 表详情
     */
    @lombok.Data
    public static class TableDetail {
        private TableMetadata table;
        private List<ColumnMetadata> columns;
    }
    
    /**
     * TableRowMapper
     */
    private static class TableRowMapper implements RowMapper<TableMetadata> {
        @Override
        public TableMetadata mapRow(ResultSet rs, int rowNum) throws SQLException {
            TableMetadata table = new TableMetadata();
            table.setId(rs.getLong("id"));
            table.setDatasourceId(rs.getLong("datasource_id"));
            table.setTableName(rs.getString("table_name"));
            table.setTableComment(rs.getString("table_comment"));
            table.setTableType(rs.getString("table_type"));
            table.setSchemaName(rs.getString("schema_name"));
            table.setRowCountEstimate(rs.getLong("row_count_estimate"));
            table.setDataSizeKb(rs.getLong("data_size_kb"));
            table.setIndexSizeKb(rs.getLong("index_size_kb"));
            table.setCreatedAt(rs.getTimestamp("created_at").toLocalDateTime());
            table.setUpdatedAt(rs.getTimestamp("updated_at").toLocalDateTime());
            return table;
        }
    }
    
    /**
     * ColumnRowMapper
     */
    private static class ColumnRowMapper implements RowMapper<ColumnMetadata> {
        @Override
        public ColumnMetadata mapRow(ResultSet rs, int rowNum) throws SQLException {
            ColumnMetadata column = new ColumnMetadata();
            column.setId(rs.getLong("id"));
            column.setDatasourceId(rs.getLong("datasource_id"));
            column.setTableName(rs.getString("table_name"));
            column.setColumnName(rs.getString("column_name"));
            column.setDataType(rs.getString("data_type"));
            column.setColumnSize(rs.getInt("column_size"));
            column.setDecimalDigits(rs.getInt("decimal_digits"));
            column.setIsNullable(rs.getInt("is_nullable"));
            column.setColumnDefault(rs.getString("column_default"));
            column.setColumnComment(rs.getString("column_comment"));
            column.setIsPrimaryKey(rs.getInt("is_primary_key"));
            column.setIsUnique(rs.getInt("is_unique"));
            column.setOrdinalPosition(rs.getInt("ordinal_position"));
            
            // 兼容可能为null的字段
            try {
                column.setCharacterSetName(rs.getString("character_set_name"));
            } catch (SQLException e) {
                column.setCharacterSetName(null);
            }
            
            try {
                column.setCollationName(rs.getString("collation_name"));
            } catch (SQLException e) {
                column.setCollationName(null);
            }
            
            column.setExtraInfo(rs.getString("extra_info"));
            column.setCreatedAt(rs.getTimestamp("created_at").toLocalDateTime());
            column.setUpdatedAt(rs.getTimestamp("updated_at").toLocalDateTime());
            return column;
        }
    }
}
