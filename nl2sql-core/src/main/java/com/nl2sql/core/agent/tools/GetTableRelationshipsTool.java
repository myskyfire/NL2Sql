package com.nl2sql.core.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 表关联关系查询 Tool - 原子能力：查询已配置的表关联关系
 */
@Slf4j
@Component
public class GetTableRelationshipsTool {
    
    @Autowired
    private com.nl2sql.metadata.service.TableRelationshipService relationshipService;
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    @Tool("查询数据库中已配置的表关联关系。输入数据源ID和可选的表名，返回表之间的外键关联信息")
    public String getTableRelationships(Long datasourceId, String tableName) {
        try {
            log.info("[GetTableRelationshipsTool] 查询关联关系: datasourceId={}, table={}", datasourceId, tableName);
            
            List<com.nl2sql.metadata.entity.TableRelationship> relationships;
            
            if (tableName != null && !tableName.trim().isEmpty()) {
                // 查询指定表的关联
                relationships = relationshipService.getRelationshipsByTable(datasourceId, tableName);
            } else {
                // 查询所有关联
                relationships = relationshipService.getRelationships(datasourceId);
            }
            
            // 转换为Map格式
            List<Map<String, Object>> result = new ArrayList<>();
            for (com.nl2sql.metadata.entity.TableRelationship rel : relationships) {
                Map<String, Object> map = new HashMap<>();
                map.put("id", rel.getId());
                map.put("sourceTable", rel.getSourceTable());
                map.put("sourceColumn", rel.getSourceColumn());
                map.put("targetTable", rel.getTargetTable());
                map.put("targetColumn", rel.getTargetColumn());
                map.put("relationshipType", rel.getRelationshipType());
                map.put("confidence", rel.getConfidence());
                map.put("description", rel.getDescription());
                map.put("isActive", rel.getIsActive());
                result.add(map);
            }
            
            // ✅ 构建统一响应
            Map<String, Object> data = new HashMap<>();
            data.put("relationshipCount", result.size());
            data.put("relationships", result);
            
            return ToolResponseBuilder.success("data")
                .withData(data)
                .addMetadata("toolName", "get_table_relationships")
                .addMetadata("datasourceId", datasourceId)
                .build();
            
        } catch (Exception e) {
            log.error("[GetTableRelationshipsTool] 查询失败", e);
            return ToolResponseBuilder.error("RELATIONSHIP_QUERY_ERROR", e.getMessage())
                .addMetadata("toolName", "get_table_relationships")
                .addMetadata("datasourceId", datasourceId)
                .build();
        }
    }
}
