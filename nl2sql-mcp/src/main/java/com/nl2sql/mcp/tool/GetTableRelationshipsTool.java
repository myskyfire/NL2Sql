package com.nl2sql.mcp.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nl2sql.mcp.datasource.DataSourcePoolManager;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.*;

/**
 * 获取表关联关系 Tool
 * 
 * 输入：datasource_id, jdbc_url, username, password
 * 输出：数据库中所有表的外键关联关系
 */
@Slf4j
@Component
public class GetTableRelationshipsTool {

    private final DataSourcePoolManager poolManager;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public GetTableRelationshipsTool(DataSourcePoolManager poolManager) {
        this.poolManager = poolManager;
    }

    /**
     * 构建 GetTableRelationships Tool Specification
     */
    public McpServerFeatures.SyncToolSpecification buildGetTableRelationshipsTool() {
        Map<String, Object> properties = new HashMap<>();
        properties.put("datasource_id", Map.of(
            "type", "integer",
            "description", "数据源ID（从可用数据源列表中选择）"
        ));
        
        Map<String, Object> inputSchemaMap = new HashMap<>();
        inputSchemaMap.put("type", "object");
        inputSchemaMap.put("properties", properties);
        inputSchemaMap.put("required", List.of("datasource_id"));
        
        String inputSchemaJson;
        try {
            inputSchemaJson = objectMapper.writeValueAsString(inputSchemaMap);
        } catch (Exception e) {
            inputSchemaJson = "{}";
        }
        
        return new McpServerFeatures.SyncToolSpecification(
            new McpSchema.Tool(
                "get_table_relationships",
                "获取数据库中所有表的外键关联关系",
                inputSchemaJson
            ),
            (exchange, args) -> {
                try {
                    Long datasourceId = ((Number) args.get("datasource_id")).longValue();

                    log.info("执行 get_table_relationships: datasourceId={}", datasourceId);

                    List<Map<String, Object>> relationships = getRelationships(datasourceId);

                    Map<String, Object> result = new HashMap<>();
                    result.put("relationships", relationships);
                    result.put("count", relationships.size());

                    return McpSchema.CallToolResult.builder()
                        .content(List.of(new McpSchema.TextContent(objectMapper.writeValueAsString(result))))
                        .isError(false)
                        .build();
                } catch (Exception e) {
                    log.error("get_table_relationships 执行失败", e);
                    return McpSchema.CallToolResult.builder()
                        .content(List.of(new McpSchema.TextContent("{\"error\": \"" + e.getMessage() + "\"}")))
                        .isError(true)
                        .build();
                }
            }
        );
    }

    /**
     * 获取表关联关系
     */
    private List<Map<String, Object>> getRelationships(Long datasourceId) throws Exception {
        List<Map<String, Object>> relationships = new ArrayList<>();

        try (Connection conn = poolManager.getConnection(datasourceId)) {
            DatabaseMetaData metaData = conn.getMetaData();

            // 获取所有表的外键信息
            try (ResultSet rs = metaData.getImportedKeys(null, null, null)) {
                while (rs.next()) {
                    Map<String, Object> relationship = new HashMap<>();
                    
                    // 子表信息（有外键的表）
                    relationship.put("child_table", rs.getString("FKTABLE_NAME"));
                    relationship.put("child_column", rs.getString("FKCOLUMN_NAME"));
                    
                    // 父表信息（被引用的表）
                    relationship.put("parent_table", rs.getString("PKTABLE_NAME"));
                    relationship.put("parent_column", rs.getString("PKCOLUMN_NAME"));
                    
                    // 外键名称
                    relationship.put("fk_name", rs.getString("FK_NAME"));
                    
                    // 更新/删除规则
                    relationship.put("update_rule", getRuleDescription(rs.getShort("UPDATE_RULE")));
                    relationship.put("delete_rule", getRuleDescription(rs.getShort("DELETE_RULE")));

                    relationships.add(relationship);
                }
            }

        } catch (Exception e) {
            log.error("获取表关联关系失败", e);
            throw e;
        }

        return relationships;
    }

    /**
     * 获取规则描述
     */
    private String getRuleDescription(Short ruleCode) {
        if (ruleCode == null) {
            return "UNKNOWN";
        }
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
