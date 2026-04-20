package com.nl2sql.web.controller;

import com.nl2sql.common.result.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * 系统监控控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/monitor")
public class MonitorController {
    
    @Autowired(required = false)
    private JdbcTemplate jdbcTemplate;
    
    /**
     * 获取系统统计信息
     */
    @GetMapping("/stats")
    public Result<Map<String, Object>> getStats() {
        try {
            Map<String, Object> stats = new HashMap<>();
            
            // 查询总查询次数
            if (jdbcTemplate != null) {
                try {
                    Integer totalQueries = jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM nl2sql_query_log", Integer.class
                    );
                    stats.put("totalQueries", totalQueries != null ? totalQueries : 0);
                } catch (Exception e) {
                    log.warn("查询总次数失败", e);
                    stats.put("totalQueries", 0);
                }
                
                // 今日查询次数
                try {
                    Integer todayQueries = jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM nl2sql_query_log WHERE DATE(created_at) = CURDATE()", 
                        Integer.class
                    );
                    stats.put("todayQueries", todayQueries != null ? todayQueries : 0);
                } catch (Exception e) {
                    log.warn("查询今日次数失败", e);
                    stats.put("todayQueries", 0);
                }
                
                // 慢查询数（超过3秒）
                try {
                    Integer slowQueries = jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM nl2sql_query_log WHERE execution_time_ms > 3000", 
                        Integer.class
                    );
                    stats.put("slowQueries", slowQueries != null ? slowQueries : 0);
                } catch (Exception e) {
                    log.warn("查询慢查询数失败", e);
                    stats.put("slowQueries", 0);
                }
                
                // 平均耗时
                try {
                    Double avgTime = jdbcTemplate.queryForObject(
                        "SELECT AVG(execution_time_ms) FROM nl2sql_query_log", 
                        Double.class
                    );
                    stats.put("avgTime", avgTime != null ? String.format("%.2fs", avgTime / 1000) : "0s");
                } catch (Exception e) {
                    log.warn("查询平均耗时失败", e);
                    stats.put("avgTime", "0s");
                }
            } else {
                stats.put("totalQueries", 0);
                stats.put("todayQueries", 0);
                stats.put("slowQueries", 0);
                stats.put("avgTime", "0s");
            }
            
            // 缓存统计（简化版，实际可集成Caffeine/Redis监控）
            Map<String, Object> cacheStats = new HashMap<>();
            cacheStats.put("size", 0);
            cacheStats.put("hitRate", "0%");
            stats.put("cacheStats", cacheStats);
            
            return Result.success(stats);
        } catch (Exception e) {
            log.error("获取系统统计信息失败", e);
            return Result.error("获取统计信息失败: " + e.getMessage());
        }
    }
}
