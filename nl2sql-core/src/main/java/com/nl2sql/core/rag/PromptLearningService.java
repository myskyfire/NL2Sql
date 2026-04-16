package com.nl2sql.core.rag;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

/**
 * Prompt自我学习服务
 * 负责Prompt版本管理、A/B测试、自动优化
 */
@Slf4j
@Service
public class PromptLearningService {
    
    @Autowired
    private JdbcTemplate jdbcTemplate;
    
    // ==================== Prompt版本管理 ====================
    
    /**
     * 创建Prompt版本
     */
    public Long createPromptVersion(String versionName, String promptType, 
                                    String promptContent, String description, 
                                    Long createdBy) {
        String sql = "INSERT INTO prompt_versions (version_name, prompt_type, prompt_content, description, created_by) " +
                    "VALUES (?, ?, ?, ?, ?)";
        
        jdbcTemplate.update(sql, versionName, promptType, promptContent, description, createdBy);
        Long id = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        
        log.info("创建Prompt版本: id={}, name={}, type={}", id, versionName, promptType);
        return id;
    }
    
    /**
     * 更新Prompt版本
     */
    public void updatePromptVersion(Long id, String promptContent, String description) {
        String sql = "UPDATE prompt_versions SET prompt_content = ?, description = ?, updated_at = NOW() WHERE id = ?";
        jdbcTemplate.update(sql, promptContent, description, id);
        
        log.info("更新Prompt版本: id={}", id);
    }
    
    /**
     * 激活Prompt版本
     */
    public void activatePromptVersion(Long id) {
        // 先取消同类型的其他默认版本
        PromptVersion current = getPromptVersion(id);
        if (current != null) {
            String resetSql = "UPDATE prompt_versions SET is_default = 0 WHERE prompt_type = ?";
            jdbcTemplate.update(resetSql, current.getPromptType());
        }
        
        // 激活当前版本
        String sql = "UPDATE prompt_versions SET status = 'ACTIVE', is_default = 1, activated_at = NOW() WHERE id = ?";
        jdbcTemplate.update(sql, id);
        
        log.info("激活Prompt版本: id={}", id);
    }
    
    /**
     * 获取指定类型的默认Prompt
     */
    public String getDefaultPrompt(String promptType) {
        String sql = "SELECT prompt_content FROM prompt_versions WHERE prompt_type = ? AND is_default = 1 LIMIT 1";
        
        try {
            return jdbcTemplate.queryForObject(sql, String.class, promptType);
        } catch (Exception e) {
            log.warn("未找到默认Prompt: type={}, 使用fallback", promptType);
            return getFallbackPrompt(promptType);
        }
    }
    
    /**
     * A/B测试：根据流量分配返回Prompt版本ID
     */
    public Long getPromptVersionForABTest(String promptType, String sessionId) {
        // 查找正在运行的A/B测试
        String testSql = "SELECT * FROM prompt_ab_tests WHERE prompt_type = ? AND status = 'RUNNING' LIMIT 1";
        
        List<Map<String, Object>> tests = jdbcTemplate.queryForList(testSql, promptType);
        if (tests.isEmpty()) {
            // 没有运行中的测试，返回默认版本
            return getDefaultVersionId(promptType);
        }
        
        Map<String, Object> test = tests.get(0);
        double trafficSplit = ((Number) test.get("traffic_split")).doubleValue();
        Long versionAId = ((Number) test.get("version_a_id")).longValue();
        Long versionBId = ((Number) test.get("version_b_id")).longValue();
        
        // 根据sessionId哈希决定使用哪个版本
        int hash = Math.abs(sessionId.hashCode() % 100);
        Long selectedVersionId = hash < trafficSplit ? versionAId : versionBId;
        
        // 记录使用日志
        recordUsage(selectedVersionId, sessionId, null, null, null, null, null);
        
        return selectedVersionId;
    }
    
    /**
     * 获取Prompt版本详情
     */
    public PromptVersion getPromptVersion(Long id) {
        String sql = "SELECT * FROM prompt_versions WHERE id = ?";
        
        try {
            return jdbcTemplate.queryForObject(sql, new PromptVersionRowMapper(), id);
        } catch (Exception e) {
            return null;
        }
    }
    
    /**
     * 列出所有Prompt版本
     */
    public List<PromptVersion> listPromptVersions(String promptType) {
        String sql = "SELECT * FROM prompt_versions";
        Object[] params;
        
        if (promptType != null && !promptType.isEmpty()) {
            sql += " WHERE prompt_type = ? ORDER BY created_at DESC";
            params = new Object[]{promptType};
        } else {
            sql += " ORDER BY prompt_type, created_at DESC";
            params = new Object[]{};
        }
        
        return jdbcTemplate.query(sql, new PromptVersionRowMapper(), params);
    }
    
    // ==================== A/B测试管理 ====================
    
    /**
     * 创建A/B测试
     */
    public Long createABTest(String testName, String promptType, Long versionAId, 
                            Long versionBId, Double trafficSplit, Integer minSamples, Long createdBy) {
        String sql = "INSERT INTO prompt_ab_tests (test_name, prompt_type, version_a_id, version_b_id, " +
                    "traffic_split, min_samples, created_by) VALUES (?, ?, ?, ?, ?, ?, ?)";
        
        jdbcTemplate.update(sql, testName, promptType, versionAId, versionBId, 
            trafficSplit != null ? trafficSplit : 50.0, 
            minSamples != null ? minSamples : 100, 
            createdBy);
        
        Long id = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        log.info("创建A/B测试: id={}, name={}, type={}", id, testName, promptType);
        return id;
    }
    
    /**
     * 停止A/B测试并确定优胜者
     */
    public void stopABTest(Long testId) {
        // 获取测试结果
        Map<String, Object> stats = getABTestStats(testId);
        
        Double ratingA = (Double) stats.get("versionAAvgRating");
        Double ratingB = (Double) stats.get("versionBAvgRating");
        
        Long winnerId = null;
        if (ratingA != null && ratingB != null) {
            winnerId = ratingA > ratingB ? 
                ((Number) stats.get("versionAId")).longValue() : 
                ((Number) stats.get("versionBId")).longValue();
        }
        
        // 更新测试状态
        String sql = "UPDATE prompt_ab_tests SET status = 'COMPLETED', ended_at = NOW(), winner_version_id = ? WHERE id = ?";
        jdbcTemplate.update(sql, winnerId, testId);
        
        // 如果确定了优胜者，自动激活
        if (winnerId != null) {
            activatePromptVersion(winnerId);
            log.info("A/B测试完成，自动激活优胜版本: testId={}, winnerId={}", testId, winnerId);
        }
        
        log.info("停止A/B测试: testId={}, winnerId={}", testId, winnerId);
    }
    
    /**
     * 获取A/B测试统计
     */
    public Map<String, Object> getABTestStats(Long testId) {
        String sql = "SELECT * FROM prompt_ab_tests WHERE id = ?";
        
        try {
            Map<String, Object> test = jdbcTemplate.queryForMap(sql, testId);
            
            // 计算实时统计数据
            Long versionAId = ((Number) test.get("version_a_id")).longValue();
            Long versionBId = ((Number) test.get("version_b_id")).longValue();
            
            Map<String, Object> statsA = getVersionUsageStats(versionAId);
            Map<String, Object> statsB = getVersionUsageStats(versionBId);
            
            Map<String, Object> result = new HashMap<>(test);
            result.putAll(statsA);
            result.putAll(statsB);
            
            return result;
        } catch (Exception e) {
            log.error("获取A/B测试统计失败", e);
            return Collections.emptyMap();
        }
    }
    
    /**
     * 列出所有A/B测试
     */
    public List<Map<String, Object>> listABTests(String status) {
        String sql = "SELECT * FROM prompt_ab_tests";
        
        if (status != null && !status.isEmpty()) {
            sql += " WHERE status = ? ORDER BY started_at DESC";
            return jdbcTemplate.queryForList(sql, status);
        } else {
            sql += " ORDER BY started_at DESC";
            return jdbcTemplate.queryForList(sql);
        }
    }
    
    // ==================== 自动优化引擎 ====================
    
    /**
     * 分析低分反馈，生成优化建议
     */
    public List<Map<String, Object>> analyzeLowRatingFeedbacks(String promptType, int limit) {
        String sql = "SELECT pul.question, pul.generated_sql, pul.rating, pul.feedback_text, " +
                    "pv.version_name, pv.prompt_content " +
                    "FROM prompt_usage_logs pul " +
                    "JOIN prompt_versions pv ON pul.prompt_version_id = pv.id " +
                    "WHERE pul.rating <= 2 AND pv.prompt_type = ? " +
                    "ORDER BY pul.created_at DESC LIMIT ?";
        
        List<Map<String, Object>> feedbacks = jdbcTemplate.queryForList(sql, promptType, limit);
        
        // 分析常见问题模式
        Map<String, Integer> issuePatterns = new HashMap<>();
        for (Map<String, Object> feedback : feedbacks) {
            String feedbackText = (String) feedback.get("feedback_text");
            if (feedbackText != null) {
                // 简单关键词匹配（可扩展为NLP分析）
                if (feedbackText.contains("性能") || feedbackText.contains("慢")) {
                    issuePatterns.merge("performance_issue", 1, Integer::sum);
                }
                if (feedbackText.contains("错误") || feedbackText.contains("语法")) {
                    issuePatterns.merge("syntax_error", 1, Integer::sum);
                }
                if (feedbackText.contains("缺少") || feedbackText.contains("遗漏")) {
                    issuePatterns.merge("missing_clause", 1, Integer::sum);
                }
            }
        }
        
        // 生成优化建议
        List<Map<String, Object>> suggestions = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : issuePatterns.entrySet()) {
            Map<String, Object> suggestion = new HashMap<>();
            suggestion.put("issueType", entry.getKey());
            suggestion.put("occurrenceCount", entry.getValue());
            suggestion.put("suggestion", generateOptimizationSuggestion(entry.getKey()));
            suggestions.add(suggestion);
        }
        
        return suggestions;
    }
    
    /**
     * 记录Prompt使用情况
     */
    public void recordUsage(Long promptVersionId, String sessionId, Long userId,
                           String question, String generatedSql, Integer rating, 
                           Boolean executionSuccess) {
        String sql = "INSERT INTO prompt_usage_logs (prompt_version_id, session_id, user_id, " +
                    "question, generated_sql, rating, execution_success) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?)";
        
        jdbcTemplate.update(sql, promptVersionId, sessionId, userId, 
            question, generatedSql, rating, executionSuccess);
        
        // 异步更新版本统计（实际生产应使用@Async）
        updateVersionStats(promptVersionId);
    }
    
    /**
     * 获取版本使用统计
     */
    public Map<String, Object> getVersionUsageStats(Long versionId) {
        String sql = "SELECT COUNT(*) as usage_count, AVG(rating) as avg_rating, " +
                    "SUM(CASE WHEN execution_success = 1 THEN 1 ELSE 0 END) * 100.0 / COUNT(*) as success_rate " +
                    "FROM prompt_usage_logs WHERE prompt_version_id = ?";
        
        try {
            return jdbcTemplate.queryForMap(sql, versionId);
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }
    
    // ==================== 私有方法 ====================
    
    private Long getDefaultVersionId(String promptType) {
        String sql = "SELECT id FROM prompt_versions WHERE prompt_type = ? AND is_default = 1 LIMIT 1";
        try {
            return jdbcTemplate.queryForObject(sql, Long.class, promptType);
        } catch (Exception e) {
            return null;
        }
    }
    
    private void updateVersionStats(Long versionId) {
        try {
            Map<String, Object> stats = getVersionUsageStats(versionId);
            
            String sql = "UPDATE prompt_versions SET total_usage = ?, avg_rating = ?, success_rate = ? WHERE id = ?";
            jdbcTemplate.update(sql,
                stats.getOrDefault("usage_count", 0),
                stats.getOrDefault("avg_rating", 0.0),
                stats.getOrDefault("success_rate", 0.0),
                versionId
            );
        } catch (Exception e) {
            log.warn("更新版本统计失败: versionId={}", versionId, e);
        }
    }
    
    private String generateOptimizationSuggestion(String issueType) {
        switch (issueType) {
            case "performance_issue":
                return "建议在Prompt中强调添加LIMIT限制、避免SELECT *、使用索引字段";
            case "syntax_error":
                return "建议检查SQL语法规范，特别是JOIN条件和子查询语法";
            case "missing_clause":
                return "建议明确要求包含WHERE条件、GROUP BY等必要子句";
            default:
                return "需要人工review低分案例，提炼共性问题进行优化";
        }
    }
    
    private String getFallbackPrompt(String promptType) {
        // Fallback Prompt模板
        if ("sql_generation".equals(promptType)) {
            return "你是一个专业的SQL工程师。请根据用户问题生成准确、高效的MySQL查询语句。\n\n" +
                   "要求：\n" +
                   "1. 只返回SQL语句，不要解释\n" +
                   "2. 使用标准的MySQL语法\n" +
                   "3. 对于大数据量查询，必须包含LIMIT限制\n" +
                   "4. 优先使用索引字段进行过滤";
        }
        return "请提供专业、准确的回答。";
    }
    
    // ==================== 数据模型 ====================
    
    @Data
    public static class PromptVersion {
        private Long id;
        private String versionName;
        private String promptType;
        private String promptContent;
        private String description;
        private String status;
        private Boolean isDefault;
        private Integer totalUsage;
        private Double avgRating;
        private Double successRate;
        private Long createdBy;
        private Date createdAt;
        private Date updatedAt;
        private Date activatedAt;
    }
    
    private static class PromptVersionRowMapper implements RowMapper<PromptVersion> {
        @Override
        public PromptVersion mapRow(ResultSet rs, int rowNum) throws SQLException {
            PromptVersion version = new PromptVersion();
            version.setId(rs.getLong("id"));
            version.setVersionName(rs.getString("version_name"));
            version.setPromptType(rs.getString("prompt_type"));
            version.setPromptContent(rs.getString("prompt_content"));
            version.setDescription(rs.getString("description"));
            version.setStatus(rs.getString("status"));
            version.setIsDefault(rs.getInt("is_default") == 1);
            version.setTotalUsage(rs.getInt("total_usage"));
            version.setAvgRating(rs.getDouble("avg_rating"));
            version.setSuccessRate(rs.getDouble("success_rate"));
            version.setCreatedBy(rs.getLong("created_by"));
            version.setCreatedAt(rs.getTimestamp("created_at"));
            version.setUpdatedAt(rs.getTimestamp("updated_at"));
            version.setActivatedAt(rs.getTimestamp("activated_at"));
            return version;
        }
    }
}
