package com.nl2sql.mcp.tool;

import com.nl2sql.mcp.datasource.DataSourcePoolManager;
import io.modelcontextprotocol.server.McpServerFeatures;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 添加数据源 Tool
 */
@Slf4j
@Component
public class AddDatasourceTool extends McpToolSupport {

    private final DataSourcePoolManager poolManager;

    public AddDatasourceTool(DataSourcePoolManager poolManager) {
        this.poolManager = poolManager;
    }

    public McpServerFeatures.SyncToolSpecification buildAddDatasourceTool() {
        return buildTool(
                "add_datasource",
                "添加新的数据源配置（凭据由 MCP Server 安全存储）",
                Map.of(
                        "name", param("string", "数据源名称（如：主数据库、财务数据库）"),
                        "jdbc_url", param("string", "JDBC 连接URL（如：jdbc:mysql://localhost:3306/dbname）"),
                        "username", param("string", "数据库用户名"),
                        "password", param("string", "数据库密码")
                ),
                List.of("name", "jdbc_url", "username", "password"),
                args -> addDatasource(
                        getStringParam(args, "name"),
                        getStringParam(args, "jdbc_url"),
                        getStringParam(args, "username"),
                        getStringParam(args, "password")
                )
        );
    }

    private String addDatasource(String name, String jdbcUrl, String username, String password) {
        Long datasourceId = poolManager.addDatasource(name, jdbcUrl, username, password);
        return toJson(Map.of(
                "datasource_id", datasourceId,
                "name", name,
                "message", "数据源添加成功"
        ));
    }
}
