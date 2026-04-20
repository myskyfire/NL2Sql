package com.nl2sql.core.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 索引检查 Tool - 原子能力：检查表的索引情况
 */
@Slf4j
@Component
public class CheckIndexTool {
    
    @Autowired
    private JdbcTemplate jdbcTemplate;
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    @Tool("检查指定表的索引情况。输入表名和数据源ID，返回索引列表、字段覆盖情况等")
    public String checkIndex(String tableName, Long datasourceId) {
        try {
            log.info("[CheckIndexTool] 检查索引: table={}", tableName);
            
            // 查询索引信息
            List<Map<String, Object>> indexes = jdbcTemplate.queryForList(
                "SELECT INDEX_NAME, COLUMN_NAME, SEQ_IN_INDEX, NON_UNIQUE, INDEX_TYPE " +
                "FROM information_schema.STATISTICS " +
                "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? " +
                "ORDER BY INDEX_NAME, SEQ_IN_INDEX",
                tableName
            );
            
            // 按索引分组
            Map<String, List<Map<String, Object>>> indexMap = new LinkedHashMap<>();
            for (Map<String, Object> row : indexes) {
                String indexName = (String) row.get("INDEX_NAME");
                indexMap.computeIfAbsent(indexName, k -> new ArrayList<>()).add(row);
            }
            
            List<Map<String, Object>> result = new ArrayList<>();
            for (Map.Entry<String, List<Map<String, Object>>> entry : indexMap.entrySet()) {
                Map<String, Object> indexInfo = new HashMap<>();
                indexInfo.put("indexName", entry.getKey());
                indexInfo.put("isUnique", entry.getValue().get(0).get("NON_UNIQUE").equals(0));
                indexInfo.put("indexType", entry.getValue().get(0).get("INDEX_TYPE"));
                
                List<String> columns = new ArrayList<>();
                for (Map<String, Object> col : entry.getValue()) {
                    columns.add((String) col.get("COLUMN_NAME"));
                }
                indexInfo.put("columns", columns);
                
                result.add(indexInfo);
            }
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("tableName", tableName);
            response.put("indexCount", result.size());
            response.put("indexes", result);
            
            return objectMapper.writeValueAsString(response);
            
        } catch (Exception e) {
            log.error("[CheckIndexTool] 检查失败", e);
            return "{\"success\":false,\"error\":\"" + e.getMessage() + "\"}";
        }
    }
}
