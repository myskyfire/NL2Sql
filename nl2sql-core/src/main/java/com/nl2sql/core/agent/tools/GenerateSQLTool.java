package com.nl2sql.core.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nl2sql.core.service.NL2SQLService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

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
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    /**
     * 生成 SQL 查询语句
     * 
     * @param question 用户的自然语言问题
     * @param datasourceId 数据源ID
     * @return JSON格式：{"success": true, "sql": "..."} 或 {"success": false, "error": "..."}
     */
    @Tool("基于用户问题和数据源生成 SQL 查询语句（内部会自动检索表结构）")
    public String execute(
        @P("用户的自然语言问题，例如：查询上月订单总额") String question,
        @P("数据源ID") Long datasourceId
    ) {
        try {
            log.info("[GenerateSQLTool] 开始生成SQL: question={}, datasourceId={}", question, datasourceId);
            
            if (question == null || question.trim().isEmpty()) {
                return buildErrorResponse("用户问题不能为空");
            }
            
            if (datasourceId == null) {
                return buildErrorResponse("数据源ID不能为空");
            }
            
            // 调用 NL2SQLService 生成 SQL（内部会自动检索表结构）
            String sql = nl2sqlService.generateSQL(question, datasourceId);
            
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
        try {
            return objectMapper.writeValueAsString(java.util.Map.of(
                "success", true,
                "sql", sql
            ));
        } catch (Exception e) {
            log.error("[GenerateSQLTool] JSON序列化失败", e);
            return "{\"success\":false,\"error\":\"JSON序列化失败\"}";
        }
    }
    
    /**
     * 构建错误响应
     */
    private String buildErrorResponse(String error) {
        try {
            return objectMapper.writeValueAsString(java.util.Map.of(
                "success", false,
                "error", error
            ));
        } catch (Exception e) {
            log.error("[GenerateSQLTool] JSON序列化失败", e);
            return "{\"success\":false,\"error\":\"JSON序列化失败\"}";
        }
    }
}
