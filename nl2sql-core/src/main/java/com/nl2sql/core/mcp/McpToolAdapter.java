package com.nl2sql.core.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nl2sql.core.agent.tool.BaseTool;
import com.nl2sql.core.agent.tool.ToolContext;
import com.nl2sql.core.agent.tool.ToolResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * MCP Tool 适配器
 * 
 * 将 MCP Server 的 Tool 适配为 nl2sql-core 的 BaseTool 接口，
 * 使得主程序可以通过统一接口调用 MCP 能力，无需修改现有逻辑。
 * 
 * 使用方式：
 * 1. 在 application.yml 中配置 nl2sql.mcp.enabled=true
 * 2. 注入此 Bean 并像普通 BaseTool 一样使用
 * 
 * 注意：
 * - 默认关闭，不影响现有 JDBC 逻辑
 * - 每个 MCP Tool 对应一个适配器实例
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "nl2sql.mcp", name = "enabled", havingValue = "true")
public class McpToolAdapter implements BaseTool {

    @Autowired(required = false)
    private McpClientService mcpClient;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // ==================== 数据源管理 ====================

    /**
     * 列出所有可用数据源
     */
    public ToolResult listDatasources() {
        try {
            String result = mcpClient.listDatasources();
            return ToolResult.success(parseJson(result));
        } catch (Exception e) {
            return ToolResult.error("列出数据源失败: " + e.getMessage());
        }
    }

    /**
     * 添加数据源
     */
    public ToolResult addDatasource(String name, String jdbcUrl, String username, String password) {
        try {
            String result = mcpClient.addDatasource(name, jdbcUrl, username, password);
            return ToolResult.success(parseJson(result));
        } catch (Exception e) {
            return ToolResult.error("添加数据源失败: " + e.getMessage());
        }
    }

    // ==================== 数据库操作 ====================

    /**
     * 查询表结构
     */
    public ToolResult querySchema(Long datasourceId, String tableName) {
        try {
            String result = mcpClient.querySchema(datasourceId, tableName);
            return ToolResult.success(parseJson(result));
        } catch (Exception e) {
            return ToolResult.error("查询表结构失败: " + e.getMessage());
        }
    }

    /**
     * 执行只读 SQL
     */
    public ToolResult executeSql(Long datasourceId, String sql) {
        try {
            String result = mcpClient.executeSql(datasourceId, sql);
            return ToolResult.success(parseJson(result));
        } catch (Exception e) {
            return ToolResult.error("执行 SQL 失败: " + e.getMessage());
        }
    }

    /**
     * 获取表关联关系
     */
    public ToolResult getTableRelationships(Long datasourceId) {
        try {
            String result = mcpClient.getTableRelationships(datasourceId);
            return ToolResult.success(parseJson(result));
        } catch (Exception e) {
            return ToolResult.error("获取表关联关系失败: " + e.getMessage());
        }
    }

    // ==================== SQL 生成与验证 ====================

    /**
     * 生成 SQL
     */
    public ToolResult generateSql(Long datasourceId, String question) {
        try {
            String result = mcpClient.generateSql(datasourceId, question);
            return ToolResult.success(parseJson(result));
        } catch (Exception e) {
            return ToolResult.error("生成 SQL 失败: " + e.getMessage());
        }
    }

    /**
     * 验证 SQL
     */
    public ToolResult validateSql(Long datasourceId, String sql) {
        try {
            String result = mcpClient.validateSql(datasourceId, sql);
            return ToolResult.success(parseJson(result));
        } catch (Exception e) {
            return ToolResult.error("验证 SQL 失败: " + e.getMessage());
        }
    }

    // ==================== BaseTool 接口实现 ====================
    // 以下实现使得 McpToolAdapter 可以作为通用 MCP 入口被 ToolRegistry 注册

    @Override
    public String getName() {
        return "mcp_tool";
    }

    @Override
    public String getDescription() {
        return "通过 MCP 协议调用远端数据库操作能力（数据源管理、SQL执行、表结构查询等）。当 nl2sql.mcp.enabled=true 时生效。";
    }

    @Override
    public Map<String, Object> getParameterSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> actionParam = new HashMap<>();
        actionParam.put("type", "string");
        actionParam.put("description", "MCP 操作名称: list_datasources, add_datasource, query_schema, execute_sql, get_table_relationships, generate_sql, validate_sql");
        properties.put("action", actionParam);

        Map<String, Object> datasourceParam = new HashMap<>();
        datasourceParam.put("type", "integer");
        datasourceParam.put("description", "数据源ID");
        properties.put("datasourceId", datasourceParam);

        Map<String, Object> sqlParam = new HashMap<>();
        sqlParam.put("type", "string");
        sqlParam.put("description", "SQL 语句");
        properties.put("sql", sqlParam);

        Map<String, Object> tableParam = new HashMap<>();
        tableParam.put("type", "string");
        tableParam.put("description", "表名");
        properties.put("tableName", tableParam);

        Map<String, Object> questionParam = new HashMap<>();
        questionParam.put("type", "string");
        questionParam.put("description", "自然语言问题");
        properties.put("question", questionParam);

        schema.put("properties", properties);
        schema.put("required", List.of("action"));

        return schema;
    }

    @Override
    public ToolResult execute(ToolContext context) {
        if (mcpClient == null || !mcpClient.isAvailable()) {
            return ToolResult.error("MCP Client 未启用或不可用，请配置 nl2sql.mcp.enabled=true");
        }

        String action = context.getRequiredParameter("action");

        try {
            return switch (action) {
                case "list_datasources" -> listDatasources();
                case "add_datasource" -> addDatasource(
                        context.getRequiredParameter("name"),
                        context.getRequiredParameter("jdbcUrl"),
                        context.getRequiredParameter("username"),
                        context.getRequiredParameter("password")
                );
                case "query_schema" -> querySchema(
                        context.getRequiredParameter("datasourceId"),
                        context.getRequiredParameter("tableName")
                );
                case "execute_sql" -> executeSql(
                        context.getRequiredParameter("datasourceId"),
                        context.getRequiredParameter("sql")
                );
                case "get_table_relationships" -> getTableRelationships(
                        context.getRequiredParameter("datasourceId")
                );
                case "generate_sql" -> generateSql(
                        context.getRequiredParameter("datasourceId"),
                        context.getRequiredParameter("question")
                );
                case "validate_sql" -> validateSql(
                        context.getRequiredParameter("datasourceId"),
                        context.getRequiredParameter("sql")
                );
                default -> ToolResult.error("未知的 MCP 操作: " + action);
            };
        } catch (Exception e) {
            return ToolResult.error("MCP 操作执行失败: " + e.getMessage());
        }
    }

    @Override
    public String getApplicableScenarios() {
        return "适用于以下场景：\n" +
               "1. 通过 MCP 协议远程管理数据源\n" +
               "2. 通过 MCP 协议执行只读 SQL 查询\n" +
               "3. 通过 MCP 协议查询表结构和关联关系\n" +
               "4. 将数据库操作与主程序解耦";
    }

    @Override
    public String getInapplicableScenarios() {
        return "不适用于以下场景：\n" +
               "1. 需要低延迟的本地数据库操作\n" +
               "2. MCP Server 未部署或不可达时";
    }

    // ==================== 内部方法 ====================

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJson(String json) {
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            return Map.of("raw_response", json);
        }
    }
}
