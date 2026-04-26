package com.nl2sql.core.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 执行安全SQL Tool - 原子能力：执行经过验证的SQL查询
 */
@Slf4j
@Component
public class ExecuteSafeSQLTool {
    
    @Autowired
    private SQLExecutionTool sqlExecutionTool;
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    /**
     * 执行安全的SQL查询
     * 
     * @param sql SQL语句
     * @param datasourceId 数据源ID
     * @param userId 用户ID
     * @param username 用户名
     * @return JSON格式的查询结果
     */
    @Tool("执行经过验证的安全SQL查询。输入SQL语句、数据源ID、用户ID和用户名，返回查询结果")
    public String executeSafeSQL(String sql, Long datasourceId, Long userId, String username) {
        try {
            log.info("[ExecuteSafeSQLTool] 执行SQL: datasourceId={}, userId={}", datasourceId, userId);
            
            // 调用 SQLExecutionTool 执行SQL
            SQLExecutionTool.ExecutionResult result = sqlExecutionTool.executeSQL(sql, datasourceId, userId, username);
            
            if (result.isSuccess()) {
                // ✅ 构建统一响应
                Map<String, Object> data = new HashMap<>();
                data.put("rows", result.getData());
                data.put("rowCount", result.getData() != null ? result.getData().size() : 0);
                data.put("executionTime", result.getExecutionTime());
                data.put("sql", sql);
                
                log.info("[ExecuteSafeSQLTool] 执行成功: rowCount={}", data.get("rowCount"));
                
                return ToolResponseBuilder.success("data")
                    .withData(data)
                    .addMetadata("toolName", "execute_safe_sql")
                    .addMetadata("datasourceId", datasourceId)
                    .addMetadata("userId", userId)
                    .build();
            } else {
                log.warn("[ExecuteSafeSQLTool] 执行失败: {}", result.getError());
                
                return ToolResponseBuilder.error("SQL_EXECUTION_ERROR", result.getError())
                    .addMetadata("toolName", "execute_safe_sql")
                    .addMetadata("datasourceId", datasourceId)
                    .addMetadata("userId", userId)
                    .build();
            }
            
        } catch (Exception e) {
            log.error("[ExecuteSafeSQLTool] 执行异常", e);
            
            return ToolResponseBuilder.error("SQL_EXCEPTION", e.getMessage())
                .addMetadata("toolName", "execute_safe_sql")
                .addMetadata("datasourceId", datasourceId)
                .addMetadata("userId", userId)
                .build();
        }
    }
}
