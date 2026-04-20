package com.nl2sql.core.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 数据库统计信息 Tool - 原子能力：获取表大小、行数等统计信息
 */
@Slf4j
@Component
public class GetDatabaseStatsTool {
    
    @Autowired
    private JdbcTemplate jdbcTemplate;
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    @Tool("获取数据库表的统计信息，包括表大小、行数、索引数量等。输入数据源ID和可选的表名列表")
    public String getDatabaseStats(Long datasourceId, String tableNames) {
        try {
            log.info("[GetDatabaseStatsTool] 获取统计信息: datasourceId={}, tables={}", datasourceId, tableNames);
            
            List<Map<String, Object>> stats = new ArrayList<>();
            
            // 查询所有表的统计信息
            String sql = "SELECT table_name, table_rows, data_length, index_length, " +
                        "(data_length + index_length) as total_size, " +
                        "ROUND((data_length + index_length) / 1024 / 1024, 2) as size_mb " +
                        "FROM information_schema.tables " +
                        "WHERE table_schema = DATABASE()";
            
            if (tableNames != null && !tableNames.trim().isEmpty()) {
                String[] tables = tableNames.split(",");
                StringBuilder whereClause = new StringBuilder(" AND table_name IN (");
                for (int i = 0; i < tables.length; i++) {
                    if (i > 0) whereClause.append(",");
                    whereClause.append("'").append(tables[i].trim()).append("'");
                }
                whereClause.append(")");
                sql += whereClause.toString();
            }
            
            List<Map<String, Object>> results = jdbcTemplate.queryForList(sql);
            
            for (Map<String, Object> row : results) {
                Map<String, Object> stat = new HashMap<>();
                stat.put("tableName", row.get("table_name"));
                stat.put("rowCount", row.get("table_rows"));
                stat.put("dataSize", row.get("data_length"));
                stat.put("indexSize", row.get("index_length"));
                stat.put("totalSize", row.get("total_size"));
                stat.put("sizeMB", row.get("size_mb"));
                stats.add(stat);
            }
            
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("tableCount", stats.size());
            result.put("stats", stats);
            
            return objectMapper.writeValueAsString(result);
            
        } catch (Exception e) {
            log.error("[GetDatabaseStatsTool] 获取失败", e);
            return "{\"success\":false,\"error\":\"" + e.getMessage() + "\"}";
        }
    }
}
