package com.nl2sql.mcp.tool;

import com.nl2sql.mcp.datasource.DataSourcePoolManager;
import io.modelcontextprotocol.server.McpServerFeatures;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 列出数据源所有表 Tool
 */
@Slf4j
@Component
public class ListTablesTool extends McpToolSupport {

    private final DataSourcePoolManager poolManager;

    public ListTablesTool(DataSourcePoolManager poolManager) {
        this.poolManager = poolManager;
    }

    public McpServerFeatures.SyncToolSpecification buildListTablesTool() {
        return buildTool(
                "list_tables",
                "列出指定数据源中的所有表名及注释",
                Map.of(
                        "datasource_id", param("integer", "数据源ID"),
                        "schema_pattern", param("string", "Schema 匹配模式（可选，如 public）")
                ),
                List.of("datasource_id"),
                args -> {
                    try {
                        return listTables(getLongParam(args, "datasource_id"), getStringParam(args, "schema_pattern"));
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }
        );
    }

    private String listTables(Long datasourceId, String schemaPattern) throws Exception {
        List<Map<String, Object>> tables = new ArrayList<>();

        try (Connection conn = poolManager.getConnection(datasourceId)) {
            DatabaseMetaData metaData = conn.getMetaData();
            String schema = (schemaPattern != null && !schemaPattern.isEmpty()) ? schemaPattern : null;

            try (ResultSet rs = metaData.getTables(null, schema, "%", new String[]{"TABLE"})) {
                while (rs.next()) {
                    tables.add(Map.of(
                            "table_name", rs.getString("TABLE_NAME"),
                            "table_type", rs.getString("TABLE_TYPE"),
                            "remarks", rs.getString("REMARKS") != null ? rs.getString("REMARKS") : ""
                    ));
                }
            }
        }

        return toJson(Map.of(
                "datasource_id", datasourceId,
                "tables", tables,
                "table_count", tables.size()
        ));
    }
}
