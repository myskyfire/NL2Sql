package com.nl2sql.metadata.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * DDL提取服务
 * 
 * 从数据库元数据提取建表语句，用于LLM扩词
 */
@Slf4j
@Service
public class DdlExtractService {
    
    @Autowired(required = false)
    private JdbcTemplate jdbcTemplate;
    
    /**
     * 提取所有表的DDL
     * 
     * @return Map<tableName, ddl>
     */
    public Map<String, String> extractAllDdls() {
        if (jdbcTemplate == null) {
            log.warn("[DdlExtract] JdbcTemplate未配置");
            return Collections.emptyMap();
        }
        
        try {
            // 1. 获取所有表名
            List<String> tableNames = queryAllTableNames();
            
            // 2. 逐个提取DDL
            Map<String, String> ddlMap = new HashMap<>();
            for (String tableName : tableNames) {
                String ddl = extractTableDdl(tableName);
                if (ddl != null) {
                    ddlMap.put(tableName, ddl);
                }
            }
            
            log.info("[DdlExtract] 成功提取{}个表的DDL", ddlMap.size());
            return ddlMap;
            
        } catch (Exception e) {
            log.error("[DdlExtract] 提取DDL失败", e);
            return Collections.emptyMap();
        }
    }
    
    /**
     * 查询所有表名
     */
    private List<String> queryAllTableNames() {
        String sql = "SELECT table_name FROM information_schema.tables " +
                    "WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE' " +
                    "AND table_name NOT LIKE 'sys_%' AND table_name NOT LIKE 'temp_%'";
        
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);
        return rows.stream()
            .map(row -> (String) row.get("table_name"))
            .toList();
    }
    
    /**
     * 提取单个表的DDL
     */
    private String extractTableDdl(String tableName) {
        try {
            // MySQL: SHOW CREATE TABLE
            String sql = "SHOW CREATE TABLE `" + tableName + "`";
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);
            
            if (!rows.isEmpty()) {
                return (String) rows.get(0).get("Create Table");
            }
            
            return null;
            
        } catch (Exception e) {
            log.warn("[DdlExtract] 提取表{}的DDL失败: {}", tableName, e.getMessage());
            return null;
        }
    }
    
    /**
     * 构建简化的表结构描述（用于LLM Prompt）
     */
    public String buildSchemaDescription(Map<String, String> ddlMap) {
        StringBuilder sb = new StringBuilder();
        
        for (Map.Entry<String, String> entry : ddlMap.entrySet()) {
            sb.append("=== 表: ").append(entry.getKey()).append(" ===\n");
            sb.append(entry.getValue()).append("\n\n");
        }
        
        return sb.toString();
    }
}
