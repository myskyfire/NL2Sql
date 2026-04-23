package com.nl2sql.core.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nl2sql.common.util.MarkdownUtils;
import com.nl2sql.core.agent.tool.BaseToolAdapter;
import com.nl2sql.core.agent.tool.ToolContext;
import com.nl2sql.core.llm.ModelRouterService;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * SQL自动修正工具 - 根据错误信息自动修正SQL语句
 * ✅ 从NL2SQLTool拆分出的原子能力
 */
@Slf4j
@Component
public class SQLAutoFixTool extends BaseToolAdapter {
    
    @Autowired
    private ModelRouterService modelRouter;
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    @Override
    public String getName() { return "sql_auto_fix"; }
    
    @Override
    public String getDescription() { return "根据SQL执行错误信息自动修正SQL语句，支持语法错误、列名幻觉、JOIN条件错误等常见问题。"; }
    
    @Override
    public Map<String, Object> getParameterSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        Map<String, Object> props = new HashMap<>();
        props.put("failedSql", Map.of("type", "string", "description", "执行失败的SQL语句"));
        props.put("errorMessage", Map.of("type", "string", "description", "错误信息"));
        schema.put("properties", props);
        schema.put("required", Arrays.asList("failedSql", "errorMessage"));
        return schema;
    }
    
    @Override
    public String getApplicableScenarios() { return "SQL执行失败需要自动修正时"; }
    
    @Override
    public String getInapplicableScenarios() { return "SQL执行成功或错误无法自动修正的场景"; }
    
    @Override
    protected void validateParameters(ToolContext context) {
        parameterValidator
            .required("failedSql", context.getParameter("failedSql"))
            .required("errorMessage", context.getParameter("errorMessage"))
            .throwIfHasErrors();
    }
    
    @Override
    protected Object doExecute(ToolContext context) throws Exception {
        String failedSql = context.getRequiredParameter("failedSql");
        String errorMessage = context.getRequiredParameter("errorMessage");
        
        log.info("[SQLAutoFix] 开始修正SQL: error={}", errorMessage);
        
        String fixPrompt = String.format(
            "SQL执行失败，请修正。\n\n" +
            "失败的SQL:\n%s\n\n" +
            "错误信息:\n%s\n\n" +
            "要求：\n" +
            "1. 只输出修正后的SQL语句\n" +
            "2. 不要包含```sql或其他标记\n" +
            "3. 保持原有查询意图不变\n" +
            "4. **重要：如果ON条件中使用了IN子查询，必须改为直接JOIN**\n" +
            "   - 错误：JOIN tableB ON colA IN (SELECT id FROM tableB WHERE ...)\n" +
            "   - 正确：JOIN tableB ON tableA.ref_id = tableB.id",
            failedSql, errorMessage
        );
        
        String fixedSql = modelRouter.smartGenerateSQL(fixPrompt, "");
        String cleanedSql = MarkdownUtils.cleanSQL(fixedSql);
        
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("originalSql", failedSql);
        result.put("fixedSql", cleanedSql);
        result.put("error", errorMessage);
        
        log.info("[SQLAutoFix] 修正完成");
        return result;
    }
    
    @Tool("根据SQL执行错误信息自动修正SQL语句。输入失败的SQL和错误信息，返回修正后的SQL")
    public String autoFixSQL(String failedSql, String errorMessage) {
        try {
            ToolContext context = ToolContext.builder()
                .parameters(new HashMap<String, Object>() {{
                    put("failedSql", failedSql);
                    put("errorMessage", errorMessage);
                }})
                .build();
            
            com.nl2sql.core.agent.tool.ToolResult result = execute(context);
            
            if (result.isSuccess()) {
                Map<String, Object> data = (Map<String, Object>) result.getData();
                return objectMapper.writeValueAsString(data);
            } else {
                Map<String, Object> error = new HashMap<>();
                error.put("success", false);
                error.put("error", result.getErrorMessage());
                return objectMapper.writeValueAsString(error);
            }
        } catch (Exception e) {
            log.error("[SQLAutoFix] 执行失败", e);
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("error", e.getMessage());
            try {
                return objectMapper.writeValueAsString(error);
            } catch (Exception ex) {
                return "{\"success\":false,\"error\":\"序列化失败\"}";
            }
        }
    }
}
