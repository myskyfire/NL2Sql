package com.nl2sql.core.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * SQL 执行 Tool - 原子能力：执行SQL查询并返回结果
 */
@Slf4j
@Component
public class ExecuteSQLTool {
    
    @Autowired
    private JdbcTemplate jdbcTemplate;
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    /**
     * 执行SQL查询
     * 
     * @param sql SQL语句
     * @param datasourceId 数据源ID
     * @return JSON格式的查询结果
     */
    @Tool("执行SQL查询并返回结果。输入SQL语句和数据源ID，返回查询结果的JSON格式数据")
    public String executeSQL(String sql, Long datasourceId) {
        try {
            log.info("[ExecuteSQLTool] 执行SQL: {}", sql);
            
            // 安全检查：只允许SELECT语句
            String upperSQL = sql.trim().toUpperCase();
            if (!upperSQL.startsWith("SELECT")) {
                return "{\"success\":false,\"error\":\"只允许执行SELECT查询\"}";
            }
            
            // 执行查询
            List<Map<String, Object>> results = jdbcTemplate.queryForList(sql);
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("rowCount", results.size());
            response.put("data", results);
            
            if (!results.isEmpty()) {
                response.put("columns", new ArrayList<>(results.get(0).keySet()));
            }
            
            log.info("[ExecuteSQLTool] 查询成功，返回 {} 行数据", results.size());
            
            return objectMapper.writeValueAsString(response);
            
        } catch (Exception e) {
            log.error("[ExecuteSQLTool] 执行失败", e);
            
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
