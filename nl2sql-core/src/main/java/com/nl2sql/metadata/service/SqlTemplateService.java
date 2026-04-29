package com.nl2sql.metadata.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * SQL模板路由服务
 * 
 * 根据术语+动作直接返回预定义SQL模板，避免LLM调用
 */
@Slf4j
@Service
public class SqlTemplateService {
    
    @Autowired(required = false)
    private JdbcTemplate jdbcTemplate;
    
    /**
     * 根据模板ID和参数生成SQL
     * 
     * @param templateId 模板ID（如order_list）
     * @param params 动态参数（如时间范围、筛选条件）
     * @return 生成的SQL
     */
    public String generateSql(String templateId, Map<String, Object> params) {
        if (templateId == null || templateId.trim().isEmpty()) {
            log.warn("[SqlTemplate] 模板ID为空");
            return null;
        }
        
        try {
            // 1. 从数据库查询模板
            String template = queryTemplate(templateId);
            if (template == null) {
                log.warn("[SqlTemplate] 模板不存在: {}", templateId);
                return null;
            }
            
            // 2. 替换占位符
            String sql = replacePlaceholders(template, params);
            
            log.debug("[SqlTemplate] 模板ID={}, 生成SQL长度={}", templateId, sql.length());
            return sql;
            
        } catch (Exception e) {
            log.error("[SqlTemplate] 生成SQL失败: templateId={}", templateId, e);
            return null;
        }
    }
    
    /**
     * 从数据库查询模板内容
     */
    private String queryTemplate(String templateId) {
        if (jdbcTemplate == null) {
            log.warn("[SqlTemplate] JdbcTemplate未配置");
            return getDefaultTemplate(templateId);
        }
        
        try {
            String sql = "SELECT template_content FROM sql_template WHERE template_id = ? AND is_active = 1";
            return jdbcTemplate.queryForObject(sql, String.class, templateId);
        } catch (Exception e) {
            log.warn("[SqlTemplate] 查询模板失败，使用默认模板: {}", e.getMessage());
            return getDefaultTemplate(templateId);
        }
    }
    
    /**
     * 默认模板（兜底逻辑）
     */
    private String getDefaultTemplate(String templateId) {
        return switch (templateId) {
            case "order_list" -> "SELECT * FROM orders WHERE 1=1 {conditions} ORDER BY created_at DESC LIMIT 100";
            case "order_stats" -> "SELECT COUNT(*) AS order_count, SUM(amount) AS total_amount FROM orders WHERE 1=1 {conditions}";
            case "order_detail" -> "SELECT * FROM orders WHERE id = {id}";
            case "user_list" -> "SELECT * FROM users WHERE 1=1 {conditions} ORDER BY created_at DESC LIMIT 100";
            case "user_activity_stats" -> "SELECT COUNT(DISTINCT user_id) AS dau FROM user_activity WHERE activity_date = {date}";
            case "product_list" -> "SELECT * FROM products WHERE 1=1 {conditions} ORDER BY sales DESC LIMIT 100";
            case "product_sales_stats" -> "SELECT product_id, SUM(quantity) AS total_sales, SUM(amount) AS total_revenue FROM order_items GROUP BY product_id ORDER BY total_sales DESC LIMIT 10";
            case "amount_sum" -> "SELECT SUM(amount) AS total_amount FROM orders WHERE 1=1 {conditions}";
            case "amount_compare" -> "SELECT DATE_FORMAT(created_at, '%Y-%m') AS month, SUM(amount) AS monthly_amount FROM orders GROUP BY month ORDER BY month";
            case "conversion_trend" -> "SELECT DATE_FORMAT(created_at, '%Y-%m-%d') AS date, COUNT(*) AS orders FROM orders WHERE 1=1 {conditions} GROUP BY date ORDER BY date";
            case "conversion_compare" -> "SELECT channel, COUNT(*) AS orders, SUM(amount) AS total_amount FROM orders GROUP BY channel ORDER BY total_amount DESC";
            default -> null;
        };
    }
    
    /**
     * 替换SQL模板中的占位符
     */
    private String replacePlaceholders(String template, Map<String, Object> params) {
        if (params == null || params.isEmpty()) {
            return template.replace("{conditions}", "");
        }
        
        String sql = template;
        
        // 替换通用占位符
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            String key = "{" + entry.getKey() + "}";
            String value = escapeSqlValue(entry.getValue());
            sql = sql.replace(key, value);
        }
        
        // 处理{conditions}占位符
        if (sql.contains("{conditions}")) {
            String conditions = buildConditions(params);
            sql = sql.replace("{conditions}", conditions.isEmpty() ? "" : " AND " + conditions);
        }
        
        return sql;
    }
    
    /**
     * 构建WHERE条件
     */
    private String buildConditions(Map<String, Object> params) {
        StringBuilder conditions = new StringBuilder();
        
        if (params.containsKey("startDate")) {
            conditions.append("created_at >= '").append(escapeSqlValue(params.get("startDate"))).append("' ");
        }
        
        if (params.containsKey("endDate")) {
            conditions.append("AND created_at <= '").append(escapeSqlValue(params.get("endDate"))).append("' ");
        }
        
        if (params.containsKey("status")) {
            conditions.append("AND status = '").append(escapeSqlValue(params.get("status"))).append("' ");
        }
        
        return conditions.toString().trim();
    }
    
    /**
     * SQL值转义（防止注入）
     */
    private String escapeSqlValue(Object value) {
        if (value == null) {
            return "NULL";
        }
        
        String str = value.toString();
        
        // 数字类型直接返回
        if (value instanceof Number) {
            return str;
        }
        
        // 字符串类型转义单引号
        return str.replace("'", "''");
    }
}
