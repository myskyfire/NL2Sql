package com.nl2sql.core.rag;

import com.nl2sql.core.rag.dto.BatchImportResult;
import com.nl2sql.core.rag.dto.QAImportRequest;
import com.nl2sql.core.rag.provider.VectorSearchResult;
import com.nl2sql.core.rag.provider.VectorStoreManager;
import com.nl2sql.core.rag.provider.VectorStoreProvider;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
public class RagKnowledgeBaseService {
    
    private final JdbcTemplate jdbcTemplate;
    private final VectorStoreManager vectorStoreManager;
    private static final double SIMILARITY_THRESHOLD = 0.85;
    private static final int MAX_EXAMPLES = 3;
    
    public RagKnowledgeBaseService(JdbcTemplate jdbcTemplate,
                                   VectorStoreManager vectorStoreManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.vectorStoreManager = vectorStoreManager;
    }
    
    /**
     * 保存问答对到知识库（多提供者架构）
     */
    public Long saveQAPair(String question, String answer, String sqlExample, 
                          String category, float qualityScore) {
        // 1. 保存到MySQL（持久化）
        String sql = "INSERT INTO rag_knowledge_base (question, answer, sql_example, category, quality_score, usage_count, created_at) " +
                    "VALUES (?, ?, ?, ?, ?, 0, NOW())";
        
        jdbcTemplate.update(sql, question, answer, sqlExample, category, qualityScore);
        Long id = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        
        // 2. 同步到向量数据库（使用活跃提供者）
        VectorStoreProvider activeProvider = vectorStoreManager.getActiveProvider();
        if (activeProvider != null) {
            try {
                String docId = activeProvider.addKnowledge(question, answer, sqlExample, category);
                log.info("RAG知识已同步到{}: id={}, docId={}", activeProvider.getName(), id, docId);
            } catch (Exception e) {
                log.warn("向量数据库同步失败({}): {}", activeProvider.getName(), e.getMessage());
            }
        } else {
            log.warn("没有可用的向量数据库提供者，仅保存到MySQL");
        }
        
        log.info("保存RAG问答对: id={}, question={}, quality={}", id, question, qualityScore);
        return id;
    }
    
    /**
     * 检索相似的问答对（多提供者架构，自动降级）
     */
    public List<KnowledgeItem> searchSimilarQuestions(String question, int maxResults) {
        // 使用活跃的向量数据库提供者
        VectorStoreProvider activeProvider = vectorStoreManager.getActiveProvider();
        
        if (activeProvider != null) {
            try {
                List<VectorSearchResult> providerResults = 
                    activeProvider.searchSimilar(question, maxResults, SIMILARITY_THRESHOLD);
                
                if (!providerResults.isEmpty()) {
                    log.info("RAG检索成功({}): question={}, found={} items", 
                        activeProvider.getName(), question, providerResults.size());
                    return convertFromProviderResults(providerResults);
                }
            } catch (Exception e) {
                log.warn("{}向量搜索失败，降级到MySQL全文检索: {}", 
                    activeProvider.getName(), e.getMessage());
            }
        } else {
            log.debug("没有可用的向量数据库提供者，直接使用MySQL全文检索");
        }
        
        // 降级到MySQL全文检索
        return searchByMySQL(question, maxResults);
    }
    
    /**
     * MySQL全文检索（降级方案）
     */
    private List<KnowledgeItem> searchByMySQL(String question, int maxResults) {
        // ✅ 关键优化：结合用户反馈评分调整排序权重
        String sql = "SELECT k.id, k.question, k.answer, k.sql_example, k.category, k.quality_score, k.usage_count, " +
                    "MATCH(k.question) AGAINST(? IN NATURAL LANGUAGE MODE) as relevance, " +
                    "COALESCE(avg_feedback.rating, 3.0) as avg_rating " +  // 平均评分，默认3.0
                    "FROM rag_knowledge_base k " +
                    "LEFT JOIN (" +
                    "    SELECT knowledge_id, AVG(rating) as rating " +
                    "    FROM rag_feedback " +
                    "    WHERE rating >= 3 " +  // 只统计正面反馈
                    "    GROUP BY knowledge_id" +
                    ") avg_feedback ON k.id = avg_feedback.knowledge_id " +
                    "WHERE MATCH(k.question) AGAINST(? IN NATURAL LANGUAGE MODE) " +
                    "AND k.quality_score >= ? " +
                    "ORDER BY (relevance * 0.6 + (avg_rating / 5.0) * 0.4) DESC, k.quality_score DESC " +  // 综合评分
                    "LIMIT ?";
        
        List<KnowledgeItem> results = jdbcTemplate.query(
            sql, 
            new KnowledgeRowMapper(),
            question, question, SIMILARITY_THRESHOLD, maxResults
        );
        
        if (!results.isEmpty()) {
            log.info("RAG检索成功(MySQL+Feedback): question={}, found={} items", question, results.size());
        } else {
            log.debug("RAG未找到相似问题: question={}", question);
        }
        
        return results;
    }
    
    /**
     * 转换Provider结果为KnowledgeItem
     */
    private List<KnowledgeItem> convertFromProviderResults(List<VectorSearchResult> providerResults) {
        List<KnowledgeItem> items = new java.util.ArrayList<>();
        for (VectorSearchResult result : providerResults) {
            KnowledgeItem item = new KnowledgeItem();
            item.setQuestion(result.getQuestion());
            item.setAnswer(result.getAnswer());
            item.setSqlExample(result.getSqlExample());
            item.setCategory(result.getCategory());
            item.setRelevance(result.getScore());
            item.setQualityScore(0.9f); // Provider结果默认高质量
            item.setUsageCount(0);
            items.add(item);
        }
        return items;
    }
    
    /**
     * 记录使用（增加使用次数）
     */
    public void recordUsage(Long knowledgeId) {
        String sql = "UPDATE rag_knowledge_base SET usage_count = usage_count + 1 WHERE id = ?";
        jdbcTemplate.update(sql, knowledgeId);
    }
    
    /**
     * 更新质量评分（基于用户反馈）
     */
    public void updateQualityScore(Long knowledgeId, float scoreChange) {
        String sql = "UPDATE rag_knowledge_base SET quality_score = LEAST(1.0, GREATEST(0.0, quality_score + ?)) WHERE id = ?";
        jdbcTemplate.update(sql, scoreChange, knowledgeId);
        log.debug("更新RAG质量评分: id={}, change={}", knowledgeId, scoreChange);
    }
    
    /**
     * 获取高质量样本（用于few-shot学习）
     */
    public List<KnowledgeItem> getHighQualitySamples(String category, int limit) {
        String sql = "SELECT id, question, answer, sql_example, category, quality_score, usage_count " +
                    "FROM rag_knowledge_base " +
                    "WHERE quality_score >= 0.9";
        
        if (category != null && !category.isEmpty()) {
            sql += " AND category = ?";
        }
        
        sql += " ORDER BY usage_count DESC LIMIT ?";
        
        Object[] params = category != null && !category.isEmpty() ? 
            new Object[]{category, limit} : 
            new Object[]{limit};
        
        return jdbcTemplate.query(sql, new KnowledgeRowMapper(), params);
    }
    
    /**
     * 删除低质量样本
     */
    public void removeLowQualitySamples(float threshold) {
        String sql = "DELETE FROM rag_knowledge_base WHERE quality_score < ?";
        int deleted = jdbcTemplate.update(sql, threshold);
        log.info("清理低质量RAG样本: threshold={}, deleted={}", threshold, deleted);
    }
    
    /**
     * 批量导入QA对（用于手动录入生产环境SQL）
     */
    public BatchImportResult batchImportQAPairs(List<QAImportRequest> requests) {
        BatchImportResult result = new BatchImportResult();
        
        if (requests == null || requests.isEmpty()) {
            log.warn("批量导入请求为空");
            return result;
        }
        
        log.info("开始批量导入QA对: count={}", requests.size());
        
        for (int i = 0; i < requests.size(); i++) {
            QAImportRequest request = requests.get(i);
            try {
                // 参数校验
                if (request.getQuestion() == null || request.getQuestion().trim().isEmpty()) {
                    result.addFailure("第" + (i + 1) + "条：问题不能为空");
                    continue;
                }
                if (request.getSql() == null || request.getSql().trim().isEmpty()) {
                    result.addFailure("第" + (i + 1) + "条：SQL不能为空");
                    continue;
                }
                
                // 生成AI回答（如果未提供）
                String answer = request.getAnswer();
                if (answer == null || answer.trim().isEmpty()) {
                    answer = generateAnswerTemplate(request.getQuestion(), request.getSql());
                }
                
                // 设置默认值
                String category = request.getCategory() != null ? request.getCategory() : "manual_imported";
                float qualityScore = request.getQualityScore() != null ? request.getQualityScore() : 0.9f;
                
                // 保存QA对
                Long id = saveQAPair(
                    request.getQuestion(),
                    answer,
                    request.getSql(),
                    category,
                    qualityScore
                );
                
                result.addSuccess();
                log.debug("导入成功 [{}/{}]: id={}, question={}", 
                    i + 1, requests.size(), id, request.getQuestion());
                
            } catch (Exception e) {
                String errorMsg = String.format("第%d条导入失败: %s", i + 1, e.getMessage());
                result.addFailure(errorMsg);
                log.error(errorMsg, e);
            }
        }
        
        log.info("批量导入完成: success={}, failed={}", 
            result.getSuccessCount(), result.getFailedCount());
        
        return result;
    }
    
    /**
     * 生成AI回答模板
     */
    private String generateAnswerTemplate(String question, String sql) {
        return String.format(
            "根据您的查询「%s」，系统生成了相应的 SQL 并成功执行。\n" +
            "您可以参考以下 SQL 语句进行类似的数据查询：\n\n" +
            "```sql\n%s\n```\n\n" +
            "如需进一步分析或可视化，请告知具体需求。",
            question, sql
        );
    }
    
    // ==================== 数据模型 ====================
    
    @Data
    public static class KnowledgeItem {
        private Long id;
        private String question;
        private String answer;
        private String sqlExample;
        private String category;
        private Float qualityScore;
        private Integer usageCount;
        private Double relevance; // 相关度分数
    }
    
    // ==================== RowMapper ====================
    
    private static class KnowledgeRowMapper implements RowMapper<KnowledgeItem> {
        @Override
        public KnowledgeItem mapRow(ResultSet rs, int rowNum) throws SQLException {
            KnowledgeItem item = new KnowledgeItem();
            item.setId(rs.getLong("id"));
            item.setQuestion(rs.getString("question"));
            item.setAnswer(rs.getString("answer"));
            item.setSqlExample(rs.getString("sql_example"));
            item.setCategory(rs.getString("category"));
            item.setQualityScore(rs.getFloat("quality_score"));
            item.setUsageCount(rs.getInt("usage_count"));
            
            // relevance可能不存在（getHighQualitySamples查询）
            try {
                item.setRelevance(rs.getDouble("relevance"));
            } catch (SQLException e) {
                item.setRelevance(null);
            }
            
            return item;
        }
    }
}
