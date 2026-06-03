package com.nl2sql.core.agent.tools;

import com.nl2sql.core.datasource.DatasourceAccessService;
import com.nl2sql.core.executor.SQLExecutor;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
public class ExecuteRawSQLTool {

    @Autowired
    private SQLExecutor sqlExecutor;

    @Autowired
    private DatasourceAccessService datasourceAccessService;

    @Tool("纯执行SQL查询，不包含任何重试、修正或RAG学习逻辑。仅执行一次，成功返回数据，失败返回错误。适用于Skill编排中由上层控制重试策略的场景")
    public String executeRawSQL(
        @P("要执行的SQL语句，必须是SELECT语句") String sql,
        @P("数据源ID") Long datasourceId,
        @P("用户ID（可选）") Long userId,
        @P("用户名（可选）") String username
    ) {
        try {
            log.info("[ExecuteRawSQLTool] 执行SQL: datasourceId={}, mcp={}, sql={}", 
                datasourceId, datasourceAccessService.isMcpEnabled(), 
                sql != null ? sql.substring(0, Math.min(80, sql.length())) : "null");

            if (sql == null || sql.trim().isEmpty()) {
                return ToolResponseBuilder.error("INVALID_INPUT", "SQL不能为空")
                    .addMetadata("toolName", "execute_raw_sql")
                    .build();
            }

            if (datasourceId == null) {
                return ToolResponseBuilder.error("INVALID_INPUT", "数据源ID不能为空")
                    .addMetadata("toolName", "execute_raw_sql")
                    .build();
            }

            String upperSQL = sql.trim().toUpperCase();
            if (!upperSQL.startsWith("SELECT") && !upperSQL.startsWith("WITH")) {
                return ToolResponseBuilder.error("FORBIDDEN_OPERATION", "仅允许执行SELECT查询")
                    .addMetadata("toolName", "execute_raw_sql")
                    .build();
            }

            long startTime = System.currentTimeMillis();

            // MCP 模式：通过 DatasourceAccessService 执行（自动降级到 JDBC）
            if (datasourceAccessService.isMcpEnabled()) {
                DatasourceAccessService.SqlExecutionResult mcpResult = 
                    datasourceAccessService.executeSql(datasourceId, sql, userId);

                double executionTime = (System.currentTimeMillis() - startTime) / 1000.0;

                if (!mcpResult.isSuccess()) {
                    log.warn("[ExecuteRawSQLTool] MCP执行失败: {}", mcpResult.getError());

                    Map<String, Object> data = new HashMap<>();
                    data.put("success", false);
                    data.put("error", mcpResult.getError());
                    data.put("sql", sql);
                    data.put("executionTime", executionTime);

                    return ToolResponseBuilder.success("data")
                        .withData(data)
                        .addMetadata("toolName", "execute_raw_sql")
                        .addMetadata("datasourceId", datasourceId)
                        .addMetadata("accessMode", "MCP")
                        .build();
                }

                log.info("[ExecuteRawSQLTool] MCP执行成功: rowCount={}, executionTime={}s", mcpResult.getRowCount(), executionTime);

                Map<String, Object> data = new HashMap<>();
                data.put("success", true);
                data.put("data", mcpResult.getData());
                data.put("rowCount", mcpResult.getRowCount());
                data.put("executionTime", executionTime);
                data.put("sql", sql);
                data.put("datasourceId", datasourceId);

                return ToolResponseBuilder.success("data")
                    .withData(data)
                    .addMetadata("toolName", "execute_raw_sql")
                    .addMetadata("datasourceId", datasourceId)
                    .addMetadata("accessMode", "MCP")
                    .build();
            }

            // JDBC 模式：走原有 SQLExecutor（保留值映射、列名翻译等本地增强）
            SQLExecutor.QueryResult result = sqlExecutor.executeQuery(
                sql, datasourceId, userId, username, "unknown"
            );

            double executionTime = (System.currentTimeMillis() - startTime) / 1000.0;

            if (result.getError() != null) {
                log.warn("[ExecuteRawSQLTool] 执行失败: {}", result.getError());

                Map<String, Object> data = new HashMap<>();
                data.put("success", false);
                data.put("error", result.getError());
                data.put("sql", sql);
                data.put("executionTime", executionTime);

                return ToolResponseBuilder.success("data")
                    .withData(data)
                    .addMetadata("toolName", "execute_raw_sql")
                    .addMetadata("datasourceId", datasourceId)
                    .addMetadata("accessMode", "JDBC")
                    .build();
            }

            log.info("[ExecuteRawSQLTool] 执行成功: rowCount={}, executionTime={}s", result.getRowCount(), executionTime);

            Map<String, Object> data = new HashMap<>();
            data.put("success", true);
            data.put("data", result.getData());
            data.put("rowCount", result.getRowCount());
            data.put("executionTime", executionTime);
            data.put("sql", sql);
            data.put("datasourceId", datasourceId);

            return ToolResponseBuilder.success("data")
                .withData(data)
                .addMetadata("toolName", "execute_raw_sql")
                .addMetadata("datasourceId", datasourceId)
                .addMetadata("accessMode", "JDBC")
                .build();

        } catch (Exception e) {
            log.error("[ExecuteRawSQLTool] 执行异常", e);
            return ToolResponseBuilder.error("EXECUTION_ERROR", e.getMessage())
                .addMetadata("toolName", "execute_raw_sql")
                .addMetadata("datasourceId", datasourceId)
                .build();
        }
    }
}
