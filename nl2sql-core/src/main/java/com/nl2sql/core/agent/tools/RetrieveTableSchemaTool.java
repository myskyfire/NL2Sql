package com.nl2sql.core.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 检索表结构 Tool - 原子能力：根据问题检索相关的表结构信息
 */
@Slf4j
@Component
public class RetrieveTableSchemaTool {
    
    @Autowired
    private NL2SQLTool nl2sqlTool;
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    /**
     * 检索相关表的结构信息
     * 
     * @param question 用户问题
     * @param datasourceId 数据源ID
     * @return JSON格式的表结构信息
     */
    @Tool("根据用户问题检索相关的表结构信息。输入问题和数据源ID，返回匹配的表名、字段列表和注释")
    public String retrieveTableSchema(String question, Long datasourceId) {
        try {
            log.info("[RetrieveTableSchemaTool] 检索表结构: question={}, datasourceId={}", question, datasourceId);
            
            // 调用 NL2SQLTool 的 retrieveSchema 方法
            String schema = nl2sqlTool.retrieveSchema(question, datasourceId);
            
            // 解析并返回结构化结果
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("schema", schema);
            result.put("datasourceId", datasourceId);
            
            log.info("[RetrieveTableSchemaTool] 检索成功");
            
            return objectMapper.writeValueAsString(result);
            
        } catch (Exception e) {
            log.error("[RetrieveTableSchemaTool] 检索失败", e);
            
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
