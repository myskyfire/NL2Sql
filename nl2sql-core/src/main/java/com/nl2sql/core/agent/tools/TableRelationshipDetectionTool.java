package com.nl2sql.core.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nl2sql.metadata.service.TableRelationshipService;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 表关联关系推断 Tool - LLM可主动调用以分析表之间的关联关系
 */
@Slf4j
@Component
public class TableRelationshipDetectionTool {
    
    @Autowired
    private TableRelationshipService relationshipService;
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    /**
     * 智能推断表关联关系（规则引擎 + LLM）
     * 
     * @param datasourceId 数据源ID
     * @return JSON格式的关联关系列表，包含冲突信息
     */
    @Tool("自动推断数据库表之间的关联关系。结合规则引擎和LLM语义分析，返回可能的表关联。输入数据源ID")
    public String detectTableRelationships(Long datasourceId) {
        try {
            log.info("[TableRelationshipDetectionTool] 开始推断关联关系: datasourceId={}", datasourceId);
            
            List<Map<String, Object>> relationships = relationshipService.smartDetectRelationships(datasourceId);
            
            // 检查结果是否包含冲突
            if (!relationships.isEmpty() && relationships.get(0) instanceof Map) {
                Map<String, Object> firstItem = relationships.get(0);
                if (firstItem.containsKey("_hasConflicts")) {
                    // 有冲突的情况
                    log.warn("[TableRelationshipDetectionTool] 检测到 {} 个冲突", 
                        ((Map<?, ?>) firstItem.get("data")).get("conflictCount"));
                    
                    // ✅ 构建统一响应
                    return ToolResponseBuilder.clarification("relationship_conflict")
                        .withMessage("发现关联关系冲突，需要用户手动确认")
                        .withData(firstItem.get("data"))
                        .addMetadata("toolName", "table_relationship_detection")
                        .addMetadata("datasourceId", datasourceId)
                        .build();
                }
            }
            
            // 无冲突的正常结果
            Map<String, Object> data = new HashMap<>();
            data.put("relationships", relationships);
            data.put("count", relationships.size());
            
            log.info("[TableRelationshipDetectionTool] 推断完成，共 {} 条关联", relationships.size());
            
            return ToolResponseBuilder.success("data")
                .withData(data)
                .withMessage(String.format("成功推断 %d 条关联关系", relationships.size()))
                .addMetadata("toolName", "table_relationship_detection")
                .addMetadata("datasourceId", datasourceId)
                .build();
            
        } catch (Exception e) {
            log.error("[TableRelationshipDetectionTool] 推断失败", e);
            
            return ToolResponseBuilder.error("DETECTION_ERROR", e.getMessage())
                .withMessage("关联关系推断失败")
                .addMetadata("toolName", "table_relationship_detection")
                .addMetadata("datasourceId", datasourceId)
                .build();
        }
    }
    
    /**
     * 快速推断表关联关系（仅规则引擎，速度快）
     * 
     * @param datasourceId 数据源ID
     * @return JSON格式的关联关系列表
     */
    @Tool("快速推断数据库表之间的关联关系（仅使用规则引擎，速度快但可能遗漏复杂关联）。输入数据源ID")
    public String quickDetectTableRelationships(Long datasourceId) {
        try {
            log.info("[TableRelationshipDetectionTool] 快速推断关联关系: datasourceId={}", datasourceId);
            
            List<Map<String, Object>> relationships = relationshipService.autoDetectRelationships(datasourceId);
            
            // ✅ 构建统一响应
            Map<String, Object> data = new HashMap<>();
            data.put("method", "rule_based");
            data.put("relationships", relationships);
            data.put("count", relationships.size());
            
            log.info("[TableRelationshipDetectionTool] 快速推断完成，共 {} 条关联", relationships.size());
            
            return ToolResponseBuilder.success("data")
                .withData(data)
                .withMessage(String.format("快速推断完成，发现 %d 条关联关系", relationships.size()))
                .addMetadata("toolName", "quick_detect_table_relationships")
                .addMetadata("datasourceId", datasourceId)
                .build();
            
        } catch (Exception e) {
            log.error("[TableRelationshipDetectionTool] 快速推断失败", e);
            
            return ToolResponseBuilder.error("QUICK_DETECTION_ERROR", e.getMessage())
                .addMetadata("toolName", "quick_detect_table_relationships")
                .addMetadata("datasourceId", datasourceId)
                .build();
        }
    }
}
