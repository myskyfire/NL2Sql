package com.nl2sql.core.agent.tools;

import com.nl2sql.core.visualization.ChartRecommendationService;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 图表推荐 Tool - 根据数据推荐可视化图表
 */
@Slf4j
@Component
public class ChartRecommendationTool {
    
    @Autowired
    private ChartRecommendationService chartRecommendationService;
    
    /**
     * 根据查询结果推荐合适的图表
     * 
     * @param userQuery 用户原始问题
     * @param dataJson 查询结果数据JSON字符串
     * @return 图表推荐结果（JSON格式）
     */
    @Tool("根据查询结果数据推荐合适的可视化图表类型和配置")
    public String recommendCharts(String userQuery, String dataJson) {
        try {
            List<Map<String, Object>> data = parseDataJson(dataJson);
            log.info("[ChartRecommendationTool] 开始推荐图表: data行数={}", data != null ? data.size() : 0);
            
            if (data == null || data.isEmpty()) {
                return "无数据，无法推荐图表。";
            }
            
            ChartRecommendationService.ChartRecommendation recommendation = 
                chartRecommendationService.recommendCharts(userQuery, data);
            
            log.info("[ChartRecommendationTool] 推荐完成: {}个图表", 
                recommendation.getCharts().size());
            
            // 返回简化的图表信息
            StringBuilder result = new StringBuilder();
            result.append("推荐的图表类型：\n");
            
            for (int i = 0; i < Math.min(3, recommendation.getCharts().size()); i++) {
                ChartRecommendationService.ChartConfig chart = recommendation.getCharts().get(i);
                result.append(String.format("%d. %s - %s\n", 
                    i + 1, 
                    chart.getType(), 
                    chart.getTitle()
                ));
            }
            
            return result.toString();
            
        } catch (Exception e) {
            log.error("[ChartRecommendationTool] 推荐图表失败", e);
            return "图表推荐失败：" + e.getMessage();
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
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            return mapper.readValue(dataJson, List.class);
        } catch (Exception e) {
            log.error("[ChartRecommendationTool] 解析数据JSON失败", e);
            return new ArrayList<>();
        }
    }
}
