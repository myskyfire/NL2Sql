package com.nl2sql.core.rag;

import com.nl2sql.core.cache.QueryCacheService;
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
    
    @Autowired(required = false)
    private QueryCacheService queryCacheService;
    
    @Autowired(required = false)
    private com.nl2sql.core.cache.MetadataCacheService metadataCacheService;
    
    /**
     * 提交SQL反馈
     */
    public Long submitFeedback(SQLFeedbackRequest request, String ipAddress, String userAgent) {
        try {
            log.info("[SQL反馈] 收到请求: rating={}, question={}", 
                request.getRating(), request.getQuestion());
            
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
                request.getKnowledgeId() != null ? request.getKnowledgeId() : 0L, // knowledge_id默认为0
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
            
            log.info("[SQL反馈] 已保存到rag_feedback: feedbackId={}", feedbackId);
            
            // 触发学习机制（包含高分同步）
            learnFromFeedback(request, feedbackId);
            
            return feedbackId;
            
        } catch (IllegalArgumentException e) {
            log.warn("[SQL反馈] 参数错误: {}", e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("[SQL反馈] 提交失败: question={}", request.getQuestion(), e);
            throw new RuntimeException("提交反馈失败: " + e.getMessage(), e);
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
            // ✅ 新增：高分反馈（4-5星）自动同步到rag_knowledge_base + 注入L1/L2表缓存
            if (request.getRating() >= 4) {
                syncHighRatingFeedbackToKnowledge(request);
                injectTableSelectionToCache(request);  // ✅ 新增：注入表选择缓存
            }
            
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
                
                // ✅ 关键：清除该问题的 SQL 缓存（避免下次仍返回错误 SQL）
                if (queryCacheService != null) {
                    try {
                        queryCacheService.invalidateCache(request.getQuestion());
                        log.info("[反馈处理] ✅ 已清除 SQL 缓存: question={}", request.getQuestion());
                    } catch (Exception e) {
                        log.warn("[反馈处理] 清除缓存失败", e);
                    }
                }
                
                // ✅ 新增：触发Agent学习修正
                if (feedbackLearningService != null) {
                    try {
                        feedbackLearningService.processLowRatingFeedback(
                            feedbackId, 
                            request.getRating().intValue(),  // Integer -> int
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
     * ✅ 新增：将高分反馈同步到rag_knowledge_base表
     * 
     * @param request 反馈请求
     */
    private void syncHighRatingFeedbackToKnowledge(SQLFeedbackRequest request) {
        try {
            String question = request.getQuestion();
            String sql = request.getGeneratedSql();
            int rating = request.getRating();
            
            log.info("[反馈同步] 开始处理: rating={}, question={}", rating, question);
            
            // 检查是否已存在相似问题（避免重复）
            List<RagKnowledgeBaseService.KnowledgeItem> existingItems = 
                ragKnowledgeBaseService.searchSimilarQuestions(question, 1);
            
            if (!existingItems.isEmpty()) {
                double similarity = existingItems.get(0).getRelevance() != null ? 
                    existingItems.get(0).getRelevance() : 0.0;
                
                log.info("[反馈同步] 找到相似示例: similarity={}", similarity);
                
                // 如果相似度>0.9，认为已存在，只更新质量评分
                if (similarity > 0.9) {
                    Long existingId = existingItems.get(0).getId();
                    float scoreChange = calculateScoreChange(rating);
                    ragKnowledgeBaseService.updateQualityScore(existingId, scoreChange);
                    
                    log.info("[反馈同步] ✅ 发现相似示例，更新质量评分: id={}, change={}", 
                        existingId, scoreChange);
                    return;
                }
            }
            
            // 不存在则新增
            // 根据评分计算初始质量分：5星=1.0, 4星=0.8
            float qualityScore = rating == 5 ? 1.0f : 0.8f;
            
            // 自动分类（简单规则）
            String category = categorizeQuestion(question);
            
            log.info("[反馈同步] 准备新增: category={}, qualityScore={}", category, qualityScore);
            
            // 保存到rag_knowledge_base（会自动同步到Chroma）
            Long knowledgeId = ragKnowledgeBaseService.saveQAPair(
                question,
                "",  // answer暂时为空
                sql,
                category,
                qualityScore
            );
            
            log.info("[反馈同步] ✅ 高分反馈已同步到知识库: id={}, rating={}, question={}", 
                knowledgeId, rating, question);
            
        } catch (Exception e) {
            // ⚠️ 关键：同步失败不影响主流程，只记录日志
            log.error("[反馈同步] ❌ 同步失败（不影响反馈提交）: question={}", 
                request.getQuestion(), e);
        }
    }
    
    /**
     * ✅ 新增：将高分反馈的表选择注入到L1/L2缓存
     * 核心思路：用户评分>=4分 → 提取SQL中的表 → 写入元数据缓存
     * 下次相似查询时，直接从缓存获取表，跳过L3向量检索和LLM选表
     */
    private void injectTableSelectionToCache(SQLFeedbackRequest request) {
        if (metadataCacheService == null) {
            log.debug("[表缓存注入] MetadataCacheService未注入，跳过");
            return;
        }
        
        try {
            String question = request.getQuestion();
            String sql = request.getGeneratedSql();
            int rating = request.getRating();
            
            // 1. 从SQL中提取实际使用的表
            java.util.Set<String> usedTables = extractTablesFromSQL(sql);
            if (usedTables.isEmpty()) {
                log.warn("[表缓存注入] 无法从SQL提取表: sql={}", sql);
                return;
            }
            
            log.info("[表缓存注入] 开始处理: rating={}, question={}, tables={}", 
                rating, question, usedTables);
            
            // 2. 获取datasourceId（从nl2sql_query_log查询）
            Long datasourceId = null;
            if (request.getSessionId() != null) {
                try {
                    List<Map<String, Object>> logs = jdbcTemplate.queryForList(
                        "SELECT datasource_id FROM nl2sql_query_log WHERE session_id = ? ORDER BY created_at DESC LIMIT 1",
                        request.getSessionId()
                    );
                    if (!logs.isEmpty()) {
                        datasourceId = ((Number) logs.get(0).get("datasource_id")).longValue();
                    }
                } catch (Exception e) {
                    log.warn("[表缓存注入] 查询datasourceId失败", e);
                }
            }
            
            if (datasourceId == null) {
                log.warn("[表缓存注入] 无法获取datasourceId，跳过");
                return;
            }
            
            // 3. 注入到L2模糊向量缓存（归一化key）
            String normalizedQuery = normalizeQuery(question);
            java.util.List<String> tableList = new java.util.ArrayList<>(usedTables);
            metadataCacheService.putFuzzyVectorRetrieval(normalizedQuery, tableList);
            
            log.info("[表缓存注入] ✅ 已注入L2缓存: question='{}', normalized='{}', tables={}", 
                question, normalizedQuery, tableList);
            
            // 4. 注入到L3语义索引（供Jaccard匹配）
            metadataCacheService.recordQueryToSemanticIndex(datasourceId, question, tableList, rating);
            
            log.info("[表缓存注入] ✅ 已注入L3语义索引: datasourceId={}, tables={}", 
                datasourceId, tableList);
            
        } catch (Exception e) {
            // ⚠️ 关键：注入失败不影响主流程，只记录日志
            log.error("[表缓存注入] ❌ 注入失败（不影响反馈提交）: question={}", 
                request.getQuestion(), e);
        }
    }
    
    /**
     * ✅ 从SQL中提取表名（使用JSqlParser）
     */
    private java.util.Set<String> extractTablesFromSQL(String sql) {
        java.util.Set<String> tables = new java.util.HashSet<>();
        
        if (sql == null || sql.trim().isEmpty()) {
            return tables;
        }
        
        try {
            net.sf.jsqlparser.statement.Statement statement = 
                net.sf.jsqlparser.parser.CCJSqlParserUtil.parse(sql);
            
            if (!(statement instanceof net.sf.jsqlparser.statement.select.Select)) {
                return tables; // 非SELECT语句
            }
            
            net.sf.jsqlparser.statement.select.Select selectStmt = 
                (net.sf.jsqlparser.statement.select.Select) statement;
            net.sf.jsqlparser.statement.select.SelectBody selectBody = selectStmt.getSelectBody();
            
            if (!(selectBody instanceof net.sf.jsqlparser.statement.select.PlainSelect)) {
                return tables;
            }
            
            net.sf.jsqlparser.statement.select.PlainSelect plainSelect = 
                (net.sf.jsqlparser.statement.select.PlainSelect) selectBody;
            
            // FROM表
            if (plainSelect.getFromItem() != null) {
                String fromTable = plainSelect.getFromItem().toString().toLowerCase();
                // 去除别名
                if (fromTable.contains(" ")) {
                    fromTable = fromTable.split("\\s+")[0];
                }
                tables.add(fromTable);
            }
            
            // JOIN表
            if (plainSelect.getJoins() != null) {
                for (net.sf.jsqlparser.statement.select.Join join : plainSelect.getJoins()) {
                    if (join.getRightItem() != null) {
                        String joinTable = join.getRightItem().toString().toLowerCase();
                        if (joinTable.contains(" ")) {
                            joinTable = joinTable.split("\\s+")[0];
                        }
                        tables.add(joinTable);
                    }
                }
            }
            
            log.debug("[表缓存注入] 从SQL提取到表: {}", tables);
            
        } catch (Exception e) {
            log.warn("[表缓存注入] SQL解析失败: {}", e.getMessage());
        }
        
        return tables;
    }
    
    /**
     * ✅ 归一化查询文本（用于缓存key）
     * 去除时间、数字等变量，保留语义结构
     */
    private String normalizeQuery(String query) {
        if (query == null) return "";
        
        // 替换数字为占位符
        String normalized = query.replaceAll("\\d+", "<NUM>");
        
        // 替换具体日期为占位符
        normalized = normalized.replaceAll("\\d{4}[-/]\\d{1,2}[-/]\\d{1,2}", "<DATE>");
        
        // 替换相对时间为占位符
        normalized = normalized.replaceAll("最近\\d+天", "最近<NUM>天");
        normalized = normalized.replaceAll("过去\\d+天", "过去<NUM>天");
        
        // 去除多余空格
        normalized = normalized.trim().replaceAll("\\s+", " ");
        
        return normalized;
    }
    
    /**
     * 根据问题自动分类
     */
    private String categorizeQuestion(String question) {
        if (question == null) return "其他";
        
        String lower = question.toLowerCase();
        
        if (lower.contains("统计") || lower.contains("汇总") || lower.contains("平均") || 
            lower.contains("合计") || lower.contains("总数")) {
            return "统计查询";
        }
        
        if (lower.contains("查询") || lower.contains("查找") || lower.contains("显示")) {
            return "明细查询";
        }
        
        if (lower.contains("排序") || lower.contains("排名") || lower.contains("最")) {
            return "排序查询";
        }
        
        return "其他";
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
                0L,    // ✅ knowledgeId: 0 表示无关联知识库
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
