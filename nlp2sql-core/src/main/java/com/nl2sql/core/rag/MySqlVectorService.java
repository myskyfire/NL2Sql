package com.nl2sql.core.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;

/**
 * MySQL向量服务 - Chroma不可用时的降级方案
 */
@Slf4j
@Service
public class MySqlVectorService {
    
    private final JdbcTemplate jdbcTemplate;
    
    public MySqlVectorService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }
    
    @PostConstruct
    public void init() {
        log.info("Chroma未配置，使用MySQL全文检索作为RAG后端");
        createTableIfNotExists();
    }
    
    /**
     * 创建表（如果不存在）
     */
    private void createTableIfNotExists() {
        try {
            String createTableSQL = "CREATE TABLE IF NOT EXISTS rag_knowledge (" +
                "id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID'," +
                "question TEXT NOT NULL COMMENT '问题'," +
                "answer TEXT COMMENT '答案'," +
                "sql_example TEXT COMMENT 'SQL示例'," +
                "category VARCHAR(100) COMMENT '分类'," +
                "created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间'," +
                "INDEX idx_question (question(255))," +
                "INDEX idx_category (category)," +
                "INDEX idx_created (created_at)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='RAG知识库表'";
            
            jdbcTemplate.execute(createTableSQL);
            log.info("RAG知识库表初始化完成");
            
        } catch (Exception e) {
            log.error("RAG知识库表初始化失败", e);
        }
    }
    
    /**
     * 添加知识到MySQL
     */
    public String addKnowledge(String question, String answer, String sqlExample, String category) {
        try {
            String sql = "INSERT INTO rag_knowledge (question, answer, sql_example, category, created_at) VALUES (?, ?, ?, ?, NOW())";
            jdbcTemplate.update(sql, question, answer, sqlExample, category);
            
            log.debug("添加知识到MySQL: question={}", question);
            return "mysql-" + System.currentTimeMillis();
            
        } catch (Exception e) {
            log.error("添加知识到MySQL失败", e);
            return null;
        }
    }
    
    /**
     * 使用MySQL全文检索搜索相似知识
     */
    public List<RagResult> searchSimilar(String question, int maxResults, double minScore) {
        try {
            // 使用LIKE进行模糊匹配（简化版）
            String sql = "SELECT question, answer, sql_example, category FROM rag_knowledge " +
                        "WHERE question LIKE ? OR answer LIKE ? " +
                        "ORDER BY created_at DESC LIMIT ?";
            
            String pattern = "%" + question + "%";
            List<Object[]> rows = jdbcTemplate.query(sql, 
                (rs, rowNum) -> new Object[]{
                    rs.getString("question"),
                    rs.getString("answer"),
                    rs.getString("sql_example"),
                    rs.getString("category")
                },
                pattern, pattern, maxResults
            );
            
            // 转换为RagResult
            List<RagResult> results = new ArrayList<>();
            for (Object[] row : rows) {
                RagResult result = new RagResult();
                result.setQuestion((String) row[0]);
                result.setAnswer((String) row[1]);
                result.setSqlExample((String) row[2]);
                result.setCategory((String) row[3]);
                result.setScore(0.5); // MySQL无法提供相似度分数，使用默认值
                results.add(result);
            }
            
            log.debug("MySQL搜索结果: {} 条", results.size());
            return results;
            
        } catch (Exception e) {
            log.error("MySQL搜索失败", e);
            return new ArrayList<>();
        }
    }
    
    @lombok.Data
    public static class RagResult {
        private String question;
        private String answer;
        private String sqlExample;
        private String category;
        private double score;
    }
}
