package com.nl2sql.core.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
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
    private NL2SQLTool nl2sqlTool;
    
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
            log.info("[GenerateSQLFromSchemaTool] 生成SQL: question={}, datasourceId={}", question, datasourceId);
            
            // 调用 NL2SQLTool 的 generateSQL 方法
            String sql = nl2sqlTool.generateSQL(question, datasourceId);
            
            // 检查是否需要澄清
            if (sql != null && (sql.startsWith("CLARIFY_") || sql.startsWith("CLARIFICATION"))) {
                Map<String, Object> clarification = new HashMap<>();
                clarification.put("success", false);
                clarification.put("needsClarification", true);
                clarification.put("message", sql);
                return objectMapper.writeValueAsString(clarification);
            }
            
            // 检查是否生成失败
            if (sql == null || sql.trim().isEmpty() || sql.startsWith("错误：") || sql.startsWith("ERROR:")) {
                Map<String, Object> error = new HashMap<>();
                error.put("success", false);
                error.put("error", sql != null ? sql : "SQL生成失败");
                return objectMapper.writeValueAsString(error);
            }
            
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("sql", sql);
            result.put("datasourceId", datasourceId);
            
            log.info("[GenerateSQLFromSchemaTool] 生成成功: {}", sql);
            
            return objectMapper.writeValueAsString(result);
            
        } catch (Exception e) {
            log.error("[GenerateSQLFromSchemaTool] 生成失败", e);
            
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
