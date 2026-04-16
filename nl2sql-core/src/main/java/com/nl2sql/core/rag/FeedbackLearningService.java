package com.nl2sql.core.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 反馈学习服务
 * 根据用户低分反馈自动分析原因并优化RAG知识库
 */
@Slf4j
@Service
public class FeedbackLearningService {
    
    @Autowired
    private JdbcTemplate jdbcTemplate;
    
    @Autowired(required = false)
    private RagKnowledgeBaseService ragKnowledgeBaseService;
    
    /**
     * 处理低分反馈，触发Agent学习修正
     * 
     * @param feedbackId 反馈ID
     * @param rating 评分（1-2星）
     * @param question 原始问题
     * @param generatedSql 生成的SQL
     * @param feedbackText 用户反馈的原因
     */
    public void processLowRatingFeedback(Long feedbackId, int rating, String question,
                                        String generatedSql, String feedbackText) {
        log.info("[反馈学习] 开始处理低分反馈: feedbackId={}, rating={}, question={}",
            feedbackId, rating, question);
        
        try {
            // 1. 分析低分原因（关键词提取）
            List<String> errorCategories = analyzeErrorReasons(feedbackText);
            
            log.info("[反馈学习] 错误分类: {}", errorCategories);
            
            // 2. 降低相关示例的质量评分
            degradeSimilarExamples(question, generatedSql, rating);
            
            // 3. 标记为负面示例（供后续过滤）
            markAsNegativeExample(feedbackId, errorCategories);
            
            // 4. 生成修正建议（可选，记录到日志供人工审核）
            generateCorrectionSuggestion(question, generatedSql, feedbackText, errorCategories);
            
            log.info("[反馈学习] 低分反馈处理完成: feedbackId={}", feedbackId);
            
        } catch (Exception e) {
            log.error("[反馈学习] 处理低分反馈失败: feedbackId={}", feedbackId, e);
        }
    }
    
    /**
     * 分析错误原因（关键词匹配）
     */
    private List<String> analyzeErrorReasons(String feedbackText) {
        if (feedbackText == null || feedbackText.trim().isEmpty()) {
            return List.of("UNKNOWN");
        }
        
        String lowerText = feedbackText.toLowerCase();
        Set<String> categories = new HashSet<>();
        
        // GROUP BY相关错误
        if (lowerText.contains("group by") || lowerText.contains("分组") || 
            lowerText.contains("汇总") || lowerText.contains("统计")) {
            categories.add("WRONG_GROUP_BY");
        }
        
        // JOIN相关错误
        if (lowerText.contains("join") || lowerText.contains("关联") || 
            lowerText.contains("连接") || lowerText.contains("表关系")) {
            categories.add("WRONG_JOIN");
        }
        
        // 字段选择错误
        if (lowerText.contains("字段") || lowerText.contains("列") || 
            lowerText.contains("select") || lowerText.contains("查询")) {
            categories.add("WRONG_FIELDS");
        }
        
        // WHERE条件错误
        if (lowerText.contains("where") || lowerText.contains("条件") || 
            lowerText.contains("过滤") || lowerText.contains("筛选")) {
            categories.add("WRONG_WHERE");
        }
        
        // 时间格式化错误
        if (lowerText.contains("时间") || lowerText.contains("日期") || 
            lowerText.contains("date_format") || lowerText.contains("格式")) {
            categories.add("WRONG_DATE_FORMAT");
        }
        
        // 语法错误
        if (lowerText.contains("语法") || lowerText.contains("错误") || 
            lowerText.contains("报错") || lowerText.contains("执行失败")) {
            categories.add("SYNTAX_ERROR");
        }
        
        // 语义理解错误
        if (lowerText.contains("理解") || lowerText.contains("意思") || 
            lowerText.contains("不是") || lowerText.contains("应该是")) {
            categories.add("SEMANTIC_ERROR");
        }
        
        return categories.isEmpty() ? List.of("UNKNOWN") : List.copyOf(categories);
    }
    
    /**
     * 降低相似示例的质量评分
     */
    private void degradeSimilarExamples(String question, String generatedSql, int rating) {
        if (ragKnowledgeBaseService == null) {
            log.warn("[反馈学习] RAG服务未启用，跳过质量评分调整");
            return;
        }
        
        try {
            // 查找相似的SQL示例
            String similarSqlPattern = generateSqlPattern(generatedSql);
            
            String updateSql = "UPDATE rag_knowledge_base SET quality_score = GREATEST(quality_score - ?, 0), " +
                              "usage_count = usage_count + 1 " +
                              "WHERE sql_example LIKE ? OR question LIKE ?";
            
            int affectedRows = jdbcTemplate.update(updateSql, 
                calculateDegradation(rating),
                "%" + extractTableName(generatedSql) + "%",
                "%" + extractKeywords(question) + "%"
            );
            
            log.info("[反馈学习] 降低 {} 个相似示例的质量评分", affectedRows);
            
        } catch (Exception e) {
            log.error("[反馈学习] 降低质量评分失败", e);
        }
    }
    
    /**
     * 标记为负面示例
     */
    private void markAsNegativeExample(Long feedbackId, List<String> errorCategories) {
        try {
            String categoriesStr = String.join(",", errorCategories);
            
            String updateSql = "UPDATE rag_feedback SET feedback_text = CONCAT(IFNULL(feedback_text, ''), '|ERROR_CATEGORIES:', ?) " +
                              "WHERE id = ?";
            
            jdbcTemplate.update(updateSql, categoriesStr, feedbackId);
            
            log.info("[反馈学习] 已标记负面示例: feedbackId={}, categories={}", feedbackId, categoriesStr);
            
        } catch (Exception e) {
            log.error("[反馈学习] 标记负面示例失败", e);
        }
    }
    
    /**
     * 生成修正建议（记录到日志）
     */
    private void generateCorrectionSuggestion(String question, String generatedSql, 
                                             String feedbackText, List<String> errorCategories) {
        StringBuilder suggestion = new StringBuilder();
        suggestion.append("\n========== 低分反馈修正建议 ==========\n");
        suggestion.append("问题: ").append(question).append("\n");
        suggestion.append("原SQL: ").append(generatedSql).append("\n");
        suggestion.append("用户反馈: ").append(feedbackText).append("\n");
        suggestion.append("错误分类: ").append(String.join(", ", errorCategories)).append("\n\n");
        
        // 根据不同错误类型生成建议
        if (errorCategories.contains("WRONG_GROUP_BY")) {
            suggestion.append("建议: 检查是否需要GROUP BY，用户可能想要详情而非汇总\n");
        }
        if (errorCategories.contains("WRONG_JOIN")) {
            suggestion.append("建议: 检查JOIN条件和表关联关系是否正确\n");
        }
        if (errorCategories.contains("WRONG_FIELDS")) {
            suggestion.append("建议: 检查SELECT字段是否符合用户需求\n");
        }
        if (errorCategories.contains("SEMANTIC_ERROR")) {
            suggestion.append("建议: 重新理解用户意图，可能需要澄清\n");
        }
        
        suggestion.append("========================================\n");
        
        log.warn(suggestion.toString());
    }
    
    /**
     * 计算降级幅度
     */
    private float calculateDegradation(int rating) {
        switch (rating) {
            case 1: return 0.3f;  // 很差：大幅降低
            case 2: return 0.2f;  // 较差：中度降低
            default: return 0.1f;
        }
    }
    
    /**
     * 从 SQL中提取表名（简化版）
     */
    private String extractTableName(String sql) {
        if (sql == null || sql.isEmpty()) return "";
            
        // 简单提取FROM后面的第一个表名
        Pattern pattern = Pattern.compile(
            "\\bFROM\\s+(\\w+)", Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(sql);
            
        if (matcher.find()) {
            return matcher.group(1);
        }
            
        return "";
    }
    
    /**
     * 从问题中提取关键词
     */
    private String extractKeywords(String question) {
        if (question == null || question.isEmpty()) return "";
        
        // 简单提取中文字符
        Pattern pattern = Pattern.compile("[\\u4e00-\\u9fa5]{2,}");
        Matcher matcher = pattern.matcher(question);
        
        StringBuilder keywords = new StringBuilder();
        while (matcher.find()) {
            if (keywords.length() > 0) keywords.append("|");
            keywords.append(matcher.group());
        }
        
        return keywords.toString();
    }
    
    /**
     * 生成SQL模式（用于模糊匹配）
     */
    private String generateSqlPattern(String sql) {
        if (sql == null || sql.isEmpty()) return "";
        
        // 移除具体值，保留结构
        String pattern = sql.replaceAll("'[^']*'", "'%'")
                           .replaceAll("\\d+", "%");
        
        return pattern;
    }
}
