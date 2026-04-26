package com.nl2sql.core.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 查询成本估算 Tool - 原子能力：估算SQL查询的资源消耗
 */
@Slf4j
@Component
public class EstimateCostTool {
    
    @Autowired
    private JdbcTemplate jdbcTemplate;
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    @Tool("估算SQL查询的成本，包括预计扫描行数、执行时间等。输入SQL和数据源ID，返回成本评估")
    public String estimateCost(String sql, Long datasourceId) {
        try {
            log.info("[EstimateCostTool] 估算查询成本");
            
            // 执行EXPLAIN获取预估信息
            List<Map<String, Object>> explainResult = jdbcTemplate.queryForList("EXPLAIN " + sql);
            
            if (explainResult.isEmpty()) {
                return ToolResponseBuilder.error("EXPLAIN_ERROR", "无法获取执行计划")
                    .addMetadata("toolName", "estimate_cost")
                    .build();
            }
            
            Map<String, Object> firstRow = explainResult.get(0);
            
            // 提取关键指标
            Number estimatedRows = (Number) firstRow.get("rows");
            String accessType = (String) firstRow.get("type");
            String extra = (String) firstRow.getOrDefault("Extra", "");
            
            // 计算成本分数（0-100，越低越好）
            int costScore = calculateCostScore(estimatedRows, accessType, extra);
            
            // 估算执行时间（粗略估计）
            long estimatedTimeMs = estimateExecutionTime(estimatedRows, accessType);
            
            // ✅ 构建统一响应
            Map<String, Object> data = new HashMap<>();
            data.put("costScore", costScore);
            data.put("costLevel", getCostLevel(costScore));
            data.put("estimatedRows", estimatedRows);
            data.put("estimatedTimeMs", estimatedTimeMs);
            data.put("accessType", accessType);
            data.put("usesIndex", !"ALL".equals(accessType));
            data.put("usesTemporary", extra.contains("Using temporary"));
            data.put("usesFilesort", extra.contains("Using filesort"));
            
            return ToolResponseBuilder.success("data")
                .withData(data)
                .addMetadata("toolName", "estimate_cost")
                .addMetadata("datasourceId", datasourceId)
                .build();
            
        } catch (Exception e) {
            log.error("[EstimateCostTool] 估算失败", e);
            return ToolResponseBuilder.error("COST_ESTIMATION_ERROR", e.getMessage())
                .addMetadata("toolName", "estimate_cost")
                .addMetadata("datasourceId", datasourceId)
                .build();
        }
    }
    
    private int calculateCostScore(Number rows, String accessType, String extra) {
        int score = 0;
        
        // 基于扫描行数
        if (rows != null) {
            long rowCount = rows.longValue();
            if (rowCount > 1000000) score += 40;
            else if (rowCount > 100000) score += 30;
            else if (rowCount > 10000) score += 20;
            else if (rowCount > 1000) score += 10;
        }
        
        // 基于访问类型
        if ("ALL".equals(accessType)) score += 30;
        else if ("index".equals(accessType)) score += 15;
        else if ("range".equals(accessType)) score += 10;
        
        // 基于额外操作
        if (extra.contains("Using temporary")) score += 15;
        if (extra.contains("Using filesort")) score += 10;
        
        return Math.min(score, 100);
    }
    
    private long estimateExecutionTime(Number rows, String accessType) {
        if (rows == null) return 1000;
        
        long rowCount = rows.longValue();
        long baseTime = 10; // 基础时间10ms
        
        if ("ALL".equals(accessType)) {
            return baseTime + rowCount / 100; // 全表扫描较慢
        } else {
            return baseTime + rowCount / 1000; // 索引访问较快
        }
    }
    
    private String getCostLevel(int score) {
        if (score < 20) return "LOW";
        if (score < 50) return "MEDIUM";
        if (score < 80) return "HIGH";
        return "VERY_HIGH";
    }
}
