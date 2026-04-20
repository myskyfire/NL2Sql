package com.nl2sql.core.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 查询计划分析 Tool - 原子能力：执行EXPLAIN分析SQL性能
 */
@Slf4j
@Component
public class AnalyzeQueryPlanTool {
    
    @Autowired
    private JdbcTemplate jdbcTemplate;
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    /**
     * 分析SQL执行计划
     * 
     * @param sql SQL语句
     * @param datasourceId 数据源ID
     * @return JSON格式的执行计划分析结果
     */
    @Tool("分析SQL的执行计划（EXPLAIN），返回性能风险评估。输入SQL语句和数据源ID，返回风险等级、风险点列表和优化建议")
    public String analyzeQueryPlan(String sql, Long datasourceId) {
        try {
            log.info("[AnalyzeQueryPlanTool] 分析执行计划: {}", sql);
            
            // 执行EXPLAIN
            List<Map<String, Object>> explainResult = jdbcTemplate.queryForList("EXPLAIN " + sql);
            
            if (explainResult.isEmpty()) {
                return "{\"success\":false,\"error\":\"无法获取执行计划\"}";
            }
            
            Map<String, Object> firstRow = explainResult.get(0);
            
            // 分析风险
            List<String> risks = new ArrayList<>();
            List<String> suggestions = new ArrayList<>();
            String riskLevel = "LOW";
            
            // 1. 检查是否全表扫描
            String type = (String) firstRow.get("type");
            if ("ALL".equals(type)) {
                risks.add("全表扫描（type=ALL）");
                suggestions.add("考虑添加索引或使用更精确的WHERE条件");
                riskLevel = "HIGH";
            } else if ("index".equals(type)) {
                risks.add("索引全扫描（type=index）");
                suggestions.add("检查是否需要覆盖索引");
                if ("MEDIUM".compareTo(riskLevel) > 0 || "LOW".equals(riskLevel)) {
                    riskLevel = "MEDIUM";
                }
            }
            
            // 2. 检查是否使用临时表
            String extra = (String) firstRow.getOrDefault("Extra", "");
            if (extra.contains("Using temporary")) {
                risks.add("使用临时表");
                suggestions.add("优化GROUP BY或ORDER BY，避免临时表");
                if ("MEDIUM".compareTo(riskLevel) > 0 || "LOW".equals(riskLevel)) {
                    riskLevel = "MEDIUM";
                }
            }
            
            // 3. 检查是否文件排序
            if (extra.contains("Using filesort")) {
                risks.add("使用文件排序");
                suggestions.add("为ORDER BY字段添加索引");
                if ("MEDIUM".compareTo(riskLevel) > 0 || "LOW".equals(riskLevel)) {
                    riskLevel = "MEDIUM";
                }
            }
            
            // 4. 检查扫描行数
            Number rows = (Number) firstRow.get("rows");
            if (rows != null && rows.longValue() > 100000) {
                risks.add("预计扫描行数过多: " + rows);
                suggestions.add("添加LIMIT限制或优化查询条件");
                riskLevel = "HIGH";
            }
            
            // 5. 检查是否未使用索引
            String possibleKeys = (String) firstRow.getOrDefault("possible_keys", "");
            String key = (String) firstRow.getOrDefault("key", "");
            if (possibleKeys != null && !possibleKeys.isEmpty() && (key == null || key.isEmpty())) {
                risks.add("有可用索引但未使用");
                suggestions.add("检查WHERE条件是否能利用索引");
                if ("MEDIUM".compareTo(riskLevel) > 0 || "LOW".equals(riskLevel)) {
                    riskLevel = "MEDIUM";
                }
            }
            
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("riskLevel", riskLevel);
            result.put("risks", risks);
            result.put("suggestions", suggestions);
            result.put("explainResult", explainResult);
            result.put("scanType", type);
            result.put("estimatedRows", rows);
            
            log.info("[AnalyzeQueryPlanTool] 分析完成: riskLevel={}, risks={}", riskLevel, risks.size());
            
            return objectMapper.writeValueAsString(result);
            
        } catch (Exception e) {
            log.error("[AnalyzeQueryPlanTool] 分析失败", e);
            
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
