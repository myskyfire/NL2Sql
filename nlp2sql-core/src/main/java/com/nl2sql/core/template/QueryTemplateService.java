package com.nl2sql.core.template;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class QueryTemplateService {
    
    private final JdbcTemplate jdbcTemplate;
    
    public QueryTemplateService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }
    
    /**
     * 保存查询模板
     */
    public Long saveTemplate(Long userId, String name, String description, 
                            String templateSql, Map<String, Object> parameters, 
                            String category, boolean isPublic) {
        String sql = "INSERT INTO query_templates (user_id, name, description, template_sql, parameters, category, is_public, created_at) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, NOW())";
        
        jdbcTemplate.update(sql, userId, name, description, templateSql, 
            parameters != null ? parameters.toString() : null, category, isPublic ? 1 : 0);
        
        Long templateId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        log.info("保存查询模板: userId={}, templateId={}, name={}", userId, templateId, name);
        
        return templateId;
    }
    
    /**
     * 获取用户的模板列表
     */
    public List<TemplateInfo> getUserTemplates(Long userId, String category, int page, int size) {
        StringBuilder sql = new StringBuilder(
            "SELECT id, name, description, template_sql, parameters, category, is_public, usage_count, created_at " +
            "FROM query_templates WHERE user_id = ?"
        );
        
        if (category != null && !category.isEmpty()) {
            sql.append(" AND category = ?");
        }
        
        sql.append(" ORDER BY created_at DESC LIMIT ? OFFSET ?");
        
        int offset = (page - 1) * size;
        
        Object[] params = category != null && !category.isEmpty() ? 
            new Object[]{userId, category, size, offset} : 
            new Object[]{userId, size, offset};
        
        return jdbcTemplate.query(sql.toString(), new TemplateRowMapper(), params);
    }
    
    /**
     * 获取公共模板
     */
    public List<TemplateInfo> getPublicTemplates(String category, int page, int size) {
        StringBuilder sql = new StringBuilder(
            "SELECT id, user_id, name, description, template_sql, parameters, category, usage_count, created_at " +
            "FROM query_templates WHERE is_public = 1"
        );
        
        if (category != null && !category.isEmpty()) {
            sql.append(" AND category = ?");
        }
        
        sql.append(" ORDER BY usage_count DESC LIMIT ? OFFSET ?");
        
        int offset = (page - 1) * size;
        Object[] params = category != null && !category.isEmpty() ? 
            new Object[]{category, size, offset} : 
            new Object[]{size, offset};
        
        return jdbcTemplate.query(sql.toString(), new PublicTemplateRowMapper(), params);
    }
    
    /**
     * 使用模板（增加使用次数）
     */
    public void useTemplate(Long templateId) {
        String sql = "UPDATE query_templates SET usage_count = usage_count + 1 WHERE id = ?";
        jdbcTemplate.update(sql, templateId);
        log.debug("模板使用次数+1: templateId={}", templateId);
    }
    
    /**
     * 删除模板
     */
    public void deleteTemplate(Long templateId, Long userId) {
        String sql = "DELETE FROM query_templates WHERE id = ? AND user_id = ?";
        int rows = jdbcTemplate.update(sql, templateId, userId);
        
        if (rows > 0) {
            log.info("删除查询模板: templateId={}, userId={}", templateId, userId);
        } else {
            log.warn("删除模板失败: templateId={}, userId={}", templateId, userId);
        }
    }
    
    /**
     * 获取模板详情
     */
    public TemplateInfo getTemplateById(Long templateId) {
        String sql = "SELECT id, user_id, name, description, template_sql, parameters, category, is_public, usage_count, created_at " +
                    "FROM query_templates WHERE id = ?";
        
        List<TemplateInfo> templates = jdbcTemplate.query(sql, new FullTemplateRowMapper(), templateId);
        return templates.isEmpty() ? null : templates.get(0);
    }
    
    // ==================== 数据模型 ====================
    
    @Data
    public static class TemplateInfo {
        private Long id;
        private Long userId;
        private String name;
        private String description;
        private String templateSql;
        private String parameters;
        private String category;
        private Boolean isPublic;
        private Integer usageCount;
        private LocalDateTime createdAt;
    }
    
    // ==================== RowMapper ====================
    
    private static class TemplateRowMapper implements RowMapper<TemplateInfo> {
        @Override
        public TemplateInfo mapRow(ResultSet rs, int rowNum) throws SQLException {
            TemplateInfo template = new TemplateInfo();
            template.setId(rs.getLong("id"));
            template.setName(rs.getString("name"));
            template.setDescription(rs.getString("description"));
            template.setTemplateSql(rs.getString("template_sql"));
            template.setParameters(rs.getString("parameters"));
            template.setCategory(rs.getString("category"));
            template.setIsPublic(rs.getInt("is_public") == 1);
            template.setUsageCount(rs.getInt("usage_count"));
            template.setCreatedAt(rs.getTimestamp("created_at").toLocalDateTime());
            return template;
        }
    }
    
    private static class PublicTemplateRowMapper implements RowMapper<TemplateInfo> {
        @Override
        public TemplateInfo mapRow(ResultSet rs, int rowNum) throws SQLException {
            TemplateInfo template = new TemplateInfo();
            template.setId(rs.getLong("id"));
            template.setUserId(rs.getLong("user_id"));
            template.setName(rs.getString("name"));
            template.setDescription(rs.getString("description"));
            template.setTemplateSql(rs.getString("template_sql"));
            template.setParameters(rs.getString("parameters"));
            template.setCategory(rs.getString("category"));
            template.setUsageCount(rs.getInt("usage_count"));
            template.setCreatedAt(rs.getTimestamp("created_at").toLocalDateTime());
            return template;
        }
    }
    
    private static class FullTemplateRowMapper implements RowMapper<TemplateInfo> {
        @Override
        public TemplateInfo mapRow(ResultSet rs, int rowNum) throws SQLException {
            TemplateInfo template = new TemplateInfo();
            template.setId(rs.getLong("id"));
            template.setUserId(rs.getLong("user_id"));
            template.setName(rs.getString("name"));
            template.setDescription(rs.getString("description"));
            template.setTemplateSql(rs.getString("template_sql"));
            template.setParameters(rs.getString("parameters"));
            template.setCategory(rs.getString("category"));
            template.setIsPublic(rs.getInt("is_public") == 1);
            template.setUsageCount(rs.getInt("usage_count"));
            template.setCreatedAt(rs.getTimestamp("created_at").toLocalDateTime());
            return template;
        }
    }
}
