package com.nl2sql.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nl2sql.mcp.tool.AddDatasourceTool;
import com.nl2sql.mcp.tool.ExecuteSqlTool;
import com.nl2sql.mcp.tool.GenerateSqlTool;
import com.nl2sql.mcp.tool.GetTableRelationshipsTool;
import com.nl2sql.mcp.tool.ListDatasourcesTool;
import com.nl2sql.mcp.tool.QuerySchemaTool;
import com.nl2sql.mcp.tool.ValidateSqlTool;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * NL2SQL MCP Server 启动类
 * 
 * 提供标准化的数据访问能力：
 * - query_schema: 查询表结构
 * - execute_sql: 执行只读 SQL
 * - get_table_relationships: 获取表关联关系
 */
@Slf4j
@SpringBootApplication
public class McpServerApplication {

    private final QuerySchemaTool querySchemaTool;
    private final ExecuteSqlTool executeSqlTool;
    private final GetTableRelationshipsTool getTableRelationshipsTool;
    private final ListDatasourcesTool listDatasourcesTool;
    private final AddDatasourceTool addDatasourceTool;
    private final GenerateSqlTool generateSqlTool;
    private final ValidateSqlTool validateSqlTool;

    public McpServerApplication(QuerySchemaTool querySchemaTool, 
                                ExecuteSqlTool executeSqlTool,
                                GetTableRelationshipsTool getTableRelationshipsTool,
                                ListDatasourcesTool listDatasourcesTool,
                                AddDatasourceTool addDatasourceTool,
                                GenerateSqlTool generateSqlTool,
                                ValidateSqlTool validateSqlTool) {
        this.querySchemaTool = querySchemaTool;
        this.executeSqlTool = executeSqlTool;
        this.getTableRelationshipsTool = getTableRelationshipsTool;
        this.listDatasourcesTool = listDatasourcesTool;
        this.addDatasourceTool = addDatasourceTool;
        this.generateSqlTool = generateSqlTool;
        this.validateSqlTool = validateSqlTool;
    }

    public static void main(String[] args) {
        SpringApplication.run(McpServerApplication.class, args);
        log.info("NL2SQL MCP Server 启动成功");
    }

    /**
     * 创建 MCP Server Bean（使用 Stdio Transport）
     */
    @Bean
    public McpSyncServer mcpServer() {
        StdioServerTransportProvider transportProvider = new StdioServerTransportProvider();

        McpSyncServer server = McpServer.sync(transportProvider)
            .serverInfo("nl2sql-mcp", "1.0.0")
            .capabilities(McpSchema.ServerCapabilities.builder()
                .tools(true)  // 启用 Tools 能力
                .build())
            .tools(
                listDatasourcesTool.buildListDatasourcesTool(),
                addDatasourceTool.buildAddDatasourceTool(),
                querySchemaTool.buildQuerySchemaTool(),
                executeSqlTool.buildExecuteSqlTool(),
                getTableRelationshipsTool.buildGetTableRelationshipsTool(),
                generateSqlTool.buildGenerateSqlTool(),
                validateSqlTool.buildValidateSqlTool()
            )
            .build();

        log.info("MCP Server 启动成功，已注册 7 个 Tools: list_datasources, add_datasource, query_schema, execute_sql, get_table_relationships, generate_sql, validate_sql");
        return server;
    }
}
