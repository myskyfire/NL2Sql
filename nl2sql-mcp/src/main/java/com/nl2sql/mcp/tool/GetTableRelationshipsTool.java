package com.nl2sql.mcp.tool;

import com.nl2sql.mcp.datasource.DataSourcePoolManager;
import io.modelcontextprotocol.server.McpServerFeatures;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.*;

/**
 * 获取表关联关系 Tool
 */
@Slf4j
@Component
public class GetTableRelationshipsTool extends McpToolSupport {

    private final DataSourcePoolManager poolManager;

    public GetTableRelationshipsTool(DataSourcePoolManager poolManager) {
        this.poolManager = poolManager;
    }

    public McpServerFeatures.SyncToolSpecification buildGetTableRelationshipsTool() {
        return buildTool(
                "get_table_relationships",
                "获取数据库中所有表的外键关联关系",
                Map.of("datasource_id", param("integer", "数据源ID")),
                List.of("datasource_id"),
                args -> {
                    try {
                        return getRelationships(getLongParam(args, "datasource_id"));
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }
        );
    }

    private String getRelationships(Long datasourceId) throws Exception {
        List<Map<String, Object>> relationships = new ArrayList<>();

        try (Connection conn = poolManager.getConnection(datasourceId)) {
            DatabaseMetaData metaData = conn.getMetaData();

            try (ResultSet rs = metaData.getImportedKeys(null, null, null)) {
                while (rs.next()) {
                    relationships.add(Map.of(
                            "child_table", rs.getString("FKTABLE_NAME"),
                            "child_column", rs.getString("FKCOLUMN_NAME"),
                            "parent_table", rs.getString("PKTABLE_NAME"),
                            "parent_column", rs.getString("PKCOLUMN_NAME"),
                            "fk_name", rs.getString("FK_NAME"),
                            "update_rule", getRuleDescription(rs.getShort("UPDATE_RULE")),
                            "delete_rule", getRuleDescription(rs.getShort("DELETE_RULE"))
                    ));
                }
            }
        }

        return toJson(Map.of(
                "relationships", relationships,
                "count", relationships.size()
        ));
    }

    private String getRuleDescription(Short ruleCode) {
        if (ruleCode == null) return "UNKNOWN";
        return switch (ruleCode) {
            case DatabaseMetaData.importedKeyCascade -> "CASCADE";
            case DatabaseMetaData.importedKeyRestrict -> "RESTRICT";
            case DatabaseMetaData.importedKeySetNull -> "SET NULL";
            case DatabaseMetaData.importedKeySetDefault -> "SET DEFAULT";
            case DatabaseMetaData.importedKeyNoAction -> "NO ACTION";
            default -> "UNKNOWN (" + ruleCode + ")";
        };
    }
}
