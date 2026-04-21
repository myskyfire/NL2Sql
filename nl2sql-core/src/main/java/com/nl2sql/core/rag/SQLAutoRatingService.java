package com.nl2sql.core.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SQL自动评分服务
 * 根据生成的SQL与用户问题的匹配度自动评分（1-5星）
 */
@Slf4j
@Service
public class SQLAutoRatingService {
    
    @Autowired(required = false)
    private JdbcTemplate jdbcTemplate;
    
    /**
     * 自动评分
     * 
     * @param question 用户问题
     * @param generatedSql 生成的SQL
     * @return 评分结果（1-5星 + 原因）
     */
    public RatingResult autoRate(String question, String generatedSql) {
        if (question == null || question.trim().isEmpty() || 
            generatedSql == null || generatedSql.trim().isEmpty()) {
            return new RatingResult(1, "问题或SQL为空");
        }
        
        List<String> issues = new ArrayList<>();
        int score = 5; // 默认满分
        
        String lowerQuestion = question.toLowerCase();
        String lowerSql = generatedSql.toLowerCase();
        
        // 1. 检查是否是错误响应
        if (lowerSql.contains("error") || lowerSql.contains("exception") || 
            lowerSql.contains("failed") || lowerSql.contains("抱歉")) {
            return new RatingResult(1, "SQL生成失败: " + generatedSql);
        }
        
        // 2. 检查是否返回了澄清请求而非SQL
        if (lowerSql.contains("needsclarification") || lowerSql.contains("clarify") ||
            lowerSql.contains("请确认") || lowerSql.contains("需要澄清")) {
            return new RatingResult(1, "未生成SQL，返回了澄清请求");
        }
        
        // 3. 检查关键字段缺失
        checkMissingFields(lowerQuestion, lowerSql, issues);
        
        // 4. 检查不必要的复杂化
        checkUnnecessaryComplexity(lowerQuestion, lowerSql, issues);
        
        // 5. 检查语义偏差
        checkSemanticDeviation(lowerQuestion, lowerSql, issues);
        
        // 6. 根据问题数量扣分
        score -= issues.size();
        score = Math.max(1, Math.min(5, score));
        
        String reason = issues.isEmpty() ? "SQL质量良好" : String.join("; ", issues);
        
        log.info("[SQL自动评分] 问题: {}, 评分: {}星, 原因: {}", question, score, reason);
        
        return new RatingResult(score, reason);
    }
    
    /**
     * 检查缺失的关键字段
     */
    private void checkMissingFields(String question, String sql, List<String> issues) {
        // 检查聚合函数
        if (question.contains("总数") || question.contains("count") || question.contains("多少")) {
            if (!sql.contains("count(")) {
                issues.add("预期COUNT聚合但未找到");
            }
        }
        
        if (question.contains("总额") || question.contains("总金额") || question.contains("sum")) {
            if (!sql.contains("sum(")) {
                issues.add("预期SUM聚合但未找到");
            }
        }
        
        if (question.contains("平均") || question.contains("avg")) {
            if (!sql.contains("avg(")) {
                issues.add("预期AVG聚合但未找到");
            }
        }
        
        // 检查关键字段名
        if (question.contains("金额") || question.contains("amount")) {
            if (!sql.contains("amount") && !sql.contains("price") && !sql.contains("money")) {
                issues.add("预期金额字段但未找到相关字段");
            }
        }
        
        if (question.contains("数量") || question.contains("quantity") || question.contains("count")) {
            if (!sql.contains("quantity") && !sql.contains("count(")) {
                issues.add("预期数量字段但未找到");
            }
        }
    }
    
    /**
     * 检查不必要的复杂化
     */
    private void checkUnnecessaryComplexity(String question, String sql, List<String> issues) {
        // 简单计数问题不应该有GROUP BY
        if ((question.contains("总共有多少") || question.contains("总数")) && 
            !question.contains("分组") && !question.contains("每个")) {
            if (sql.contains("group by")) {
                issues.add("简单计数不应有GROUP BY");
            }
        }
        
        // 预期无限制但实际有LIMIT
        if (!question.contains("前") && !question.contains("limit") && !question.contains("最")) {
            if (sql.contains("limit")) {
                issues.add("添加了不必要的LIMIT限制");
            }
        }
        
        // 检查是否有过多的JOIN（预期简单查询）
        int joinCount = countMatches(sql, "join");
        if (joinCount > 3 && !question.contains("关联") && !question.contains("join")) {
            issues.add("JOIN过多(" + joinCount + "个)，可能过度复杂");
        }
    }
    
    /**
     * 检查语义偏差
     */
    private void checkSemanticDeviation(String question, String sql, List<String> issues) {
        // 销售额 vs 销售数量
        if ((question.contains("销售额") || question.contains("销售金额")) && 
            sql.contains("sum(quantity)") && !sql.contains("sum(subtotal") && !sql.contains("sum(amount")) {
            issues.add("预期销售额(SUM金额)但用了销售数量(SUM quantity)");
        }
        
        // 最近N天应该有时间过滤
        if ((question.contains("最近") || question.contains("近")) && 
            (question.contains("天") || question.contains("日"))) {
            if (!sql.contains("where") || (!sql.contains("created_at") && !sql.contains("date"))) {
                issues.add("时间范围查询缺少WHERE条件");
            }
        }
        
        // 特定用户/地区应该有精确匹配
        if (question.contains("广东省") || question.contains("广东")) {
            if (sql.contains("like") && sql.contains("%")) {
                // LIKE模糊匹配可能不精确
                if (!sql.contains("province") && !sql.contains("region")) {
                    issues.add("地区匹配使用了LIKE而非精确字段");
                }
            }
        }
    }
    
    /**
     * 计算字符串出现次数
     */
    private int countMatches(String text, String pattern) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(pattern, index)) != -1) {
            count++;
            index += pattern.length();
        }
        return count;
    }
    
    /**
     * 评分结果
     */
    public static class RatingResult {
        private int rating; // 1-5
        private String reason;
        
        public RatingResult(int rating, String reason) {
            this.rating = rating;
            this.reason = reason;
        }
        
        public int getRating() {
            return rating;
        }
        
        public String getReason() {
            return reason;
        }
    }
}
