package com.nl2sql.core.agent.tools;

import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * SQL 优化工具 - 分析SQL性能并提供优化建议
 */
@Slf4j
@Component
public class SQLOptimizerTool {
    
    @Autowired
    private JdbcTemplate jdbcTemplate;
    
    /**
     * 分析SQL并给出优化建议
     */
    @Tool("分析SQL查询的性能问题并提供优化建议。输入SQL语句和数据源ID，返回优化建议")
    public String analyzeAndOptimize(String sql, Long datasourceId) {
        try {
            log.info("[SQLOptimizer] 开始分析SQL: {}", sql);
            
            List<String> suggestions = new ArrayList<>();
            
            // 1. 执行 EXPLAIN 分析
            String explainSQL = "EXPLAIN " + sql;
            List<Map<String, Object>> explainResult = executeExplain(explainSQL, datasourceId);
            
            if (explainResult != null && !explainResult.isEmpty()) {
                suggestions.addAll(analyzeExplainResult(explainResult));
            }
            
            // 2. 检查常见性能问题
            suggestions.addAll(checkCommonIssues(sql));
            
            // 3. 生成优化后的SQL（如果可能）
            String optimizedSQL = generateOptimizedSQL(sql, suggestions);
            
            // 4. 构建响应
            StringBuilder response = new StringBuilder();
            response.append("📊 SQL 性能分析报告\n\n");
            
            if (suggestions.isEmpty()) {
                response.append("✅ SQL 性能良好，未发现明显问题\n");
            } else {
                response.append("⚠️ 发现 ").append(suggestions.size()).append(" 个优化点：\n\n");
                for (int i = 0; i < suggestions.size(); i++) {
                    response.append((i + 1)).append(". ").append(suggestions.get(i)).append("\n");
                }
            }
            
            if (optimizedSQL != null && !optimizedSQL.equals(sql)) {
                response.append("\n💡 优化后的SQL：\n").append(optimizedSQL);
            }
            
            return response.toString();
            
        } catch (Exception e) {
            log.error("[SQLOptimizer] 分析失败", e);
            return "❌ 分析失败: " + e.getMessage();
        }
    }
    
    /**
     * 执行 EXPLAIN
     */
    private List<Map<String, Object>> executeExplain(String explainSQL, Long datasourceId) {
        try {
            return jdbcTemplate.queryForList(explainSQL);
        } catch (Exception e) {
            log.warn("[SQLOptimizer] EXPLAIN 执行失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }
    
    /**
     * 分析 EXPLAIN 结果
     */
    private List<String> analyzeExplainResult(List<Map<String, Object>> explainResult) {
        List<String> suggestions = new ArrayList<>();
        
        for (Map<String, Object> row : explainResult) {
            String type = String.valueOf(row.get("type"));
            String extra = String.valueOf(row.get("extra"));
            Long rows = row.get("rows") != null ? ((Number) row.get("rows")).longValue() : 0;
            
            // 全表扫描警告
            if ("ALL".equals(type)) {
                suggestions.add("⚠️ 检测到全表扫描（type=ALL），建议添加索引");
            }
            
            // 临时表警告
            if (extra != null && extra.contains("Using temporary")) {
                suggestions.add("⚠️ 使用了临时表，可能导致性能下降，考虑优化 GROUP BY 或 ORDER BY");
            }
            
            // 文件排序警告
            if (extra != null && extra.contains("Using filesort")) {
                suggestions.add("⚠️ 使用了文件排序，建议在排序字段上添加索引");
            }
            
            // 行数过多警告
            if (rows > 10000) {
                suggestions.add("⚠️ 预计扫描 " + rows + " 行数据，建议添加WHERE条件限制范围");
            }
        }
        
        return suggestions;
    }
    
    /**
     * 检查常见问题
     */
    private List<String> checkCommonIssues(String sql) {
        List<String> issues = new ArrayList<>();
        String upperSQL = sql.toUpperCase();
        
        // SELECT * 警告
        if (upperSQL.contains("SELECT *")) {
            issues.add("💡 避免使用 SELECT *，明确指定需要的字段可以减少数据传输量");
        }
        
        // 缺少 LIMIT
        if (!upperSQL.contains("LIMIT")) {
            issues.add("💡 建议添加 LIMIT 子句限制返回行数，避免一次性加载大量数据");
        }
        
        // LIKE 前缀通配符
        if (upperSQL.contains("LIKE '%")) {
            issues.add("⚠️ LIKE 使用前缀通配符（%xxx）无法利用索引，考虑使用全文索引");
        }
        
        // OR 条件
        if (upperSQL.contains(" OR ")) {
            issues.add("💡 OR 条件可能导致索引失效，考虑使用 UNION 替代");
        }
        
        // 函数调用在WHERE中
        if (upperSQL.matches(".*WHERE.*\\(.*\\).*")) {
            issues.add("⚠️ WHERE 子句中使用函数可能导致索引失效，考虑将函数移到应用层处理");
        }
        
        return issues;
    }
    
    /**
     * 生成优化后的SQL
     */
    private String generateOptimizedSQL(String originalSQL, List<String> suggestions) {
        String optimized = originalSQL;
        
        // 如果缺少 LIMIT，自动添加
        if (!optimized.toUpperCase().contains("LIMIT") && 
            suggestions.stream().anyMatch(s -> s.contains("LIMIT"))) {
            optimized = optimized.trim();
            if (optimized.endsWith(";")) {
                optimized = optimized.substring(0, optimized.length() - 1);
            }
            optimized += " LIMIT 100";
        }
        
        return optimized;
    }
}
