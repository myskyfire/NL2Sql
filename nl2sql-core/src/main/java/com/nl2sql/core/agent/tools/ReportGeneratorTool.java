package com.nl2sql.core.agent.tools;

import com.nl2sql.core.llm.ModelRouterService;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 报告生成工具 - 生成结构化数据分析报告
 */
@Slf4j
@Component
public class ReportGeneratorTool {
    
    @Autowired
    private ModelRouterService modelRouter;
    
    /**
     * 生成结构化报告
     */
    @Tool("根据查询结果生成结构化的数据分析报告。输入用户问题、SQL和数据JSON字符串，返回包含摘要、关键发现、建议的报告")
    public String generateReport(String userQuery, String sql, String dataJson) {
        try {
            log.info("[ReportGenerator] 生成报告");
            
            // 解析 JSON 数据
            List<Map<String, Object>> data = parseDataJson(dataJson);
            
            if (data == null || data.isEmpty()) {
                return "📊 数据分析报告\n\n❌ 查询未返回任何数据";
            }
            
            // 1. 数据统计
            Map<String, Object> statistics = calculateStatistics(data);
            
            // 2. 构建报告 Prompt
            String reportPrompt = buildReportPrompt(userQuery, sql, data, statistics);
            
            // 3. 调用 LLM 生成报告
            String report = modelRouter.getMultiModelService().summarizeResult(reportPrompt);
            
            // 4. 格式化报告
            return formatReport(report, statistics);
            
        } catch (Exception e) {
            log.error("[ReportGenerator] 生成失败", e);
            return "❌ 报告生成失败: " + e.getMessage();
        }
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
            // 使用 Jackson 解析 JSON
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            return mapper.readValue(dataJson, List.class);
        } catch (Exception e) {
            log.error("[ReportGenerator] 解析数据JSON失败", e);
            return new ArrayList<>();
        }
    }
    
    /**
     * 计算数据统计信息
     */
    private Map<String, Object> calculateStatistics(List<Map<String, Object>> data) {
        Map<String, Object> stats = new HashMap<>();
        
        stats.put("totalRows", data.size());
        stats.put("columns", data.get(0).keySet().size());
        
        // 尝试找出数值列并计算统计
        for (String column : data.get(0).keySet()) {
            Object firstValue = data.get(0).get(column);
            if (firstValue instanceof Number) {
                double sum = 0;
                double max = Double.MIN_VALUE;
                double min = Double.MAX_VALUE;
                
                for (Map<String, Object> row : data) {
                    double value = ((Number) row.get(column)).doubleValue();
                    sum += value;
                    max = Math.max(max, value);
                    min = Math.min(min, value);
                }
                
                Map<String, Double> colStats = new HashMap<>();
                colStats.put("sum", sum);
                colStats.put("avg", sum / data.size());
                colStats.put("max", max);
                colStats.put("min", min);
                
                stats.put(column + "_stats", colStats);
            }
        }
        
        return stats;
    }
    
    /**
     * 构建报告 Prompt
     */
    private String buildReportPrompt(String userQuery, String sql, 
                                     List<Map<String, Object>> data,
                                     Map<String, Object> statistics) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是数据分析师，请根据以下数据生成报告。\n\n");
        
        prompt.append("问题：").append(userQuery).append("\n\n");
        prompt.append("SQL 查询：").append(sql).append("\n\n");
        
        prompt.append("数据统计：\n");
        prompt.append("- 总行数：").append(statistics.get("totalRows")).append("\n");
        prompt.append("- 列数：").append(statistics.get("columns")).append("\n");
        
        // 添加数值列统计
        for (Map.Entry<String, Object> entry : statistics.entrySet()) {
            if (entry.getKey().endsWith("_stats")) {
                String columnName = entry.getKey().replace("_stats", "");
                Map<String, Double> colStats = (Map<String, Double>) entry.getValue();
                prompt.append(String.format("- %s: 总和=%.2f, 平均=%.2f, 最大=%.2f, 最小=%.2f\n",
                    columnName, colStats.get("sum"), colStats.get("avg"),
                    colStats.get("max"), colStats.get("min")));
            }
        }
        
        prompt.append("\n数据样本（前 10 行）：\n");
        int displayRows = Math.min(10, data.size());
        for (int i = 0; i < displayRows; i++) {
            prompt.append(data.get(i).toString()).append("\n");
        }
        
        prompt.append("\n报告结构：\n");
        prompt.append("1. 执行摘要（50 字以内）\n");
        prompt.append("2. 关键发现（3-5 个，有数据支撑）\n");
        prompt.append("3. 趋势分析（模式、异常值）\n");
        prompt.append("4. 业务建议（2-3 条，可操作）\n\n");
        
        prompt.append("要求：专业易懂，避免术语，突出业务价值");
        
        return prompt.toString();
    }
    
    /**
     * 格式化报告
     */
    private String formatReport(String rawReport, Map<String, Object> statistics) {
        StringBuilder formatted = new StringBuilder();
        
        formatted.append("📊 数据分析报告\n");
        formatted.append(new String(new char[50]).replace("\0", "=")).append("\n\n");
        
        // 添加统计摘要
        formatted.append("📈 数据概览:\n");
        formatted.append("- 记录数: ").append(statistics.get("totalRows")).append(" 条\n");
        formatted.append("- 字段数: ").append(statistics.get("columns")).append(" 个\n\n");
        
        formatted.append(rawReport);
        
        formatted.append("\n").append(new String(new char[50]).replace("\0", "="));
        formatted.append("\n💡 提示: 如需可视化图表，请告诉我\"生成图表\"");
        
        return formatted.toString();
    }
}
