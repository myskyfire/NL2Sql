package com.nl2sql.core.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nl2sql.core.service.SchemaRetrievalService;
import com.nl2sql.core.service.TableSelectionOrchestrator;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 检索表结构 Tool
 * 
 * 功能：根据用户问题检索相关的数据库表结构信息
 * 调用者：Agent/LLM
 */
@Slf4j
@Component
public class RetrieveTableSchemaTool {
    
    @Autowired
    private SchemaRetrievalService schemaRetrievalService;
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    /**
     * 检索相关表结构
     * 
     * @param question 用户的自然语言问题
     * @param datasourceId 数据源ID
     * @return JSON格式：{"success": true, "schema": "..."} 或 {"success": false, "error": "..."}
     */
    @Tool(name = "retrieveSchema", value = "根据用户问题检索相关的数据库表结构信息，返回表的字段、类型、注释等元数据")
    public String execute(
        @P("用户的自然语言问题，例如：查询上月订单总额") String question,
        @P("数据源ID") Long datasourceId
    ) {
        try {
            log.info("[RetrieveTableSchemaTool] 开始检索表结构: question={}, datasourceId={}", question, datasourceId);
            
            if (question == null || question.trim().isEmpty()) {
                return buildErrorResponse("用户问题不能为空");
            }
            
            if (datasourceId == null) {
                return buildErrorResponse("数据源ID不能为空");
            }
            
            // 调用 SchemaRetrievalService 检索表结构
            String schema = schemaRetrievalService.retrieveSchema(question, datasourceId);
            
            if (schema == null || schema.trim().isEmpty()) {
                return buildErrorResponse("未找到相关的表结构信息");
            }
            
            log.info("[RetrieveTableSchemaTool] 表结构检索成功，长度: {} 字符", schema.length());

            List<String> tableNames = extractTableNamesFromSchema(schema);
            if (!tableNames.isEmpty()) {
                TableSelectionOrchestrator.setPreRetrievedTables(tableNames);
                log.info("[RetrieveTableSchemaTool] 已设置预检索表列表到ThreadLocal: {}", tableNames);
            }
            
            // 构建成功响应
            return buildSuccessResponse(schema);
            
        } catch (Exception e) {
            log.error("[RetrieveTableSchemaTool] 表结构检索失败", e);
            return buildErrorResponse("表结构检索失败: " + e.getMessage());
        }
    }
    
    /**
     * 构建成功响应
     */
    private String buildSuccessResponse(String schema) {
        // ✅ 构建统一响应
        Map<String, Object> data = new HashMap<>();
        data.put("schema", schema);
        
        return ToolResponseBuilder.success("data")
            .withData(data)
            .addMetadata("toolName", "retrieve_table_schema")
            .build();
    }
    
    /**
     * 构建错误响应
     */
    private String buildErrorResponse(String error) {
        return ToolResponseBuilder.error("SCHEMA_RETRIEVAL_ERROR", error)
            .addMetadata("toolName", "retrieve_table_schema")
            .build();
    }

    private List<String> extractTableNamesFromSchema(String schema) {
        List<String> tables = new ArrayList<>();
        if (schema == null || schema.isEmpty()) {
            return tables;
        }

        Pattern pattern = Pattern.compile("\\n表名:\\s*(\\w+)");
        Matcher matcher = pattern.matcher(schema);
        while (matcher.find()) {
            tables.add(matcher.group(1).toLowerCase());
        }

        return tables;
    }
}
