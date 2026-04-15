package com.nl2sql.core.agent.tools;

import com.nl2sql.core.executor.SQLRiskAnalyzer;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * SQL风险分析 Tool - 执行EXPLAIN评估SQL性能
 */
@Slf4j
@Component
public class SQLRiskAnalysisTool {
    
    @Autowired
    private SQLRiskAnalyzer riskAnalyzer;
    
    /**
     * 分析SQL的执行计划和风险
     * 
     * @param sql SQL语句
     * @return 风险分析结果（包含风险等级、风险点、优化建议）
     */
    @Tool("在执行复杂SQL之前，先分析其执行计划和潜在风险。适用于：多表JOIN、子查询、大数据量查询等场景。返回风险等级(LOW/MEDIUM/HIGH)、风险点和优化建议。")
    public String analyzeSQLRisk(String sql) {
        try {
            log.info("[SQLRiskAnalysisTool] 开始分析SQL风险");
            
            SQLRiskAnalyzer.RiskAnalysisResult result = riskAnalyzer.analyzeRisk(sql);
            
            StringBuilder response = new StringBuilder();
            response.append("📊 SQL风险分析报告\n\n");
            response.append("风险等级: ").append(result.getRiskLevel()).append("\n\n");
            
            if (result.getRisks() != null && !result.getRisks().isEmpty()) {
                response.append("⚠️ 发现的风险点:\n");
                for (int i = 0; i < result.getRisks().size(); i++) {
                    response.append((i + 1)).append(". ").append(result.getRisks().get(i)).append("\n");
                }
                response.append("\n");
            } else {
                response.append("✅ 未发现明显风险\n\n");
            }
            
            if (result.getSuggestions() != null && !result.getSuggestions().isEmpty()) {
                response.append("💡 优化建议:\n");
                for (int i = 0; i < result.getSuggestions().size(); i++) {
                    response.append((i + 1)).append(". ").append(result.getSuggestions().get(i)).append("\n");
                }
                response.append("\n");
            }
            
            if (result.getTableStats() != null && !result.getTableStats().isEmpty()) {
                response.append("📈 表统计信息:\n");
                result.getTableStats().forEach((table, stats) -> {
                    response.append(String.format("  - %s: 预估行数=%d, 索引=%s\n",
                        table, stats.getRowCount(), stats.getIndexes()));
                });
            }
            
            log.info("[SQLRiskAnalysisTool] 分析完成: 风险等级={}", result.getRiskLevel());
            return response.toString();
            
        } catch (Exception e) {
            log.error("[SQLRiskAnalysisTool] 分析失败", e);
            return "❌ 分析失败: " + e.getMessage();
        }
    }
}
