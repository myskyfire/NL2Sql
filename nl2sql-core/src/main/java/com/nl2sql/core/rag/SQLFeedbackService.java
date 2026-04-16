package com.nl2sql.core.rag;

import com.nl2sql.core.rag.dto.SQLFeedbackRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * SQL反馈服务
 * 负责收集和处理用户对生成SQL的反馈，用于持续优化
 */
@Slf4j
@Service
public class SQLFeedbackService {
    
    @Autowired
    private JdbcTemplate jdbcTemplate;
    
    @Autowired(required = false)
    private RagKnowledgeBaseService ragKnowledgeBaseService;
    
    /**
     * 提交SQL反馈
     */
    public Long submitFeedback(SQLFeedbackRequest request, String ipAddress, String userAgent) {
        try {
            // 参数校验
            if (request.getRating() == null || request.getRating() < 1 || request.getRating() > 5) {
                throw new IllegalArgumentException("评分必须在1-5之间");
            }
            if (request.getQuestion() == null || request.getQuestion().trim().isEmpty()) {
                throw new IllegalArgumentException("问题不能为空");
            }
            if (request.getGeneratedSql() == null || request.getGeneratedSql().trim().isEmpty()) {
                throw new IllegalArgumentException("生成的SQL不能为空");
            }
            
            // 保存反馈
            String sql = "INSERT INTO rag_feedback (" +
                        "knowledge_id, user_id, session_id, rating, feedback_text, " +
                        "question, generated_sql, executed_sql, execution_success, " +
                        "ip_address, user_agent, created_at" +
                        ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW())";
            
            jdbcTemplate.update(sql,
                request.getKnowledgeId(),
                null, // userId 从SecurityContext获取，暂时留空
                request.getSessionId(),
                request.getRating(),
                request.getFeedbackText(),
                request.getQuestion(),
                request.getGeneratedSql(),
                request.getExecutedSql(),
                request.getExecutionSuccess() != null ? request.getExecutionSuccess() : false,
                ipAddress,
                userAgent
            );
            
            Long feedbackId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
            
            log.info("收到SQL反馈: feedbackId={}, rating={}, question={}", 
                feedbackId, request.getRating(), request.getQuestion());
            
            // 触发学习机制
            learnFromFeedback(request, feedbackId);
            
            return feedbackId;
            
        } catch (Exception e) {
            log.error("提交SQL反馈失败", e);
            throw new RuntimeException("提交反馈失败: " + e.getMessage());
        }
    }
    
    /**
     * 从反馈中学习
     */
    private void learnFromFeedback(SQLFeedbackRequest request, Long feedbackId) {
        if (ragKnowledgeBaseService == null) {
            log.debug("RAG服务未启用，跳过反馈学习");
            return;
        }
        
        try {
            // 如果有关联的知识库ID，更新其质量评分
            if (request.getKnowledgeId() != null) {
                float scoreChange = calculateScoreChange(request.getRating());
                ragKnowledgeBaseService.updateQualityScore(request.getKnowledgeId(), scoreChange);
                
                log.info("根据反馈调整知识库质量评分: knowledgeId={}, change={}", 
                    request.getKnowledgeId(), scoreChange);
            }
            
            // 低分反馈自动标记，供后续分析
            if (request.getRating() <= 2 && request.getFeedbackText() != null) {
                log.warn("低分反馈 [{}星]: question={}, reason={}", 
                    request.getRating(), request.getQuestion(), request.getFeedbackText());
                
                // TODO: 可以触发告警或人工审核流程
            }
            
        } catch (Exception e) {
            log.error("从反馈中学习失败: feedbackId={}", feedbackId, e);
        }
    }
    
    /**
     * 根据评分计算质量分数变化
     * 
     * @param rating 用户评分 1-5
     * @return 质量分数变化量 (-0.2 到 +0.2)
     */
    private float calculateScoreChange(int rating) {
        switch (rating) {
            case 5: return 0.2f;   // 很好：大幅提升
            case 4: return 0.1f;   // 较好：小幅提升
            case 3: return 0.0f;   // 一般：不变
            case 2: return -0.1f;  // 较差：小幅降低
            case 1: return -0.2f;  // 很差：大幅降低
            default: return 0.0f;
        }
    }
    
    /**
     * 获取反馈统计
     */
    public Map<String, Object> getFeedbackStats() {
        try {
            // 总反馈数
            Integer totalCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM rag_feedback", Integer.class);
            
            // 平均评分
            Double avgRating = jdbcTemplate.queryForObject(
                "SELECT AVG(rating) FROM rag_feedback", Double.class);
            
            // 各评分分布
            List<Map<String, Object>> ratingDistribution = jdbcTemplate.queryForList(
                "SELECT rating, COUNT(*) as count FROM rag_feedback GROUP BY rating ORDER BY rating");
            
            // 最近7天反馈趋势
            List<Map<String, Object>> recentTrend = jdbcTemplate.queryForList(
                "SELECT DATE(created_at) as date, AVG(rating) as avg_rating, COUNT(*) as count " +
                "FROM rag_feedback " +
                "WHERE created_at >= DATE_SUB(NOW(), INTERVAL 7 DAY) " +
                "GROUP BY DATE(created_at) " +
                "ORDER BY date");
            
            return Map.of(
                "totalCount", totalCount != null ? totalCount : 0,
                "avgRating", avgRating != null ? String.format("%.2f", avgRating) : "0.00",
                "ratingDistribution", ratingDistribution,
                "recentTrend", recentTrend
            );
            
        } catch (Exception e) {
            log.error("获取反馈统计失败", e);
            return Map.of("error", e.getMessage());
        }
    }
    
    /**
     * 获取低分反馈列表（需要改进的SQL）
     */
    public List<Map<String, Object>> getLowRatingFeedbacks(int limit) {
        try {
            String sql = "SELECT id, question, generated_sql, rating, feedback_text, created_at " +
                        "FROM rag_feedback " +
                        "WHERE rating <= 2 " +
                        "ORDER BY created_at DESC " +
                        "LIMIT ?";
            
            return jdbcTemplate.queryForList(sql, limit);
            
        } catch (Exception e) {
            log.error("获取低分反馈失败", e);
            return List.of();
        }
    }
}
