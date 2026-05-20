package com.nl2sql.mcp.tool;

import com.nl2sql.mcp.datasource.DataSourcePoolManager;
import io.modelcontextprotocol.server.McpServerFeatures;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 列出可用数据源 Tool
 */
@Slf4j
@Component
public class ListDatasourcesTool extends McpToolSupport {

    private final DataSourcePoolManager poolManager;

    public ListDatasourcesTool(DataSourcePoolManager poolManager) {
        this.poolManager = poolManager;
    }

    public McpServerFeatures.SyncToolSpecification buildListDatasourcesTool() {
        return buildTool(
                "list_datasources",
                "列出所有可用的数据源（不包含敏感信息如密码）",
                Map.of(),
                List.of(),
                args -> listDatasources()
        );
    }

    private String listDatasources() {
        List<Map<String, Object>> datasources = poolManager.getAvailableDatasources().entrySet().stream()
                .map(entry -> Map.of(
                        "id", (Object) entry.getKey(),
                        "name", entry.getValue().getName(),
                        "jdbc_url", maskJdbcUrl(entry.getValue().getJdbcUrl())
                ))
                .collect(Collectors.toList());

        return toJson(Map.of(
                "datasources", datasources,
                "count", datasources.size()
        ));
    }

    private String maskJdbcUrl(String jdbcUrl) {
        if (jdbcUrl == null) return "";
        return jdbcUrl.replaceAll("(?i)password=[^&]*", "password=***");
    }
}
