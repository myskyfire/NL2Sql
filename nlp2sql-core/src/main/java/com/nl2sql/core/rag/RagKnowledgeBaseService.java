package com.nl2sql.core.rag;

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
    private final ChromaVectorService chromaService;
    private final MySqlVectorService mysqlService;
    private static final double SIMILARITY_THRESHOLD = 0.85;
    private static final int MAX_EXAMPLES = 3;
    
    public RagKnowledgeBaseService(JdbcTemplate jdbcTemplate,
                                   ChromaVectorService chromaService,
                                   MySqlVectorService mysqlService) {
        this.jdbcTemplate = jdbcTemplate;
        this.chromaService = chromaService;
        this.mysqlService = mysqlService;
    }
    

    
    /**
     * 保存问答对到知识库（三模式：Chroma优先）
     */
    public Long saveQAPair(String question, String answer, String sqlExample, 
                          String category, float qualityScore) {
        // 1. 保存到MySQL（持久化）
        String sql = "INSERT INTO rag_knowledge_base (question, answer, sql_example, category, quality_score, usage_count, created_at) " +
                    "VALUES (?, ?, ?, ?, ?, 0, NOW())";
        
        jdbcTemplate.update(sql, question, answer, sqlExample, category, qualityScore);
        Long id = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        
        // 2. 同步到Chroma向量数据库（优先）
        if (chromaService != null) {
            try {
                chromaService.addKnowledge(question, answer, sqlExample, category);
                log.info("RAG知识已同步到Chroma: id={}", id);
            } catch (Exception e) {
                log.warn("Chroma同步失败，降级到MySQL向量: {}", e.getMessage());
                // 降级到MySQL向量服务
                if (mysqlService != null) {
                    mysqlService.addKnowledge(question, answer, sqlExample, category);
                }
            }
        } else if (mysqlService != null) {
            // 无Chroma时直接使用MySQL向量服务
            mysqlService.addKnowledge(question, answer, sqlExample, category);
            log.info("RAG知识已同步到MySQL: id={}", id);
        }
        
        log.info("保存RAG问答对: id={}, question={}, quality={}", id, question, qualityScore);
        return id;
    }
    
    /**
     * 检索相似的问答对（Chroma优先策略）
     */
    public List<KnowledgeItem> searchSimilarQuestions(String question, int maxResults) {
        // 优先使用Chroma向量数据库
        if (chromaService != null) {
            try {
                List<ChromaVectorService.RagResult> chromaResults = 
                    chromaService.searchSimilar(question, maxResults, SIMILARITY_THRESHOLD);
                
                if (!chromaResults.isEmpty()) {
                    log.info("RAG检索成功(Chroma向量): question={}, found={} items", question, chromaResults.size());
                    return convertFromChromaResults(chromaResults);
                }
            } catch (Exception e) {
                log.warn("Chroma向量搜索失败，降级到MySQL向量: {}", e.getMessage());
            }
        }
        
        // 降级到MySQL向量服务
        if (mysqlService != null) {
            try {
                List<MySqlVectorService.RagResult> mysqlResults = 
                    mysqlService.searchSimilar(question, maxResults, SIMILARITY_THRESHOLD);
                
                if (!mysqlResults.isEmpty()) {
                    log.info("RAG检索成功(MySQL向量): question={}, found={} items", question, mysqlResults.size());
                    return convertFromMysqlResults(mysqlResults);
                }
            } catch (Exception e) {
                log.warn("MySQL向量搜索失败，降级到全文检索: {}", e.getMessage());
            }
        }
        
        // 最终降级到MySQL全文检索
        return searchByMySQL(question, maxResults);
    }
    
    /**
     * MySQL全文检索（降级方案）
     */
    private List<KnowledgeItem> searchByMySQL(String question, int maxResults) {
        String sql = "SELECT id, question, answer, sql_example, category, quality_score, usage_count, " +
                    "MATCH(question) AGAINST(? IN NATURAL LANGUAGE MODE) as relevance " +
                    "FROM rag_knowledge_base " +
                    "WHERE MATCH(question) AGAINST(? IN NATURAL LANGUAGE MODE) " +
                    "AND quality_score >= ? " +
                    "ORDER BY relevance DESC, quality_score DESC " +
                    "LIMIT ?";
        
        List<KnowledgeItem> results = jdbcTemplate.query(
            sql, 
            new KnowledgeRowMapper(),
            question, question, SIMILARITY_THRESHOLD, maxResults
        );
        
        if (!results.isEmpty()) {
            log.info("RAG检索成功(MySQL): question={}, found={} items", question, results.size());
        } else {
            log.debug("RAG未找到相似问题: question={}", question);
        }
        
        return results;
    }
    
    /**
     * 转换Chroma结果为KnowledgeItem
     */
    private List<KnowledgeItem> convertFromChromaResults(List<ChromaVectorService.RagResult> chromaResults) {
        List<KnowledgeItem> items = new java.util.ArrayList<>();
        for (ChromaVectorService.RagResult result : chromaResults) {
            KnowledgeItem item = new KnowledgeItem();
            item.setQuestion(result.getQuestion());
            item.setAnswer(result.getAnswer());
            item.setSqlExample(result.getSqlExample());
            item.setCategory(result.getCategory());
            item.setRelevance(result.getScore());
            item.setQualityScore(0.9f); // Chroma结果默认高质量
            item.setUsageCount(0);
            items.add(item);
        }
        return items;
    }
    
    /**
     * 转换MySQL结果为KnowledgeItem
     */
    private List<KnowledgeItem> convertFromMysqlResults(List<MySqlVectorService.RagResult> mysqlResults) {
        List<KnowledgeItem> items = new java.util.ArrayList<>();
        for (MySqlVectorService.RagResult result : mysqlResults) {
            KnowledgeItem item = new KnowledgeItem();
            item.setQuestion(result.getQuestion());
            item.setAnswer(result.getAnswer());
            item.setSqlExample(result.getSqlExample());
            item.setCategory(result.getCategory());
            item.setRelevance(result.getScore());
            item.setQualityScore(0.9f); // MySQL结果默认高质量
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
