package com.nl2sql.core.agent.tools;

import com.nl2sql.core.llm.ModelRouterService;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * AI总结 Tool - 对查询结果进行智能总结
 */
@Slf4j
@Component
public class AISummaryTool {
    
    @Autowired
    private ModelRouterService modelRouter;
    
    /**
     * 对查询结果生成AI总结
     * 
     * @param userQuery 用户原始问题
     * @param sql 执行的SQL
     * @param data 查询结果数据（可以是JSON字符串或List<Map>对象）
     * @return AI生成的总结文本
     */
    @Tool(name = "summarize_result", value = "对SQL查询结果进行智能分析和总结，提取关键洞察")
    public String summarize(String userQuery, String sql, Object data) {
        try {
            List<Map<String, Object>> dataList = parseData(data);
            log.info("[AISummaryTool] 开始生成总结: data行数={}", dataList != null ? dataList.size() : 0);
            
            if (dataList == null || dataList.isEmpty()) {
                return "查询结果为空，无法生成总结。";
            }
            
            StringBuilder promptBuilder = new StringBuilder();
            promptBuilder.append("分析查询结果:\n\n");
            
            if (userQuery != null && !userQuery.isEmpty()) {
                promptBuilder.append("问题：").append(userQuery).append("\n\n");
            }
            
            if (sql != null && !sql.isEmpty()) {
                promptBuilder.append("SQL：\n").append(sql).append("\n\n");
            }
            
            promptBuilder.append("结果：共").append(dataList.size()).append("行\n\n");
            
            // 以表格形式展示数据
            Set<String> columns = dataList.get(0).keySet();
            String header = String.join(" | ", columns);
            promptBuilder.append(header).append("\n");
            promptBuilder.append(String.join("-|-", java.util.Collections.nCopies(columns.size(), "---"))).append("\n");
            
            // 最多显示 15 行
            int displayRows = Math.min(15, dataList.size());
            for (int i = 0; i < displayRows; i++) {
                Map<String, Object> row = dataList.get(i);
                List<String> values = new ArrayList<>();
                for (String col : columns) {
                    Object value = row.get(col);
                    values.add(value != null ? value.toString() : "NULL");
                }
                promptBuilder.append(String.join(" | ", values)).append("\n");
            }
            
            if (dataList.size() > 15) {
                promptBuilder.append("... 还有 ").append(dataList.size() - 15).append(" 行\n");
            }
            promptBuilder.append("\n");
            
            promptBuilder.append("要求：基于实际数据，段落清晰，200字以内，简洁中文");
            
            String summary = modelRouter.getMultiModelService().summarizeResult(promptBuilder.toString());
            
            log.info("[AISummaryTool] 总结生成完成");
            return summary != null ? summary : "无法生成总结";
            
        } catch (Exception e) {
            log.error("[AISummaryTool] 生成总结失败", e);
            return "总结生成失败：" + e.getMessage();
        }
    }
    
    /**
     * 解析数据（支持JSON字符串或List<Map>对象）
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseData(Object data) {
        if (data == null) {
            return new ArrayList<>();
        }
        
        // 如果已经是 List 类型，直接返回
        if (data instanceof List) {
            return (List<Map<String, Object>>) data;
        }
        
        // 如果是 String 类型，尝试解析 JSON
        if (data instanceof String) {
            return parseDataJson((String) data);
        }
        
        log.warn("[AISummaryTool] 不支持的数据类型: {}", data.getClass().getName());
        return new ArrayList<>();
    }
    
    /**
     * 解析数据 JSON
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseDataJson(String dataJson) {
        if (dataJson == null || dataJson.trim().isEmpty()) {
            return new ArrayList<>();
        }
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            return mapper.readValue(dataJson, List.class);
        } catch (Exception e) {
            log.error("[AISummaryTool] 解析数据JSON失败", e);
            return new ArrayList<>();
        }
    }
}
