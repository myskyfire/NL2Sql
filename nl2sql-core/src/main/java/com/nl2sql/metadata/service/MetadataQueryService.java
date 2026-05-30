package com.nl2sql.metadata.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.nl2sql.metadata.entity.ColumnMetadata;
import com.nl2sql.metadata.entity.TableMetadata;
import com.nl2sql.metadata.mapper.MetadataQueryMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class MetadataQueryService {
    
    @Autowired(required = false)
    private JdbcTemplate jdbcTemplate;
    
    @Autowired
    private StringRedisTemplate redisTemplate;
    
    @Autowired
    private MetadataQueryMapper metadataMapper;
    
    private final ObjectMapper objectMapper;
    
    private static final long CACHE_TTL_HOURS = 24;
    
    public MetadataQueryService() {
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
        List<TableMetadata> tables = metadataMapper.selectTablesByDatasource(datasourceId);
        
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
        TableMetadata table = metadataMapper.selectTableByDatasourceAndName(datasourceId, tableName);
        
        if (table == null) {
            return null;
        }
        
        TableDetail detail = new TableDetail();
        detail.setTable(table);
        
        // 获取字段信息
        List<ColumnMetadata> columns = metadataMapper.selectColumnsByDatasourceAndTable(datasourceId, tableName);
        detail.setColumns(columns);
        
        return detail;
    }
    
    /**
     * 搜索表名
     */
    public List<TableMetadata> searchTables(Long datasourceId, String keyword) {
        return metadataMapper.searchTablesAsEntity(datasourceId, "%" + keyword + "%");
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
        Map<String, Object> result = new HashMap<>();
        
        try {
            // 统计本地修改的表数量
            Integer tableCount = metadataMapper.countLocalModifiedTables(datasourceId);
            result.put("tableCount", tableCount != null ? tableCount : 0);
            
            // 统计本地修改的字段数量
            Integer columnCount = metadataMapper.countLocalModifiedColumns(datasourceId);
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
}
