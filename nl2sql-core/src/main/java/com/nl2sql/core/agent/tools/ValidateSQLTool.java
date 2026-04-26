package com.nl2sql.core.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * SQL 验证 Tool - 原子能力：验证SQL语法和安全性
 */
@Slf4j
@Component
public class ValidateSQLTool {
    
    @Autowired
    private JdbcTemplate jdbcTemplate;
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    /**
     * 验证SQL语句
     * 
     * @param sql SQL语句
     * @param datasourceId 数据源ID
     * @return JSON格式的验证结果
     */
    @Tool("验证SQL语句的语法正确性和安全性。输入SQL语句和数据源ID，返回验证结果包括是否有效、错误信息、风险等级")
    public String validateSQL(String sql, Long datasourceId) {
        try {
            log.info("[ValidateSQLTool] 验证SQL: {}", sql);
            
            // 1. 基本检查
            if (sql == null || sql.trim().isEmpty()) {
                return ToolResponseBuilder.error("EMPTY_SQL", "SQL语句不能为空")
                    .addMetadata("toolName", "validate_sql")
                    .addMetadata("datasourceId", datasourceId)
                    .build();
            }
            
            String upperSQL = sql.trim().toUpperCase();
            
            // 2. 只允许SELECT语句（安全限制）
            if (!upperSQL.startsWith("SELECT")) {
                return ToolResponseBuilder.error("NON_SELECT_SQL", "只允许执行SELECT查询")
                    .addMetadata("toolName", "validate_sql")
                    .addMetadata("datasourceId", datasourceId)
                    .addMetadata("riskLevel", "HIGH")
                    .build();
            }
            
            // 3. 检查危险关键字
            List<String> dangerousKeywords = Arrays.asList("DROP", "DELETE", "UPDATE", "INSERT", "ALTER", "TRUNCATE");
            for (String keyword : dangerousKeywords) {
                if (upperSQL.contains(keyword)) {
                    return ToolResponseBuilder.error("DANGEROUS_KEYWORD", "包含危险操作: " + keyword)
                        .addMetadata("toolName", "validate_sql")
                        .addMetadata("datasourceId", datasourceId)
                        .addMetadata("riskLevel", "HIGH")
                        .build();
                }
            }
            
            // 4. 尝试EXPLAIN验证语法
            try {
                String explainSQL = "EXPLAIN " + sql;
                jdbcTemplate.queryForList(explainSQL);
                
                // ✅ 构建统一响应
                Map<String, Object> data = new HashMap<>();
                data.put("valid", true);
                data.put("error", null);
                data.put("riskLevel", "LOW");
                data.put("message", "SQL语法正确");
                
                log.info("[ValidateSQLTool] 验证完成: valid=true");
                
                return ToolResponseBuilder.success("data")
                    .withData(data)
                    .addMetadata("toolName", "validate_sql")
                    .addMetadata("datasourceId", datasourceId)
                    .build();
                
            } catch (Exception e) {
                // ✅ 构建统一响应
                Map<String, Object> data = new HashMap<>();
                data.put("valid", false);
                data.put("error", "SQL语法错误: " + e.getMessage());
                data.put("riskLevel", "MEDIUM");
                
                return ToolResponseBuilder.success("data")
                    .withData(data)
                    .addMetadata("toolName", "validate_sql")
                    .addMetadata("datasourceId", datasourceId)
                    .build();
            }
            
        } catch (Exception e) {
            log.error("[ValidateSQLTool] 验证失败", e);
            
            return ToolResponseBuilder.error("VALIDATION_ERROR", e.getMessage())
                .addMetadata("toolName", "validate_sql")
                .addMetadata("datasourceId", datasourceId)
                .build();
        }
    }
}
