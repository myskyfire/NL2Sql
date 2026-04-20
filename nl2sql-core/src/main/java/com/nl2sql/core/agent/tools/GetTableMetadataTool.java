package com.nl2sql.core.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 获取表元数据 Tool - 原子能力：查询表结构和字段信息
 */
@Slf4j
@Component
public class GetTableMetadataTool {
    
    @Autowired
    private JdbcTemplate jdbcTemplate;
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    /**
     * 获取表的元数据信息
     * 
     * @param tableName 表名
     * @param datasourceId 数据源ID
     * @return JSON格式的表结构信息
     */
    @Tool("获取指定表的元数据信息，包括表注释、字段列表、数据类型等。输入表名和数据源ID")
    public String getTableMetadata(String tableName, Long datasourceId) {
        try {
            log.info("[GetTableMetadataTool] 获取表元数据: {}", tableName);
            
            // 获取表注释
            String tableComment = jdbcTemplate.queryForObject(
                "SELECT DISTINCT table_comment FROM column_metadata WHERE datasource_id = ? AND table_name = ? LIMIT 1",
                String.class, datasourceId, tableName
            );
            
            // 获取字段列表
            List<Map<String, Object>> columns = jdbcTemplate.queryForList(
                "SELECT column_name, data_type, column_comment, is_primary_key, ordinal_position " +
                "FROM column_metadata WHERE datasource_id = ? AND table_name = ? " +
                "ORDER BY ordinal_position",
                datasourceId, tableName
            );
            
            if (columns.isEmpty()) {
                return "{\"success\":false,\"error\":\"表不存在或没有字段信息\"}";
            }
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("tableName", tableName);
            response.put("tableComment", tableComment != null ? tableComment : "");
            response.put("columnCount", columns.size());
            response.put("columns", columns);
            
            log.info("[GetTableMetadataTool] 获取成功，共 {} 个字段", columns.size());
            
            return objectMapper.writeValueAsString(response);
            
        } catch (Exception e) {
            log.error("[GetTableMetadataTool] 获取失败", e);
            
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("error", e.getMessage());
            
            try {
                return objectMapper.writeValueAsString(error);
            } catch (Exception ex) {
                return "{\"success\":false,\"error\":\"序列化失败\"}";
            }
        }
    }
}
