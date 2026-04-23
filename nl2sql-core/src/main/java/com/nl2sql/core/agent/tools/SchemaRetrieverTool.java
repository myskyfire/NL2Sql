package com.nl2sql.core.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nl2sql.core.agent.tool.BaseToolAdapter;
import com.nl2sql.core.agent.tool.ToolContext;
import com.nl2sql.core.cache.MetadataCacheService;
import com.nl2sql.core.retriever.VectorRetriever;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Schema检索工具 - 根据用户问题检索相关表结构信息
 * ✅ 从NL2SQLTool拆分出的原子能力
 */
@Slf4j
@Component
public class SchemaRetrieverTool extends BaseToolAdapter {
    
    @Autowired
    private JdbcTemplate jdbcTemplate;
    
    @Autowired
    private VectorRetriever vectorRetriever;
    
    @Autowired(required = false)
    private MetadataCacheService metadataCacheService;
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    @Override
    public String getName() { return "schema_retriever"; }
    
    @Override
    public String getDescription() { return "根据用户自然语言问题检索相关的数据库表结构信息，包括表名、字段、注释等。"; }
    
    @Override
    public Map<String, Object> getParameterSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        Map<String, Object> props = new HashMap<>();
        props.put("query", Map.of("type", "string", "description", "用户自然语言问题"));
        props.put("datasourceId", Map.of("type", "integer", "description", "数据源ID"));
        schema.put("properties", props);
        schema.put("required", Arrays.asList("query", "datasourceId"));
        return schema;
    }
    
    @Override
    public String getApplicableScenarios() { return "需要获取与用户问题相关的表结构信息时"; }
    
    @Override
    public String getInapplicableScenarios() { return "已知具体表名不需要检索的场景"; }
    
    @Override
    protected void validateParameters(ToolContext context) {
        parameterValidator
            .required("query", context.getParameter("query"))
            .required("datasourceId", context.getParameter("datasourceId"))
            .throwIfHasErrors();
    }
    
    @Override
    protected Object doExecute(ToolContext context) throws Exception {
        String query = context.getRequiredParameter("query");
        Long datasourceId = context.getRequiredParameter("datasourceId");
        
        // 1. 尝试L2模糊向量缓存（高分反馈注入的"黄金表关系"）
        List<String> cachedTables = null;
        
        if (metadataCacheService != null) {
            String normalizedQuery = normalizeQueryForCache(query);
            cachedTables = metadataCacheService.getFuzzyVectorRetrieval(normalizedQuery);
            
            if (cachedTables != null && !cachedTables.isEmpty()) {
                log.info("[SchemaRetriever] ⚡ L2缓存命中(高分反馈): query='{}', tables={}", query, cachedTables);
                return buildTableSchemaInfo(cachedTables, datasourceId);
            }
            
            // 2. 尝试L3语义索引（Jaccard相似度）
            cachedTables = metadataCacheService.findSimilarQueryBySemantic(query, datasourceId, 0.85);
            
            if (cachedTables != null && !cachedTables.isEmpty()) {
                log.info("[SchemaRetriever] ⚡ L3语义索引命中: query='{}', tables={}", query, cachedTables);
                return buildTableSchemaInfo(cachedTables, datasourceId);
            }
        }
        
        // 3. 缓存未命中，走正常流程：向量检索
        log.debug("[SchemaRetriever] 缓存未命中，执行向量检索: query={}", query);
        List<String> tables = vectorRetriever.retrieveTopTables(query, datasourceId, 10);
        
        if (tables.isEmpty()) {
            return "ERROR: 未找到任何相关表";
        }
        
        // 构建schema信息
        return buildTableSchemaInfo(tables, datasourceId);
    }
    
    /**
     * 构建表结构信息
     */
    private String buildTableSchemaInfo(List<String> tables, Long datasourceId) {
        StringBuilder schemaInfo = new StringBuilder();
        
        for (String tableName : tables) {
            try {
                // 查询表注释
                String tableComment = jdbcTemplate.queryForObject(
                    "SELECT DISTINCT table_comment FROM column_metadata WHERE datasource_id = ? AND table_name = ? LIMIT 1",
                    String.class, datasourceId, tableName
                );
                
                schemaInfo.append(String.format("\n### 表: %s", tableName));
                if (tableComment != null && !tableComment.isEmpty()) {
                    schemaInfo.append(String.format(" (%s)", tableComment));
                }
                schemaInfo.append("\n");
                
                // 查询字段信息
                List<Map<String, Object>> columns = jdbcTemplate.queryForList(
                    "SELECT column_name, data_type, column_comment, is_nullable, column_key FROM column_metadata " +
                    "WHERE datasource_id = ? AND table_name = ? ORDER BY ordinal_position",
                    datasourceId, tableName
                );
                
                if (!columns.isEmpty()) {
                    schemaInfo.append("字段:\n");
                    for (Map<String, Object> col : columns) {
                        String colName = (String) col.get("column_name");
                        String dataType = (String) col.get("data_type");
                        String comment = (String) col.get("column_comment");
                        String isNullable = (String) col.get("is_nullable");
                        String columnKey = (String) col.get("column_key");
                        
                        schemaInfo.append(String.format("  - %s (%s)", colName, dataType));
                        if ("PRI".equals(columnKey)) {
                            schemaInfo.append(" [主键]");
                        }
                        if ("NO".equalsIgnoreCase(isNullable)) {
                            schemaInfo.append(" [非空]");
                        }
                        if (comment != null && !comment.isEmpty()) {
                            schemaInfo.append(String.format(": %s", comment));
                        }
                        schemaInfo.append("\n");
                    }
                }
            } catch (Exception e) {
                log.warn("[SchemaRetriever] 获取表{}信息失败: {}", tableName, e.getMessage());
            }
        }
        
        return schemaInfo.toString();
    }
    
    /**
     * 归一化查询文本
     */
    private String normalizeQueryForCache(String query) {
        if (query == null) return "";
        
        String normalized = query.replaceAll("\\d+", "<NUM>");
        normalized = normalized.replaceAll("\\d{4}[-/]\\d{1,2}[-/]\\d{1,2}", "<DATE>");
        normalized = normalized.replaceAll("最近\\d+天", "最近<NUM>天");
        normalized = normalized.replaceAll("过去\\d+天", "过去<NUM>天");
        normalized = normalized.trim().replaceAll("\\s+", " ");
        
        return normalized;
    }
    
    @Tool("根据用户自然语言问题和数据源ID，检索相关的数据库表结构信息。返回表名、字段、数据类型、注释等信息")
    public String retrieveSchema(String query, Long datasourceId) {
        try {
            ToolContext context = ToolContext.builder()
                .parameters(new HashMap<String, Object>() {{
                    put("query", query);
                    put("datasourceId", datasourceId);
                }})
                .build();
            
            com.nl2sql.core.agent.tool.ToolResult result = execute(context);
            
            if (result.isSuccess()) {
                return result.getData() != null ? result.getData().toString() : "";
            } else {
                return "ERROR: " + result.getErrorMessage();
            }
        } catch (Exception e) {
            log.error("[SchemaRetriever] 执行失败", e);
            return "ERROR: " + e.getMessage();
        }
    }
}
