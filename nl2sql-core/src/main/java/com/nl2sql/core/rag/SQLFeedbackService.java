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
    
    @Autowired(required = false)
    private FeedbackLearningService feedbackLearningService;
    
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
                
                // ✅ 新增：触发Agent学习修正
                if (feedbackLearningService != null) {
                    try {
                        feedbackLearningService.processLowRatingFeedback(
                            feedbackId, 
                            request.getRating(), 
                            request.getQuestion(), 
                            request.getGeneratedSql(), 
                            request.getFeedbackText()
                        );
                    } catch (Exception e) {
                        log.error("[反馈学习] 处理失败: feedbackId={}", feedbackId, e);
                    }
                }
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
    
    /**
     * ✅ 新增：自动给上次未评分的结果赋予默认评分（3星）
     * 当用户发起新查询时，如果上次查询没有评分，则认为用户认可，给3星
     * 
     * @param sessionId 会话ID
     * @return 是否成功应用默认评分
     */
    public boolean applyDefaultRating(String sessionId) {
        if (sessionId == null || sessionId.trim().isEmpty()) {
            return false;
        }
        
        try {
            // 查找该会话中最近一次生成的SQL，且没有对应的反馈记录
            String checkSql = "SELECT q.question, q.generated_sql, q.executed_sql, q.execution_success " +
                             "FROM nl2sql_query_log q " +
                             "LEFT JOIN rag_feedback f ON q.session_id = f.session_id " +
                             "AND q.generated_sql = f.generated_sql " +
                             "WHERE q.session_id = ? " +
                             "AND f.id IS NULL " +
                             "ORDER BY q.created_at DESC " +
                             "LIMIT 1";
            
            List<Map<String, Object>> queries = jdbcTemplate.queryForList(checkSql, sessionId);
            
            if (queries.isEmpty()) {
                log.debug("[默认评分] 会话 {} 没有未评分的查询", sessionId);
                return false;
            }
            
            Map<String, Object> lastQuery = queries.get(0);
            String question = (String) lastQuery.get("question");
            String generatedSql = (String) lastQuery.get("generated_sql");
            String executedSql = (String) lastQuery.get("executed_sql");
            Boolean executionSuccess = (Boolean) lastQuery.get("execution_success");
            
            // 插入默认3星评分
            String insertSql = "INSERT INTO rag_feedback (" +
                              "knowledge_id, user_id, session_id, rating, feedback_text, " +
                              "question, generated_sql, executed_sql, execution_success, " +
                              "ip_address, user_agent, created_at" +
                              ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW())";
            
            jdbcTemplate.update(insertSql,
                null,  // knowledgeId
                null,  // userId
                sessionId,
                3,     // 默认3星
                "用户未评分，默认为中等评价",  // 自动填充的反馈文本
                question,
                generatedSql,
                executedSql,
                executionSuccess != null ? executionSuccess : false,
                "system",  // 系统自动评分
                "auto-rating"
            );
            
            log.info("[默认评分] 会话 {} 自动赋予3星评分: question={}", sessionId, question);
            return true;
            
        } catch (Exception e) {
            log.error("[默认评分] 应用默认评分失败: sessionId={}", sessionId, e);
            return false;
        }
    }
}
