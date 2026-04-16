package com.nl2sql.core.rag.provider;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * MySQL向量数据库提供者实现（降级方案）
 */
@Slf4j
public class MySqlVectorProvider implements VectorStoreProvider {
    
    private final JdbcTemplate jdbcTemplate;
    private boolean available = false;
    
    public MySqlVectorProvider(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }
    
    @PostConstruct
    public void init() {
        createTableIfNotExists();
        this.available = true;
        log.info("MySQL向量提供者初始化完成（作为降级方案）");
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
            log.debug("RAG知识库表初始化完成");
            
        } catch (Exception e) {
            log.error("RAG知识库表初始化失败", e);
            this.available = false;
        }
    }
    
    @Override
    public String getName() {
        return "mysql";
    }
    
    @Override
    public boolean isAvailable() {
        return available;
    }
    
    @Override
    public String addKnowledge(String question, String answer, String sqlExample, String category) {
        if (!isAvailable()) {
            log.debug("MySQL不可用，跳过知识添加");
            return null;
        }
        
        try {
            String sql = "INSERT INTO rag_knowledge (question, answer, sql_example, category, created_at) VALUES (?, ?, ?, ?, NOW())";
            jdbcTemplate.update(sql, question, answer, sqlExample, category);
            
            String id = "mysql-" + System.currentTimeMillis();
            log.debug("添加知识到MySQL: question={}, id={}", question, id);
            return id;
            
        } catch (Exception e) {
            log.error("添加知识到MySQL失败", e);
            return null;
        }
    }
    
    @Override
    public List<VectorSearchResult> searchSimilar(String question, int maxResults, double minScore) {
        if (!isAvailable()) {
            log.debug("MySQL不可用，跳过搜索");
            return new ArrayList<>();
        }
        
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
            
            // 转换为VectorSearchResult
            List<VectorSearchResult> results = new ArrayList<>();
            for (Object[] row : rows) {
                VectorSearchResult result = new VectorSearchResult();
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
    
    @Override
    public void clearAll() {
        if (!isAvailable()) {
            return;
        }
        
        try {
            String sql = "TRUNCATE TABLE rag_knowledge";
            jdbcTemplate.execute(sql);
            log.info("清空MySQL RAG知识库");
        } catch (Exception e) {
            log.error("清空MySQL失败", e);
        }
    }
    
    @Override
    public Map<String, Object> getConfig() {
        Map<String, Object> config = new HashMap<>();
        config.put("type", "mysql");
        config.put("available", available);
        config.put("description", "MySQL全文检索（降级方案）");
        return config;
    }
}
