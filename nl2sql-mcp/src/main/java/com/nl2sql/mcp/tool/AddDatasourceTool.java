package com.nl2sql.mcp.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nl2sql.mcp.datasource.DataSourcePoolManager;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 添加数据源 Tool
 * 
 * 输入：name, jdbc_url, username, password
 * 输出：新数据源的 ID
 */
@Slf4j
@Component
public class AddDatasourceTool {

    private final DataSourcePoolManager poolManager;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AddDatasourceTool(DataSourcePoolManager poolManager) {
        this.poolManager = poolManager;
    }

    /**
     * 构建 AddDatasource Tool Specification
     */
    public McpServerFeatures.SyncToolSpecification buildAddDatasourceTool() {
        Map<String, Object> properties = new HashMap<>();
        properties.put("name", Map.of(
            "type", "string",
            "description", "数据源名称（如：主数据库、财务数据库）"
        ));
        properties.put("jdbc_url", Map.of(
            "type", "string",
            "description", "JDBC 连接URL（如：jdbc:mysql://localhost:3306/dbname）"
        ));
        properties.put("username", Map.of(
            "type", "string",
            "description", "数据库用户名"
        ));
        properties.put("password", Map.of(
            "type", "string",
            "description", "数据库密码"
        ));
        
        Map<String, Object> inputSchemaMap = new HashMap<>();
        inputSchemaMap.put("type", "object");
        inputSchemaMap.put("properties", properties);
        inputSchemaMap.put("required", List.of("name", "jdbc_url", "username", "password"));
        
        String inputSchemaJson;
        try {
            inputSchemaJson = objectMapper.writeValueAsString(inputSchemaMap);
        } catch (Exception e) {
            inputSchemaJson = "{}";
        }
        
        return new McpServerFeatures.SyncToolSpecification(
            new McpSchema.Tool(
                "add_datasource",
                "添加新的数据源配置（凭据由 MCP Server 安全存储）",
                inputSchemaJson
            ),
            (exchange, args) -> {
                try {
                    String name = (String) args.get("name");
                    String jdbcUrl = (String) args.get("jdbc_url");
                    String username = (String) args.get("username");
                    String password = (String) args.get("password");

                    log.info("执行 add_datasource: name={}", name);

                    Long datasourceId = poolManager.addDatasource(name, jdbcUrl, username, password);

                    Map<String, Object> result = new HashMap<>();
                    result.put("datasource_id", datasourceId);
                    result.put("name", name);
                    result.put("message", "数据源添加成功");

                    return McpSchema.CallToolResult.builder()
                        .content(List.of(new McpSchema.TextContent(objectMapper.writeValueAsString(result))))
                        .isError(false)
                        .build();
                } catch (Exception e) {
                    log.error("add_datasource 执行失败", e);
                    return McpSchema.CallToolResult.builder()
                        .content(List.of(new McpSchema.TextContent("{\"error\": \"" + e.getMessage() + "\"}")))
                        .isError(true)
                        .build();
                }
            }
        );
    }
}
