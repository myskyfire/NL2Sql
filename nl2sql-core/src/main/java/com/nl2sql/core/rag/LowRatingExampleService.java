package com.nl2sql.core.rag;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 低分SQL示例服务
 * 用于在生成SQL前提供负面示例，避免重复错误
 */
@Slf4j
@Service
public class LowRatingExampleService {
    
    @Autowired
    private JdbcTemplate jdbcTemplate;
    
    /**
     * 查找与当前问题相似的低分示例
     * 
     * @param question 当前问题
     * @param similarityThreshold 相似度阈值（0.0-1.0）
     * @param limit 返回数量
     * @return 低分示例列表
     */
    public List<LowRatingExample> findSimilarLowRatingExamples(
            String question, double similarityThreshold, int limit) {
        
        try {
            // 使用FULLTEXT搜索找到相似问题的低分反馈
            String sql = "SELECT question, generated_sql, feedback_text, rating, " +
                        "MATCH(question) AGAINST(? IN NATURAL LANGUAGE MODE) as relevance " +
                        "FROM rag_feedback " +
                        "WHERE rating <= 2 " +
                        "AND MATCH(question) AGAINST(? IN NATURAL LANGUAGE MODE) " +
                        "ORDER BY relevance DESC " +
                        "LIMIT ?";
            
            List<LowRatingExample> examples = jdbcTemplate.query(sql, 
                (rs, rowNum) -> {
                    LowRatingExample example = new LowRatingExample();
                    example.setQuestion(rs.getString("question"));
                    example.setGeneratedSql(rs.getString("generated_sql"));
                    example.setFeedbackText(rs.getString("feedback_text"));
                    example.setRating(rs.getInt("rating"));
                    example.setRelevance(rs.getDouble("relevance"));
                    return example;
                },
                question, question, limit
            );
            
            // 过滤掉相似度低于阈值的
            return examples.stream()
                .filter(ex -> ex.getRelevance() >= similarityThreshold)
                .toList();
                
        } catch (Exception e) {
            log.error("[低分示例] 查询失败: question={}", question, e);
            return List.of();
        }
    }
    
    /**
     * 检查生成的SQL是否与历史低分SQL高度相似
     * 
     * @param question 当前问题
     * @param generatedSql 生成的SQL
     * @return 如果找到高度相似的低分示例，返回该示例；否则返回null
     */
    public LowRatingExample checkIfSimilarToLowRating(String question, String generatedSql) {
        try {
            // 简化版：直接检查是否有相同问题的低分反馈
            String sql = "SELECT question, generated_sql, feedback_text, rating " +
                        "FROM rag_feedback " +
                        "WHERE question = ? AND rating <= 2 " +
                        "ORDER BY created_at DESC LIMIT 1";
            
            List<LowRatingExample> results = jdbcTemplate.query(sql,
                (rs, rowNum) -> {
                    LowRatingExample example = new LowRatingExample();
                    example.setQuestion(rs.getString("question"));
                    example.setGeneratedSql(rs.getString("generated_sql"));
                    example.setFeedbackText(rs.getString("feedback_text"));
                    example.setRating(rs.getInt("rating"));
                    example.setRelevance(1.0); // 完全匹配
                    return example;
                },
                question
            );
            
            return results.isEmpty() ? null : results.get(0);
            
        } catch (Exception e) {
            log.error("[低分检查] 查询失败: question={}", question, e);
            return null;
        }
    }
    
    /**
     * 低分示例数据类
     */
    @Data
    public static class LowRatingExample {
        private String question;        // 原始问题
        private String generatedSql;    // 生成的SQL
        private String feedbackText;    // 用户反馈原因
        private Integer rating;         // 评分（1-2星）
        private Double relevance;       // 相似度/相关性
    }
}
