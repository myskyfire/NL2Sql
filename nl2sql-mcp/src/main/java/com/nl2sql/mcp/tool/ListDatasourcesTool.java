package com.nl2sql.mcp.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nl2sql.mcp.datasource.DataSourcePoolManager;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 列出可用数据源 Tool
 * 
 * 输入：无
 * 输出：数据源列表（ID、名称、类型等，不包含密码）
 */
@Slf4j
@Component
public class ListDatasourcesTool {

    private final DataSourcePoolManager poolManager;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ListDatasourcesTool(DataSourcePoolManager poolManager) {
        this.poolManager = poolManager;
    }

    /**
     * 构建 ListDatasources Tool Specification
     */
    public McpServerFeatures.SyncToolSpecification buildListDatasourcesTool() {
        Map<String, Object> inputSchemaMap = new HashMap<>();
        inputSchemaMap.put("type", "object");
        inputSchemaMap.put("properties", new HashMap<>());
        inputSchemaMap.put("required", List.of());
        
        String inputSchemaJson;
        try {
            inputSchemaJson = objectMapper.writeValueAsString(inputSchemaMap);
        } catch (Exception e) {
            inputSchemaJson = "{}";
        }
        
        return new McpServerFeatures.SyncToolSpecification(
            new McpSchema.Tool(
                "list_datasources",
                "列出所有可用的数据源（不包含敏感信息如密码）",
                inputSchemaJson
            ),
            (exchange, args) -> {
                try {
                    log.info("执行 list_datasources");

                    List<Map<String, Object>> datasources = poolManager.getAvailableDatasources().entrySet().stream()
                        .map(entry -> {
                            Map<String, Object> ds = new HashMap<>();
                            ds.put("id", entry.getKey());
                            ds.put("name", entry.getValue().getName());
                            ds.put("jdbc_url", maskJdbcUrl(entry.getValue().getJdbcUrl()));
                            return ds;
                        })
                        .collect(Collectors.toList());

                    Map<String, Object> result = new HashMap<>();
                    result.put("datasources", datasources);
                    result.put("count", datasources.size());

                    return McpSchema.CallToolResult.builder()
                        .content(List.of(new McpSchema.TextContent(objectMapper.writeValueAsString(result))))
                        .isError(false)
                        .build();
                } catch (Exception e) {
                    log.error("list_datasources 执行失败", e);
                    return McpSchema.CallToolResult.builder()
                        .content(List.of(new McpSchema.TextContent("{\"error\": \"" + e.getMessage() + "\"}")))
                        .isError(true)
                        .build();
                }
            }
        );
    }

    /**
     * 脱敏 JDBC URL（隐藏密码）
     */
    private String maskJdbcUrl(String jdbcUrl) {
        if (jdbcUrl == null) {
            return "";
        }
        // 移除 password 参数
        return jdbcUrl.replaceAll("(?i)password=[^&]*", "password=***");
    }
}
