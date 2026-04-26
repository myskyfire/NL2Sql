package com.nl2sql.core.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nl2sql.core.agent.tool.BaseToolAdapter;
import com.nl2sql.core.agent.tool.ToolContext;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 获取表元数据 Tool - 原子能力：查询表结构和字段信息
 * 
 * ✅ 已迁移到新框架：继承BaseToolAdapter
 */
@Slf4j
@Component
public class GetTableMetadataTool extends BaseToolAdapter {
    
    @Autowired
    private JdbcTemplate jdbcTemplate;
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    // ==================== BaseTool接口实现 ====================
    
    @Override
    public String getName() {
        return "get_table_metadata";
    }
    
    @Override
    public String getDescription() {
        return "获取指定表的元数据信息，包括表注释、字段列表、数据类型等。";
    }
    
    @Override
    public Map<String, Object> getParameterSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        
        Map<String, Object> properties = new HashMap<>();
        
        Map<String, Object> tableNameParam = new HashMap<>();
        tableNameParam.put("type", "string");
        tableNameParam.put("description", "表名");
        properties.put("tableName", tableNameParam);
        
        Map<String, Object> datasourceParam = new HashMap<>();
        datasourceParam.put("type", "integer");
        datasourceParam.put("description", "数据源ID");
        properties.put("datasourceId", datasourceParam);
        
        schema.put("properties", properties);
        schema.put("required", Arrays.asList("tableName", "datasourceId"));
        
        return schema;
    }
    
    @Override
    public String getApplicableScenarios() {
        return "适用于以下场景：\n" +
               "1. 查看表结构\n" +
               "2. 了解字段类型和注释\n" +
               "3. 确认主键字段";
    }
    
    @Override
    public String getInapplicableScenarios() {
        return "不适用于查询表数据内容";
    }
    
    @Override
    protected void validateParameters(ToolContext context) {
        String tableName = context.getParameter("tableName");
        Long datasourceId = context.getParameter("datasourceId");
        
        parameterValidator
            .required("tableName", tableName)
            .required("datasourceId", datasourceId)
            .throwIfHasErrors();
    }
    
    @Override
    protected Object doExecute(ToolContext context) throws Exception {
        String tableName = context.getRequiredParameter("tableName");
        Long datasourceId = context.getRequiredParameter("datasourceId");
        
        log.info("[GetTableMetadataTool] 获取表元数据: {}", tableName);
        
        // 获取表注释
        String tableComment = jdbcTemplate.queryForObject(
            "SELECT DISTINCT table_comment FROM column_metadata WHERE datasource_id = ? AND table_name = ? LIMIT 1",
            String.class, datasourceId, tableName
        );
        
        // 获取字段列表
        List<Map<String, Object>> columns = jdbcTemplate.queryForList(
            "SELECT column_name, data_type, column_comment, is_primary_key, ordinal_position " +
            "FROM column_metadata WHERE datasource_id = ? AND table_name = ? " +
            "ORDER BY ordinal_position",
            datasourceId, tableName
        );
        
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("表不存在或没有字段信息");
        }
        
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("tableName", tableName);
        response.put("tableComment", tableComment != null ? tableComment : "");
        response.put("columnCount", columns.size());
        response.put("columns", columns);
        
        log.info("[GetTableMetadataTool] 获取成功，共 {} 个字段", columns.size());
        
        return response;
    }
    
    /**
     * 获取表的元数据信息（LangChain4j @Tool入口）
     * 
     * @param tableName 表名
     * @param datasourceId 数据源ID
     * @return JSON格式的表结构信息
     */
    @Tool("获取指定表的元数据信息，包括表注释、字段列表、数据类型等。输入表名和数据源ID")
    public String getTableMetadata(String tableName, Long datasourceId) {
        try {
            // 构建ToolContext
            ToolContext context = ToolContext.builder()
                .parameters(new HashMap<String, Object>() {{
                    put("tableName", tableName);
                    put("datasourceId", datasourceId);
                }})
                .build();
            
            // 调用新框架执行
            com.nl2sql.core.agent.tool.ToolResult result = execute(context);
            
            // ✅ 序列化返回统一格式
            if (result.isSuccess()) {
                Map<String, Object> data = (Map<String, Object>) result.getData();
                return ToolResponseBuilder.success("data")
                    .withData(data)
                    .addMetadata("toolName", "get_table_metadata")
                    .addMetadata("datasourceId", datasourceId)
                    .build();
            } else {
                return ToolResponseBuilder.error("METADATA_ERROR", result.getErrorMessage())
                    .addMetadata("toolName", "get_table_metadata")
                    .addMetadata("datasourceId", datasourceId)
                    .build();
            }
            
        } catch (Exception e) {
            log.error("[GetTableMetadataTool] 序列化失败", e);
            return ToolResponseBuilder.error("SERIALIZATION_ERROR", "序列化失败")
                .addMetadata("toolName", "get_table_metadata")
                .addMetadata("datasourceId", datasourceId)
                .build();
        }
    }
}
