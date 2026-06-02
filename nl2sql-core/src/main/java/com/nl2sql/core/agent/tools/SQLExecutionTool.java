package com.nl2sql.core.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nl2sql.core.executor.SQLExecutor;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class SQLExecutionTool {

    @Autowired
    private SQLExecutor sqlExecutor;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Deprecated
    @Tool("执行SQL查询并返回结果数据。@Deprecated - 建议使用 executeRawSQL + sql_auto_fix + ragLearnFromExecution 组合，由Workflow编排重试策略")
    public String executeSQL(
        @P("SQL语句") String sql,
        @P("数据源ID") Long datasourceId,
        @P("用户ID") Long userId,
        @P("用户名") String username
    ) {
        try {
            log.info("[SQLExecutionTool] @Deprecated - 建议使用 executeRawSQL + sql_auto_fix + ragLearnFromExecution 组合");
            log.info("[SQLExecutionTool] 执行SQL: datasourceId={}", datasourceId);

            if (sql == null || sql.trim().isEmpty()) {
                ExecutionResult result = new ExecutionResult(false, null, 0, 0.0, "SQL不能为空");
                return objectMapper.writeValueAsString(result);
            }

            if (datasourceId == null) {
                ExecutionResult result = new ExecutionResult(false, null, 0, 0.0, "数据源ID不能为空");
                return objectMapper.writeValueAsString(result);
            }

            SQLExecutor.QueryResult queryResult = sqlExecutor.executeQuery(
                sql, datasourceId, userId, username, "unknown"
            );

            if (queryResult.getError() != null) {
                log.warn("[SQLExecutionTool] 执行失败: {}", queryResult.getError());
                ExecutionResult result = new ExecutionResult(
                    false, null, 0, queryResult.getExecutionTime(), queryResult.getError()
                );
                return objectMapper.writeValueAsString(result);
            }

            log.info("[SQLExecutionTool] 执行成功: rowCount={}, executionTime={}ms",
                queryResult.getRowCount(), queryResult.getExecutionTime());

            ExecutionResult result = new ExecutionResult(
                true,
                queryResult.getData(),
                queryResult.getRowCount(),
                queryResult.getExecutionTime(),
                null,
                sql
            );
            return objectMapper.writeValueAsString(result);

        } catch (Exception e) {
            log.error("[SQLExecutionTool] 执行异常", e);
            ExecutionResult result = new ExecutionResult(false, null, 0, 0.0, e.getMessage());
            try {
                return objectMapper.writeValueAsString(result);
            } catch (Exception ex) {
                return "{\"success\":false,\"error\":\"执行失败: " + e.getMessage() + "\"}";
            }
        }
    }

    @Data
    public static class ExecutionResult {
        public boolean success;
        public List<Map<String, Object>> data;
        public int rowCount;
        public Double executionTime;
        public String error;
        public String sql;

        public ExecutionResult(boolean success, List<Map<String, Object>> data,
                              int rowCount, Double executionTime, String error) {
            this.success = success;
            this.data = data;
            this.rowCount = rowCount;
            this.executionTime = executionTime;
            this.error = error;
            this.sql = null;
        }

        public ExecutionResult(boolean success, List<Map<String, Object>> data,
                              int rowCount, Double executionTime, String error, String sql) {
            this.success = success;
            this.data = data;
            this.rowCount = rowCount;
            this.executionTime = executionTime;
            this.error = error;
            this.sql = sql;
        }
    }
}
