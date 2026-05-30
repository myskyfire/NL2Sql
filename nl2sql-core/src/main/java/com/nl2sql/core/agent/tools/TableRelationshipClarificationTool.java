package com.nl2sql.core.agent.tools;

import com.nl2sql.metadata.mapper.MetadataQueryMapper;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 表关系澄清 Tool - 当 LLM 不确定表如何关联时，用业务语言询问用户
 */
@Slf4j
@Component
public class TableRelationshipClarificationTool {
    
    @Autowired(required = false)
    private JdbcTemplate jdbcTemplate;
    
    @Autowired
    private MetadataQueryMapper metadataMapper;
    
    /**
     * 获取表的业务描述（用中文注释）
     * 
     * @param tableName 表名
     * @param datasourceId 数据源ID
     * @return 表的业务描述
     */
    @Tool("获取表的业务含义和字段说明，用于向用户澄清表关系")
    public String getTableBusinessDescription(String tableName, Long datasourceId) {
        try {
            log.info("[TableRelationshipClarificationTool] 获取表业务描述: {}", tableName);
            
            // 获取表注释
            String tableComment = metadataMapper.selectTableComment(datasourceId, tableName);
            
            if (tableComment == null || tableComment.isEmpty()) {
                tableComment = tableName;
            }
            
            // 获取所有字段的中文名
            List<Map<String, Object>> columns = metadataMapper.selectColumnNameAndComment(datasourceId, tableName);
            
            StringBuilder description = new StringBuilder();
            description.append("【").append(tableComment).append("】\n");
            
            for (Map<String, Object> col : columns) {
                String colName = (String) col.get("column_name");
                String colComment = (String) col.get("column_comment");
                
                if (colComment != null && !colComment.isEmpty()) {
                    description.append("- ").append(colComment).append("\n");
                } else {
                    description.append("- ").append(colName).append("\n");
                }
            }
            
            return description.toString();
            
        } catch (Exception e) {
            log.error("[TableRelationshipClarificationTool] 获取表描述失败", e);
            return "错误：无法获取表信息";
        }
    }
    
    /**
     * 生成面向业务的澄清问题
     * 
     * @param table1 表1名称
     * @param table2 表2名称
     * @param datasourceId 数据源ID
     * @return 面向用户的澄清问题
     */
    @Tool("当需要连接两个表但不确定关联字段时，生成面向业务用户的澄清问题")
    public String generateRelationshipQuestion(String table1, String table2, Long datasourceId) {
        try {
            log.info("[TableRelationshipClarificationTool] 生成关系澄清问题: {} <-> {}", table1, table2);
            
            // 获取两个表的业务描述
            String desc1 = getTableBusinessDescription(table1, datasourceId);
            String desc2 = getTableBusinessDescription(table2, datasourceId);
            
            // 提取表的中文名称
            String name1 = extractTableName(desc1);
            String name2 = extractTableName(desc2);
            
            // 生成面向业务的澄清问题
            StringBuilder question = new StringBuilder();
            question.append("我需要将「").append(name1).append("」和「").append(name2).append("」关联起来查询数据。\n\n");
            question.append("请问这两个表是通过什么业务字段关联的？\n");
            question.append("例如：\n");
            question.append("- 「").append(name1).append("」中的某个ID字段对应「").append(name2).append("」的主键\n");
            question.append("- 或者它们有共同的分类字段（如地区、部门等）\n\n");
            question.append("请用业务语言告诉我它们的关联方式。");
            
            return question.toString();
            
        } catch (Exception e) {
            log.error("[TableRelationshipClarificationTool] 生成澄清问题失败", e);
            return "请告诉我这两个表如何关联？";
        }
    }
    
    /**
     * 从业务描述中提取表名（第一行的中文名称）
     */
    private String extractTableName(String description) {
        if (description == null || description.isEmpty()) {
            return "未知表";
        }
        
        // 提取【】中的内容
        int start = description.indexOf("【");
        int end = description.indexOf("】");
        
        if (start != -1 && end != -1 && end > start) {
            return description.substring(start + 1, end);
        }
        
        // 如果没有【】，返回第一行
        String[] lines = description.split("\n");
        return lines[0].replaceAll("[\\[\\]【】]", "").trim();
    }
}
