package com.nl2sql.core.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nl2sql.core.service.NL2SQLService;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 生成SQL Tool - 原子能力：基于表结构和用户问题生成SQL
 */
@Slf4j
@Component
public class GenerateSQLFromSchemaTool {
    
    @Autowired
    private NL2SQLService nl2sqlService;
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    /**
     * 基于表结构生成SQL
     * 
     * @param question 用户问题
     * @param schema 表结构信息（JSON字符串）
     * @param datasourceId 数据源ID
     * @return JSON格式的SQL语句
     */
    @Tool("基于表结构和用户问题生成SQL语句。输入问题、表结构信息和数据源ID，返回生成的SQL")
    public String generateSQLFromSchema(String question, String schema, Long datasourceId) {
        try {
            log.info("[GenerateSQLFromSchemaTool] 生成SQL: question={}, datasourceId={}, schema长度={}", 
                question, datasourceId, schema != null ? schema.length() : 0);
            
            if (question == null || question.trim().isEmpty()) {
                return buildErrorResponse("用户问题不能为空");
            }
            
            if (schema == null || schema.trim().isEmpty()) {
                return buildErrorResponse("表结构信息不能为空");
            }
            
            if (datasourceId == null) {
                return buildErrorResponse("数据源ID不能为空");
            }
            
            // ✅ 直接使用传入的 schema 调用 NL2SQLService
            String sql = nl2sqlService.generateSQLWithSchema(question, schema, datasourceId);
            
            // 检查是否需要澄清
            if (sql != null && (sql.startsWith("CLARIFY_") || sql.startsWith("CLARIFICATION"))) {
                return buildClarificationResponse(sql);
            }
            
            // 检查是否生成失败
            if (sql == null || sql.trim().isEmpty() || sql.startsWith("错误：") || sql.startsWith("ERROR:")) {
                return buildErrorResponse(sql != null ? sql : "SQL生成失败");
            }
            
            log.info("[GenerateSQLFromSchemaTool] 生成成功: {}", sql);
            
            return buildSuccessResponse(sql, datasourceId);
            
        } catch (Exception e) {
            log.error("[GenerateSQLFromSchemaTool] 生成失败", e);
            return buildErrorResponse(e.getMessage());
        }
    }
    
    private String buildSuccessResponse(String sql, Long datasourceId) {
        try {
            // ✅ 构建统一响应
            Map<String, Object> data = new HashMap<>();
            data.put("sql", sql);
            
            return ToolResponseBuilder.success("data")
                .withData(data)
                .addMetadata("toolName", "generate_sql_from_schema")
                .addMetadata("datasourceId", datasourceId)
                .build();
        } catch (Exception e) {
            log.error("[GenerateSQLFromSchemaTool] JSON序列化失败", e);
            return ToolResponseBuilder.error("SERIALIZATION_ERROR", "JSON序列化失败")
                .addMetadata("toolName", "generate_sql_from_schema")
                .build();
        }
    }
    
    private String buildErrorResponse(String error) {
        return ToolResponseBuilder.error("SQL_GENERATION_ERROR", error)
            .addMetadata("toolName", "generate_sql_from_schema")
            .build();
    }
    
    private String buildClarificationResponse(String message) {
        // ✅ 澄清响应使用clarification类型
        return ToolResponseBuilder.clarification("schema_clarification")
            .withMessage(message)
            .addMetadata("toolName", "generate_sql_from_schema")
            .build();
    }
}
