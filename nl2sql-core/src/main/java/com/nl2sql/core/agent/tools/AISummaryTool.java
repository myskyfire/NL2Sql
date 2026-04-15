package com.nl2sql.core.agent.tools;

import com.nl2sql.core.llm.ModelRouterService;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

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
     * @param dataJson 查询结果数据JSON字符串
     * @return AI生成的总结文本
     */
    @Tool("对SQL查询结果进行智能分析和总结，提取关键洞察")
    public String summarize(String userQuery, String sql, String dataJson) {
        try {
            List<Map<String, Object>> data = parseDataJson(dataJson);
            log.info("[AISummaryTool] 开始生成总结: data行数={}", data != null ? data.size() : 0);
            
            if (data == null || data.isEmpty()) {
                return "查询结果为空，无法生成总结。";
            }
            
            StringBuilder promptBuilder = new StringBuilder();
            promptBuilder.append("你是一个专业的数据分析师。请根据以下查询结果进行分析和总结。\n\n");
            
            if (userQuery != null && !userQuery.isEmpty()) {
                promptBuilder.append("用户问题：").append(userQuery).append("\n\n");
            }
            
            if (sql != null && !sql.isEmpty()) {
                promptBuilder.append("SQL查询：\n").append(sql).append("\n\n");
            }
            
            promptBuilder.append("查询结果：共").append(data.size()).append("行数据\n\n");
            
            // 以表格形式展示数据
            Set<String> columns = data.get(0).keySet();
            String header = String.join(" | ", columns);
            promptBuilder.append(header).append("\n");
            promptBuilder.append(String.join("-|-", java.util.Collections.nCopies(columns.size(), "---"))).append("\n");
            
            // 最多显示15行
            int displayRows = Math.min(15, data.size());
            for (int i = 0; i < displayRows; i++) {
                Map<String, Object> row = data.get(i);
                List<String> values = new java.util.ArrayList<>();
                for (String col : columns) {
                    Object value = row.get(col);
                    values.add(value != null ? value.toString() : "NULL");
                }
                promptBuilder.append(String.join(" | ", values)).append("\n");
            }
            
            if (data.size() > 15) {
                promptBuilder.append("... 还有 ").append(data.size() - 15).append(" 行数据\n");
            }
            promptBuilder.append("\n");
            
            promptBuilder.append("请分析以上数据并总结：\n");
            promptBuilder.append("1. 数据的主要趋势或模式\n");
            promptBuilder.append("2. 关键数值和异常点\n");
            promptBuilder.append("3. 业务洞察和建议\n\n");
            promptBuilder.append("要求：\n");
            promptBuilder.append("- 必须基于上述实际数据进行分析\n");
            promptBuilder.append("- 使用清晰的段落结构，每个要点之间用空行分隔\n");
            promptBuilder.append("- 数字列表格式：1. xxx\\n\\n2. xxx\\n\\n3. xxx\n");
            promptBuilder.append("- 子项使用破折号：- xxx\n");
            promptBuilder.append("- 控制总字数在200字以内\n");
            promptBuilder.append("- 用简洁的中文回答");
            
            String summary = modelRouter.getMultiModelService().summarizeResult(promptBuilder.toString());
            
            log.info("[AISummaryTool] 总结生成完成");
            return summary != null ? summary : "无法生成总结";
            
        } catch (Exception e) {
            log.error("[AISummaryTool] 生成总结失败", e);
            return "总结生成失败：" + e.getMessage();
        }
    }
    
    /**
     * 解析数据 JSON
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseDataJson(String dataJson) {
        if (dataJson == null || dataJson.trim().isEmpty()) {
            return new java.util.ArrayList<>();
        }
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            return mapper.readValue(dataJson, List.class);
        } catch (Exception e) {
            log.error("[AISummaryTool] 解析数据JSON失败", e);
            return new java.util.ArrayList<>();
        }
    }
}
