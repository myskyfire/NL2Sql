package com.nl2sql.core.agent.postprocessing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nl2sql.core.llm.LLMService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * SQL结果总结器
 * 将查询结果转换为自然语言描述
 */
@Slf4j
@Component
public class SQLResultSummarizer {
    
    @Autowired(required = false)
    private LLMService llmService;
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    /**
     * 生成查询结果的自然语言总结
     * 
     * @param data 查询结果数据
     * @param question 原始问题
     * @param sql 执行的SQL
     * @return 自然语言总结
     */
    public String summarize(List<Map<String, Object>> data, String question, String sql) {
        if (data == null || data.isEmpty()) {
            return "未查询到相关数据。";
        }
        
        // 如果LLM服务不可用，使用简单统计
        if (llmService == null) {
            return generateSimpleSummary(data, question);
        }
        
        try {
            // 构建Prompt
            String prompt = buildSummaryPrompt(data, question, sql);
            
            // 调用LLM生成总结
            String summary = llmService.generateAnswer(prompt);
            
            log.debug("SQL结果总结完成: question={}, rowCount={}", question, data.size());
            return summary;
            
        } catch (Exception e) {
            log.warn("LLM总结失败，使用简单统计: {}", e.getMessage());
            return generateSimpleSummary(data, question);
        }
    }
    
    /**
     * 简单统计总结（Fallback）
     */
    private String generateSimpleSummary(List<Map<String, Object>> data, String question) {
        int rowCount = data.size();
        
        if (rowCount == 0) {
            return "未查询到相关数据。";
        }
        
        StringBuilder summary = new StringBuilder();
        summary.append(String.format("查询返回 %d 条记录。\n", rowCount));
        
        // 尝试识别数值列并计算统计信息
        if (!data.isEmpty()) {
            Map<String, Object> firstRow = data.get(0);
            
            // 查找数值列
            for (Map.Entry<String, Object> entry : firstRow.entrySet()) {
                if (entry.getValue() instanceof Number) {
                    String columnName = entry.getKey();
                    double sum = 0;
                    double max = Double.MIN_VALUE;
                    double min = Double.MAX_VALUE;
                    
                    for (Map<String, Object> row : data) {
                        Object value = row.get(columnName);
                        if (value instanceof Number) {
                            double num = ((Number) value).doubleValue();
                            sum += num;
                            max = Math.max(max, num);
                            min = Math.min(min, num);
                        }
                    }
                    
                    summary.append(String.format("- %s: 总和=%.2f, 最大值=%.2f, 最小值=%.2f\n", 
                        columnName, sum, max, min));
                }
            }
        }
        
        return summary.toString();
    }
    
    /**
     * 构建总结Prompt
     */
    private String buildSummaryPrompt(List<Map<String, Object>> data, String question, String sql) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是数据分析助手，请根据查询结果总结关键发现。\n\n");
        prompt.append("问题：").append(question).append("\n\n");
        prompt.append("SQL：\n```sql\n").append(sql).append("\n```\n\n");
        
        int maxRows = Math.min(data.size(), 10);
        prompt.append("结果（前").append(maxRows).append("条）：\n");
        
        try {
            String jsonData = objectMapper.writeValueAsString(data.subList(0, maxRows));
            prompt.append(jsonData).append("\n\n");
        } catch (Exception e) {
            prompt.append("共").append(data.size()).append("条记录\n\n");
        }
        
        prompt.append("请总结：\n");
        prompt.append("1. 关键指标和趋势\n");
        prompt.append("2. 异常值或显著变化\n");
        prompt.append("3. 100 字以内，只返回总结\n\n");
        prompt.append("总结：");
        
        return prompt.toString();
    }
}
