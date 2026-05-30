package com.nl2sql.web.controller;

import com.nl2sql.common.result.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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
    
    /**
     * ✅ 新增：获取归一化效果统计
     */
    @GetMapping("/normalization-stats")
    public Result<Map<String, Object>> getNormalizationStats() {
        try {
            Map<String, Object> stats = new HashMap<>();
            
            if (jdbcTemplate == null) {
                return Result.success(stats);
            }
            
            // 1. 总查询数
            Integer total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM nl2sql_query_log", Integer.class
            );
            stats.put("totalQueries", total != null ? total : 0);
            
            // 2. 包含人名实体的查询数
            Integer withPerson = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM nl2sql_query_log WHERE has_person_entity = 1", Integer.class
            );
            stats.put("withPersonEntity", withPerson != null ? withPerson : 0);
            stats.put("personEntityRate", total != null && total > 0 ? 
                String.format("%.1f%%", withPerson * 100.0 / total) : "0%");
            
            // 3. 包含地名实体的查询数
            Integer withLocation = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM nl2sql_query_log WHERE has_location_entity = 1", Integer.class
            );
            stats.put("withLocationEntity", withLocation != null ? withLocation : 0);
            stats.put("locationEntityRate", total != null && total > 0 ? 
                String.format("%.1f%%", withLocation * 100.0 / total) : "0%");
            
            // 4. 归一化方式分布
            try {
                java.util.List<Map<String, Object>> methodDist = jdbcTemplate.queryForList(
                    "SELECT normalization_method, COUNT(*) as count " +
                    "FROM nl2sql_query_log " +
                    "WHERE normalization_method IS NOT NULL " +
                    "GROUP BY normalization_method"
                );
                stats.put("normalizationMethodDistribution", methodDist);
            } catch (Exception e) {
                log.debug("查询归一化方式分布失败", e);
                stats.put("normalizationMethodDistribution", new java.util.ArrayList<>());
            }
            
            // 5. 包含占位符的查询数
            Integer withPlaceholder = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM nl2sql_query_log WHERE normalized_query LIKE '%{PERSON}%' OR normalized_query LIKE '%{LOCATION}%'", 
                Integer.class
            );
            stats.put("withPlaceholder", withPlaceholder != null ? withPlaceholder : 0);
            stats.put("placeholderRate", total != null && total > 0 ? 
                String.format("%.1f%%", withPlaceholder * 100.0 / total) : "0%");
            
            return Result.success(stats);
        } catch (Exception e) {
            log.error("获取归一化统计失败", e);
            return Result.error("获取统计失败: " + e.getMessage());
        }
    }
    
    /**
     * ✅ 新增：获取缓存命中率统计
     */
    @GetMapping("/cache-hit-rate")
    public Result<Map<String, Object>> getCacheHitRate() {
        try {
            Map<String, Object> stats = new HashMap<>();
            
            if (jdbcTemplate == null) {
                return Result.success(stats);
            }
            
            // 1. 总查询数
            Integer total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM nl2sql_query_log", Integer.class
            );
            stats.put("totalQueries", total != null ? total : 0);
            
            // 2. 缓存命中数
            Integer cacheHit = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM nl2sql_query_log WHERE cache_hit = 1", Integer.class
            );
            stats.put("cacheHitCount", cacheHit != null ? cacheHit : 0);
            stats.put("cacheHitRate", total != null && total > 0 ? 
                String.format("%.1f%%", cacheHit * 100.0 / total) : "0%");
            
            // 3. 各层级缓存命中分布
            try {
                java.util.List<Map<String, Object>> levelDist = jdbcTemplate.queryForList(
                    "SELECT cache_level, COUNT(*) as count " +
                    "FROM nl2sql_query_log " +
                    "WHERE cache_level IS NOT NULL " +
                    "GROUP BY cache_level " +
                    "ORDER BY FIELD(cache_level, 'L1', 'L2', 'L3', 'MISS')"
                );
                stats.put("cacheLevelDistribution", levelDist);
            } catch (Exception e) {
                log.debug("查询缓存层级分布失败", e);
                stats.put("cacheLevelDistribution", new java.util.ArrayList<>());
            }
            
            // 4. RAG检索统计
            try {
                Integer withRag = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM nl2sql_query_log WHERE rag_examples_count > 0", Integer.class
                );
                stats.put("ragUsageCount", withRag != null ? withRag : 0);
                stats.put("ragUsageRate", total != null && total > 0 ? 
                    String.format("%.1f%%", withRag * 100.0 / total) : "0%");
                
                Double avgRagExamples = jdbcTemplate.queryForObject(
                    "SELECT AVG(rag_examples_count) FROM nl2sql_query_log WHERE rag_examples_count > 0", 
                    Double.class
                );
                stats.put("avgRagExamplesPerQuery", avgRagExamples != null ? 
                    String.format("%.1f", avgRagExamples) : "0");
            } catch (Exception e) {
                log.debug("查询RAG统计失败", e);
            }
            
            return Result.success(stats);
        } catch (Exception e) {
            log.error("获取缓存统计失败", e);
            return Result.error("获取统计失败: " + e.getMessage());
        }
    }
    
    /**
     * ✅ 新增：获取低分查询分析
     */
    @GetMapping("/low-rating-analysis")
    public Result<java.util.List<Map<String, Object>>> getLowRatingAnalysis() {
        try {
            if (jdbcTemplate == null) {
                return Result.success(new java.util.ArrayList<>());
            }
            
            // 查询低分反馈关联的查询记录
            String sql = "SELECT q.question, q.generated_sql, q.normalized_query, " +
                        "q.cache_level, q.rag_examples_count, " +
                        "f.rating, f.feedback_text, f.created_at " +
                        "FROM nl2sql_query_log q " +
                        "JOIN rag_feedback f ON q.question = f.question " +
                        "WHERE f.rating <= 2 " +
                        "ORDER BY f.created_at DESC " +
                        "LIMIT 50";
            
            java.util.List<Map<String, Object>> results = jdbcTemplate.queryForList(sql);
            
            return Result.success(results);
        } catch (Exception e) {
            log.error("获取低分查询分析失败", e);
            return Result.error("获取分析失败: " + e.getMessage());
        }
    }
    
    /**
     * ✅ ECharts新增：查询趋势数据（近N天）
     */
    @GetMapping("/query-trend")
    public Result<Map<String, Object>> getQueryTrend(@RequestParam(defaultValue = "7") int days) {
        try {
            Map<String, Object> result = new HashMap<>();
            
            if (jdbcTemplate == null) {
                result.put("dates", new ArrayList<>());
                result.put("totalQueries", new ArrayList<>());
                result.put("successQueries", new ArrayList<>());
                result.put("cacheHits", new ArrayList<>());
                return Result.success(result);
            }
            
            List<String> dates = new ArrayList<>();
            List<Integer> totalQueries = new ArrayList<>();
            List<Integer> successQueries = new ArrayList<>();
            List<Integer> cacheHits = new ArrayList<>();
            
            for (int i = days - 1; i >= 0; i--) {
                String dateSql = i == 0 ? "CURDATE()" : String.format("DATE_SUB(CURDATE(), INTERVAL %d DAY)", i);
                
                String dateQuery = "SELECT " + dateSql;
                String date = jdbcTemplate.queryForObject(dateQuery, String.class);
                dates.add(date);
                
                Integer total = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM nl2sql_query_log WHERE DATE(created_at) = " + dateSql, 
                    Integer.class
                );
                totalQueries.add(total != null ? total : 0);
                
                Integer success = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM nl2sql_query_log WHERE DATE(created_at) = " + dateSql + " AND execution_success = 1", 
                    Integer.class
                );
                successQueries.add(success != null ? success : 0);
                
                Integer hits = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM nl2sql_query_log WHERE DATE(created_at) = " + dateSql + " AND cache_hit = 1", 
                    Integer.class
                );
                cacheHits.add(hits != null ? hits : 0);
            }
            
            result.put("dates", dates);
            result.put("totalQueries", totalQueries);
            result.put("successQueries", successQueries);
            result.put("cacheHits", cacheHits);
            
            return Result.success(result);
        } catch (Exception e) {
            log.error("获取查询趋势失败", e);
            return Result.error("获取趋势失败: " + e.getMessage());
        }
    }
    
    /**
     * ✅ ECharts新增：热门表TOP N
     */
    @GetMapping("/hot-tables")
    public Result<Map<String, Object>> getHotTables(@RequestParam(defaultValue = "10") int limit) {
        try {
            Map<String, Object> result = new HashMap<>();
            List<Map<String, Object>> tables = new ArrayList<>();
            
            if (jdbcTemplate != null) {
                try {
                    String sql = "SELECT table_name as tableName, COUNT(*) as queryCount " +
                                "FROM nl2sql_query_log " +
                                "WHERE table_name IS NOT NULL AND table_name != '' " +
                                "GROUP BY table_name " +
                                "ORDER BY queryCount DESC " +
                                "LIMIT " + limit;
                    
                    tables = jdbcTemplate.queryForList(sql);
                } catch (Exception e) {
                    log.debug("查询热门表失败", e);
                }
            }
            
            result.put("tables", tables);
            return Result.success(result);
        } catch (Exception e) {
            log.error("获取热门表失败", e);
            return Result.error("获取热门表失败: " + e.getMessage());
        }
    }
    
    /**
     * ✅ ECharts新增：查询类型分布
     */
    @GetMapping("/query-type-distribution")
    public Result<Map<String, Object>> getQueryTypeDistribution() {
        try {
            Map<String, Object> result = new HashMap<>();
            List<Map<String, Object>> types = new ArrayList<>();
            
            if (jdbcTemplate != null) {
                try {
                    String sql = "SELECT query_type as type, COUNT(*) as count " +
                                "FROM nl2sql_query_log " +
                                "WHERE query_type IS NOT NULL AND query_type != '' " +
                                "GROUP BY query_type " +
                                "ORDER BY count DESC";
                    
                    types = jdbcTemplate.queryForList(sql);
                } catch (Exception e) {
                    log.debug("查询类型分布失败", e);
                }
            }
            
            result.put("types", types);
            return Result.success(result);
        } catch (Exception e) {
            log.error("获取查询类型分布失败", e);
            return Result.error("获取查询类型分布失败: " + e.getMessage());
        }
    }
    
    /**
     * ✅ ECharts新增：响应时间分布
     */
    @GetMapping("/response-time-distribution")
    public Result<Map<String, Object>> getResponseTimeDistribution() {
        try {
            Map<String, Object> result = new HashMap<>();
            List<String> ranges = List.of("<1s", "1-2s", "2-3s", "3-5s", ">5s");
            List<Integer> counts = new ArrayList<>();
            
            if (jdbcTemplate != null) {
                try {
                    Integer lt1s = jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM nl2sql_query_log WHERE execution_time_ms < 1000", Integer.class);
                    counts.add(lt1s != null ? lt1s : 0);
                    
                    Integer lt2s = jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM nl2sql_query_log WHERE execution_time_ms >= 1000 AND execution_time_ms < 2000", Integer.class);
                    counts.add(lt2s != null ? lt2s : 0);
                    
                    Integer lt3s = jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM nl2sql_query_log WHERE execution_time_ms >= 2000 AND execution_time_ms < 3000", Integer.class);
                    counts.add(lt3s != null ? lt3s : 0);
                    
                    Integer lt5s = jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM nl2sql_query_log WHERE execution_time_ms >= 3000 AND execution_time_ms < 5000", Integer.class);
                    counts.add(lt5s != null ? lt5s : 0);
                    
                    Integer gt5s = jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM nl2sql_query_log WHERE execution_time_ms >= 5000", Integer.class);
                    counts.add(gt5s != null ? gt5s : 0);
                } catch (Exception e) {
                    log.debug("查询响应时间分布失败", e);
                    counts = List.of(0, 0, 0, 0, 0);
                }
            } else {
                counts = List.of(0, 0, 0, 0, 0);
            }
            
            result.put("ranges", ranges);
            result.put("counts", counts);
            return Result.success(result);
        } catch (Exception e) {
            log.error("获取响应时间分布失败", e);
            return Result.error("获取响应时间分布失败: " + e.getMessage());
        }
    }
}
