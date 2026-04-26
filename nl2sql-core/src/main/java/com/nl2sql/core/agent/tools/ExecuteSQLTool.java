package com.nl2sql.core.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nl2sql.core.agent.tool.BaseTool;
import com.nl2sql.core.agent.tool.ToolContext;
import com.nl2sql.core.agent.tool.ToolResult;
import com.nl2sql.core.error.ErrorClassifier;
import com.nl2sql.core.validation.ParameterValidator;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * SQL 执行 Tool - 原子能力：执行SQL查询并返回结果
 * 
 * ✅ 已迁移到新框架：BaseTool接口 + 参数校验 + 错误处理
 */
@Slf4j
@Component
public class ExecuteSQLTool implements BaseTool {
    
    @Autowired
    private JdbcTemplate jdbcTemplate;
    
    @Autowired
    private ParameterValidator parameterValidator;
    
    @Autowired
    private ErrorClassifier errorClassifier;
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    // ==================== BaseTool接口实现 ====================
    
    @Override
    public String getName() {
        return "execute_sql";
    }
    
    @Override
    public String getDescription() {
        return "执行SQL查询并返回结果。仅支持SELECT语句，不支持增删改操作。";
    }
    
    @Override
    public Map<String, Object> getParameterSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        
        Map<String, Object> properties = new HashMap<>();
        
        // sql 参数
        Map<String, Object> sqlParam = new HashMap<>();
        sqlParam.put("type", "string");
        sqlParam.put("description", "要执行的SQL查询语句，必须是SELECT语句");
        properties.put("sql", sqlParam);
        
        // datasourceId 参数
        Map<String, Object> datasourceParam = new HashMap<>();
        datasourceParam.put("type", "integer");
        datasourceParam.put("description", "数据源ID");
        properties.put("datasourceId", datasourceParam);
        
        schema.put("properties", properties);
        schema.put("required", Arrays.asList("sql", "datasourceId"));
        
        return schema;
    }
    
    @Override
    public String getApplicableScenarios() {
        return "适用于以下场景：\n" +
               "1. 执行SELECT查询获取数据\n" +
               "2. 验证生成的SQL是否正确\n" +
               "3. 预览表数据";
    }
    
    @Override
    public String getInapplicableScenarios() {
        return "不适用于以下场景：\n" +
               "1. INSERT/UPDATE/DELETE等写操作\n" +
               "2. DDL操作（CREATE/DROP/ALTER）\n" +
               "3. 系统管理命令";
    }
    
    @Override
    public ToolResult execute(ToolContext context) {
        long startTime = System.currentTimeMillis();
        
        try {
            // 1. 参数校验
            validateParameters(context);
            
            String sql = context.getRequiredParameter("sql");
            Long datasourceId = context.getRequiredParameter("datasourceId");
            
            log.info("[ExecuteSQLTool] 执行SQL: datasourceId={}, sql={}", datasourceId, sql);
            
            // 2. 安全检查：只允许SELECT语句
            String upperSQL = sql.trim().toUpperCase();
            if (!upperSQL.startsWith("SELECT")) {
                return ToolResult.error("只允许执行SELECT查询");
            }
            
            // 3. 执行查询
            List<Map<String, Object>> results = jdbcTemplate.queryForList(sql);
            
            // 4. 构建响应
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("rowCount", results.size());
            response.put("data", results);
            
            if (!results.isEmpty()) {
                response.put("columns", new ArrayList<>(results.get(0).keySet()));
            }
            
            // 5. 添加元数据
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("executionTimeMs", System.currentTimeMillis() - startTime);
            metadata.put("rowCount", results.size());
            
            log.info("[ExecuteSQLTool] 查询成功，返回 {} 行数据", results.size());
            
            return ToolResult.success(response, metadata);
            
        } catch (IllegalArgumentException e) {
            // 参数校验错误
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("executionTimeMs", System.currentTimeMillis() - startTime);
            return ToolResult.error(e.getMessage(), metadata);
            
        } catch (Exception e) {
            // 其他异常，使用错误分类器
            log.error("[ExecuteSQLTool] 执行失败", e);
            
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("executionTimeMs", System.currentTimeMillis() - startTime);
            metadata.put("errorType", errorClassifier.classify(e).name());
            
            return ToolResult.error("SQL执行失败: " + e.getMessage(), metadata);
        }
    }
    
    /**
     * 参数校验
     */
    private void validateParameters(ToolContext context) {
        String sql = context.getParameter("sql");
        Long datasourceId = context.getParameter("datasourceId");
        
        parameterValidator
            .required("sql", sql)
            .required("datasourceId", datasourceId)
            .maxLength("sql", sql, 10000)
            .throwIfHasErrors();
    }
    
    // ==================== 保留旧方法以兼容LangChain4j @Tool注解 ====================
    
    /**
     * 执行SQL查询（保留旧方法以兼容LangChain4j @Tool注解）
     * 
     * @param sql SQL语句
     * @param datasourceId 数据源ID
     * @return JSON格式的查询结果
     */
    @Tool("执行SQL查询并返回结果。输入SQL语句和数据源ID，返回查询结果的JSON格式数据")
    public String executeSQL(String sql, Long datasourceId) {
        long startTime = System.currentTimeMillis();
        
        try {
            log.info("[ExecuteSQLTool] 执行SQL: {}", sql);
            
            // 安全检查：只允许SELECT语句
            String upperSQL = sql.trim().toUpperCase();
            if (!upperSQL.startsWith("SELECT")) {
                return ToolResponseBuilder.error("SQL_SECURITY_ERROR", "只允许执行SELECT查询")
                    .addMetadata("toolName", "execute_sql")
                    .build();
            }
            
            // 执行查询
            List<Map<String, Object>> results = jdbcTemplate.queryForList(sql);
            long executionTime = System.currentTimeMillis() - startTime;
            
            // ✅ 构建统一响应
            Map<String, Object> data = new HashMap<>();
            data.put("rows", results);
            data.put("rowCount", results.size());
            if (!results.isEmpty()) {
                data.put("columns", new ArrayList<>(results.get(0).keySet()));
            }
            
            log.info("[ExecuteSQLTool] 查询成功，返回 {} 行数据", results.size());
            
            return ToolResponseBuilder.success("data")
                .withData(data)
                .addMetadata("toolName", "execute_sql")
                .addMetadata("datasourceId", datasourceId)
                .addMetadata("executionTimeMs", executionTime)
                .build();
            
        } catch (Exception e) {
            long executionTime = System.currentTimeMillis() - startTime;
            log.error("[ExecuteSQLTool] 执行失败", e);
            
            return ToolResponseBuilder.error("SQL_EXECUTION_ERROR", e.getMessage())
                .addMetadata("toolName", "execute_sql")
                .addMetadata("datasourceId", datasourceId)
                .addMetadata("executionTimeMs", executionTime)
                .build();
        }
    }
}
