package com.nl2sql.core.service;

import com.nl2sql.core.cache.MetadataCacheService;
import com.nl2sql.core.mapper.MetadataMapper;
import com.nl2sql.core.retriever.VectorRetriever;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Schema检索服务
 * 
 * 职责：
 * 1. 向量检索相关表
 * 2. 构建表Schema信息（带缓存）
 * 3. 查询归一化处理
 */
@Slf4j
@Service
public class SchemaRetrievalService {
    
    @Autowired
    private JdbcTemplate jdbcTemplate;
    
    @Autowired
    private VectorRetriever vectorRetriever;
    
    @Autowired
    private MetadataMapper metadataMapper;
    
    @Autowired
    private MetadataCacheService metadataCacheService;
    
    /**
     * 检索表结构信息（供Skill使用）
     */
    public String retrieveSchema(String query, Long datasourceId) {
        try {
            // ✅ 关键优化：优先从L1/L2缓存获取表（高分反馈注入的"黄金表关系"）
            List<String> cachedTables = null;
            
            if (metadataCacheService != null) {
                // 1. 尝试L2模糊向量缓存（归一化匹配）
                String normalizedQuery = normalizeQueryForCache(query);
                cachedTables = metadataCacheService.getFuzzyVectorRetrieval(normalizedQuery);
                
                if (cachedTables != null && !cachedTables.isEmpty()) {
                    log.info("[SchemaRetrieval] ⚡ L2缓存命中(高分反馈): query='{}', tables={}", query, cachedTables);
                    return buildTableSchemaInfo(cachedTables, datasourceId);
                }
                
                // 2. 尝试L3语义索引（Jaccard相似度）
                cachedTables = metadataCacheService.findSimilarQueryBySemantic(query, datasourceId, 0.85);
                
                if (cachedTables != null && !cachedTables.isEmpty()) {
                    log.info("[SchemaRetrieval] ⚡ L3语义索引命中: query='{}', tables={}", query, cachedTables);
                    return buildTableSchemaInfo(cachedTables, datasourceId);
                }
            }
            
            // 3. 缓存未命中，走正常流程：L3向量检索
            log.debug("[SchemaRetrieval] 缓存未命中，执行向量检索: query={}", query);
            List<String> tables = vectorRetriever.retrieveTopTables(query, datasourceId, 10);
            if (tables.isEmpty()) {
                return "ERROR: 未找到任何相关表";
            }
            
            // 构建schema信息
            return buildTableSchemaInfo(tables, datasourceId);
        } catch (Exception e) {
            log.error("[SchemaRetrieval] 检索schema失败", e);
            return "ERROR: " + e.getMessage();
        }
    }
    
    /**
     * 获取表的中文注释
     */
    public String getTableComment(String tableName, Long datasourceId) {
        try {
            return metadataMapper.getTableComment(datasourceId, tableName);
        } catch (Exception e) {
            return "";
        }
    }
    
    /**
     * 构建表Schema信息（带缓存）
     */
    public String buildTableSchemaInfo(List<String> tables, Long datasourceId) {
        if (tables == null || tables.isEmpty()) {
            return "";
        }
        
        // ✅ 关键优化：先尝试从缓存获取所有表的schema
        Map<String, List<Map<String, Object>>> cachedSchemas = metadataCacheService.getSchemas(datasourceId, tables);
        
        // 分离已缓存和未缓存的表
        List<String> uncachedTables = new ArrayList<>();
        Map<String, List<Map<String, Object>>> allColumnsByTable = new HashMap<>();
        
        for (String tableName : tables) {
            if (cachedSchemas.containsKey(tableName)) {
                allColumnsByTable.put(tableName, cachedSchemas.get(tableName));
            } else {
                uncachedTables.add(tableName);
            }
        }
        
        // 如果有未缓存的表，批量查询
        if (!uncachedTables.isEmpty()) {
            log.debug("[SchemaRetrieval] Schema缓存部分未命中，需查询 {} 个表: {}", uncachedTables.size(), uncachedTables);
            
            String placeholders = uncachedTables.stream()
                .map(t -> "?")
                .collect(java.util.stream.Collectors.joining(", "));
            
            String batchColSql = String.format(
                "SELECT table_name, column_name, data_type, column_comment, is_primary_key " +
                "FROM column_metadata WHERE datasource_id = ? AND table_name IN (%s) " +
                "ORDER BY table_name, ordinal_position",
                placeholders
            );
            
            Object[] params = new Object[uncachedTables.size() + 1];
            params[0] = datasourceId;
            for (int i = 0; i < uncachedTables.size(); i++) {
                params[i + 1] = uncachedTables.get(i);
            }
            
            List<Map<String, Object>> allColumns = jdbcTemplate.queryForList(batchColSql, params);
            
            // 按表名分组并缓存
            Map<String, List<Map<String, Object>>> uncachedByTable = allColumns.stream()
                .collect(java.util.stream.Collectors.groupingBy(col -> (String) col.get("table_name")));
            
            for (Map.Entry<String, List<Map<String, Object>>> entry : uncachedByTable.entrySet()) {
                String tableName = entry.getKey();
                List<Map<String, Object>> columns = entry.getValue();
                
                // 存入缓存
                metadataCacheService.putSchema(datasourceId, tableName, columns);
                
                // 合并到结果集
                allColumnsByTable.put(tableName, columns);
            }
            
            log.debug("[SchemaRetrieval] 已缓存 {} 个表的Schema", uncachedByTable.size());
        } else {
            log.debug("[SchemaRetrieval] Schema缓存全部命中");
        }
        
        // 构建返回字符串
        StringBuilder sb = new StringBuilder();
        for (String tableName : tables) {
            sb.append(String.format("\n表名: %s\n", tableName));
            
            List<Map<String, Object>> columns = allColumnsByTable.getOrDefault(tableName, Collections.emptyList());
            
            if (columns.isEmpty()) {
                sb.append("  [警告] 该表没有字段元数据\n");
            } else {
                for (Map<String, Object> col : columns) {
                    sb.append(String.format("  - %s (%s)", 
                        col.get("column_name"), col.get("data_type")));
                    
                    if ("1".equals(String.valueOf(col.get("is_primary_key")))) {
                        sb.append(" [主键]");
                    }
                    
                    if (col.get("column_comment") != null && !col.get("column_comment").toString().isEmpty()) {
                        sb.append(String.format(" - %s", col.get("column_comment")));
                    }
                    sb.append("\n");
                }
            }
        }
        return sb.toString();
    }
    
    /**
     * ✅ 归一化查询文本（与SQLFeedbackService保持一致）
     */
    public String normalizeQueryForCache(String query) {
        if (query == null) return "";
        
        // 替换数字为占位符
        String normalized = query.replaceAll("\\d+", "<NUM>");
        
        // 替换具体日期为占位符
        normalized = normalized.replaceAll("\\d{4}[-/]\\d{1,2}[-/]\\d{1,2}", "<DATE>");
        
        // 替换相对时间为占位符
        normalized = normalized.replaceAll("最近\\d+天", "最近<NUM>天");
        normalized = normalized.replaceAll("过去\\d+天", "过去<NUM>天");
        
        // 去除多余空格
        normalized = normalized.trim().replaceAll("\\s+", " ");
        
        return normalized;
    }
}
