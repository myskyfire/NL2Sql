package com.nl2sql.metadata.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 表查询统计服务
 * 记录每张表的查询频率和评分，用于元数据增强决策
 */
@Slf4j
@Service
public class TableQueryStatsService {
    
    @Autowired
    private JdbcTemplate jdbcTemplate;
    
    /**
     * 记录查询（每次执行SQL后调用）
     */
    public void recordQuery(Long datasourceId, String tableName, int rating) {
        try {
            // 1. 插入或更新统计记录
            String upsertSql = "INSERT INTO table_query_stats (datasource_id, table_name, query_count, avg_rating, low_rating_count, last_query_at) " +
                              "VALUES (?, ?, 1, ?, ?, NOW()) " +
                              "ON DUPLICATE KEY UPDATE " +
                              "query_count = query_count + 1, " +
                              "avg_rating = ((avg_rating * (query_count - 1)) + ?) / query_count, " +
                              "low_rating_count = low_rating + (CASE WHEN ? <= 2 THEN 1 ELSE 0 END), " +
                              "last_query_at = NOW()";
            
            double ratingDouble = rating;
            jdbcTemplate.update(upsertSql, 
                datasourceId, tableName, ratingDouble, 
                rating <= 2 ? 1 : 0,
                ratingDouble, rating);
            
            log.debug("[查询统计] 记录查询: datasourceId={}, table={}, rating={}", 
                     datasourceId, tableName, rating);
            
        } catch (Exception e) {
            log.warn("[查询统计] 记录失败", e);
            // 不阻塞主流程
        }
    }
    
    /**
     * 获取高频低质表（用于批量增强）
     * 
     * @param minQueryCount 最小查询次数
     * @param minLowRatingCount 最小低分次数
     * @param limit 返回数量限制
     */
    public List<Map<String, Object>> getHighFrequencyLowQualityTables(
            int minQueryCount, int minLowRatingCount, int limit) {
        
        String sql = "SELECT datasource_id, table_name, query_count, avg_rating, low_rating_count, last_query_at " +
                    "FROM table_query_stats " +
                    "WHERE query_count >= ? " +
                    "AND low_rating_count >= ? " +
                    "ORDER BY low_rating_count DESC, query_count DESC " +
                    "LIMIT ?";
        
        return jdbcTemplate.queryForList(sql, minQueryCount, minLowRatingCount, limit);
    }
    
    /**
     * 获取指定表的统计信息
     */
    public Map<String, Object> getTableStats(Long datasourceId, String tableName) {
        String sql = "SELECT * FROM table_query_stats WHERE datasource_id = ? AND table_name = ?";
        
        List<Map<String, Object>> results = jdbcTemplate.queryForList(sql, datasourceId, tableName);
        return results.isEmpty() ? null : results.get(0);
    }
    
    /**
     * 清理过期统计数据（可选）
     * 
     * @param daysAgo 清理多少天前的数据
     */
    public void cleanupOldStats(int daysAgo) {
        String sql = "DELETE FROM table_query_stats WHERE last_query_at < DATE_SUB(NOW(), INTERVAL ? DAY)";
        int deleted = jdbcTemplate.update(sql, daysAgo);
        log.info("[查询统计] 清理 {} 天前的统计数据，删除 {} 条记录", daysAgo, deleted);
    }
}
