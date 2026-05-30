package com.nl2sql.core.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nl2sql.core.llm.extension.IndustryConceptExtension;
import com.nl2sql.core.service.NL2SQLService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Collection;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 生成 SQL Tool
 * 
 * 功能：基于用户问题和表结构信息生成 SQL 查询语句
 * 调用者：Agent/LLM
 */
@Slf4j
@Component
public class GenerateSQLTool {
    
    @Autowired
    private NL2SQLService nl2sqlService;
    
    @Autowired(required = false)
    private ApplicationContext applicationContext;
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    /**
     * 生成 SQL 查询语句
     * 
     * @param question 用户的自然语言问题
     * @param datasourceId 数据源ID
     * @return JSON格式：{"success": true, "sql": "..."} 或 {"success": false, "error": "..."}
     */
    @Tool(name = "generateSQL", value = "基于用户问题和数据源生成 SQL 查询语句")
    public String execute(
        @P("用户的自然语言问题，例如：查询上月订单总额") String question,
        @P("数据源ID") Long datasourceId,
        @P("预检索的表结构信息（可选，如果提供则跳过内部检索）") String schemaInfo,
        @P("用户指定的表名偏好（可选，如'使用XX表'）") String tableHint
    ) {
        try {
            log.info("[GenerateSQLTool] 开始生成SQL: question={}, datasourceId={}, hasSchemaInfo={}, hasTableHint={}",
                question, datasourceId, schemaInfo != null && !schemaInfo.isEmpty(),
                tableHint != null && !tableHint.isEmpty());

            if (question == null || question.trim().isEmpty()) {
                return buildErrorResponse("用户问题不能为空");
            }

            if (datasourceId == null) {
                return buildErrorResponse("数据源ID不能为空");
            }

            String enhancedQuestion = question;
            
            // ✅ 等效于 Groovy extractTablePreference：自动从问题中提取表名偏好
            String effectiveTableHint = tableHint;
            if ((effectiveTableHint == null || effectiveTableHint.trim().isEmpty()) && question != null) {
                effectiveTableHint = extractTablePreference(question);
                if (effectiveTableHint != null) {
                    log.info("[GenerateSQLTool] 自动检测到用户指定表: {}", effectiveTableHint);
                }
            }
            
            if (effectiveTableHint != null && !effectiveTableHint.trim().isEmpty()) {
                enhancedQuestion = question + " [优先使用表: " + effectiveTableHint.trim() + "]";
                log.info("[GenerateSQLTool] 应用表名偏好: {}", effectiveTableHint);
            }
            Collection<IndustryConceptExtension> conceptExtensions =
                applicationContext != null ?
                applicationContext.getBeansOfType(IndustryConceptExtension.class).values() :
                java.util.Collections.emptyList();

            if (!conceptExtensions.isEmpty()) {
                for (IndustryConceptExtension extension : conceptExtensions) {
                    try {
                        String enhancedPrompt = extension.enhancePromptBeforeGeneration(null, question, datasourceId);
                        if (enhancedPrompt != null && !enhancedPrompt.trim().isEmpty()) {
                            log.info("[GenerateSQLTool] 行业扩展点增强Prompt: {}", extension.getClass().getSimpleName());
                            enhancedQuestion = question + "\n\n" + enhancedPrompt;
                            break;
                        }
                    } catch (Exception e) {
                        log.warn("[GenerateSQLTool] 行业扩展点执行失败: {}, error: {}",
                            extension.getClass().getSimpleName(), e.getMessage());
                    }
                }
            }

            // ✅ Groovy逻辑：直接使用 NL2SQLService.generateSQL，内部会从 ThreadLocal 读取预检索表
            String sql = nl2sqlService.generateSQL(enhancedQuestion, datasourceId);
            
            // 检查是否生成失败
            if (sql == null || sql.trim().isEmpty()) {
                return buildErrorResponse("SQL生成失败：返回结果为空");
            }
            
            if (sql.startsWith("错误：") || sql.startsWith("ERROR:")) {
                return buildErrorResponse("SQL生成失败: " + sql);
            }
            
            if (sql.startsWith("CLARIFICATION_NEEDED:")) {
                return buildErrorResponse("需要澄清: " + sql.substring("CLARIFICATION_NEEDED:".length()).trim());
            }
            
            if (sql.startsWith("TABLE_SELECTION_NEEDED:")) {
                return buildErrorResponse("需要选择表: " + sql.substring("TABLE_SELECTION_NEEDED:".length()).trim());
            }
            
            log.info("[GenerateSQLTool] SQL生成成功: {}", sql);
            
            // 构建成功响应
            return buildSuccessResponse(sql);
            
        } catch (Exception e) {
            log.error("[GenerateSQLTool] SQL生成失败", e);
            return buildErrorResponse("SQL生成异常: " + e.getMessage());
        }
    }
    
    /**
     * 构建成功响应
     */
    private String buildSuccessResponse(String sql) {
        // ✅ 构建统一响应
        Map<String, Object> data = new HashMap<>();
        data.put("sql", sql);
        
        return ToolResponseBuilder.success("data")
            .withData(data)
            .addMetadata("toolName", "generate_sql")
            .build();
    }
    
    /**
     * 构建错误响应
     */
    private String buildErrorResponse(String error) {
        return ToolResponseBuilder.error("SQL_GENERATION_ERROR", error)
            .addMetadata("toolName", "generate_sql")
            .build();
    }
    
    /**
     * ✅ 等效于 Groovy extractTablePreference：从用户问题中提取表名偏好
     * 匹配模式："使用XX表"、"用XX表"、"从XX表"、"基于XX表"
     */
    private String extractTablePreference(String question) {
        if (question == null || question.trim().isEmpty()) {
            return null;
        }
        
        Pattern[] patterns = {
            Pattern.compile("使用(\\w+)表"),
            Pattern.compile("用(\\w+)表"),
            Pattern.compile("从(\\w+)表"),
            Pattern.compile("基于(\\w+)表")
        };
        
        for (Pattern pattern : patterns) {
            Matcher matcher = pattern.matcher(question);
            if (matcher.find()) {
                return matcher.group(1);
            }
        }
        
        return null;
    }
}
