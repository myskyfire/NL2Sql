package com.nl2sql.metadata.scheduler;

import com.nl2sql.metadata.service.MetadataCollectorService;
import com.nl2sql.metadata.service.TableQueryStatsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 元数据增强定时任务调度器
 */
@Slf4j
@Component
public class MetadataEnhancementScheduler {
    
    @Autowired
    private JdbcTemplate jdbcTemplate;
    
    @Autowired(required = false)
    private MetadataCollectorService metadataCollectorService;
    
    @Autowired(required = false)
    private TableQueryStatsService tableQueryStatsService;
    
    /**
     * 每周日凌晨 2 点执行批量增强
     * cron: 秒 分 时 日 月 周
     */
    @Scheduled(cron = "0 0 2 * * SUN")
    public void weeklyBatchEnhancement() {
        log.info("[定时任务] 开始每周元数据批量增强");
        
        if (tableQueryStatsService == null || metadataCollectorService == null) {
            log.warn("[定时任务] 服务未启用，跳过批量增强");
            return;
        }
        
        try {
            // 找出高频查询但注释覆盖率低的表
            // 查询次数 >= 10，低分次数 >= 3
            List<Map<String, Object>> candidates = tableQueryStatsService
                .getHighFrequencyLowQualityTables(10, 3, 20);
            
            if (candidates.isEmpty()) {
                log.info("[定时任务] 无需增强的表");
                return;
            }
            
            log.info("[定时任务] 找到 {} 张候选表", candidates.size());
            
            int enhancedCount = 0;
            for (Map<String, Object> stats : candidates) {
                Long datasourceId = ((Number) stats.get("datasource_id")).longValue();
                String tableName = (String) stats.get("table_name");
                Integer queryCount = (Integer) stats.get("query_count");
                Integer lowRatingCount = (Integer) stats.get("low_rating_count");
                
                log.info("[定时任务] 增强表: {}.{}, 查询次数={}, 低分次数={}", 
                        datasourceId, tableName, queryCount, lowRatingCount);
                
                try {
                    metadataCollectorService.enhanceColumnDescriptionsForTable(datasourceId, tableName);
                    enhancedCount++;
                    
                    // 避免频繁调用，间隔 5 秒
                    Thread.sleep(5000);
                    
                } catch (Exception e) {
                    log.error("[定时任务] 增强表 {}.{} 失败", datasourceId, tableName, e);
                }
            }
            
            log.info("[定时任务] 批量增强完成，成功增强 {} 张表", enhancedCount);
            
        } catch (Exception e) {
            log.error("[定时任务] 批量增强失败", e);
        }
    }
    
    /**
     * 每小时上报元数据统计
     */
    @Scheduled(fixedRate = 3600000) // 1小时 = 3600000毫秒
    public void reportMetadataStats() {
        try {
            // 总表数
            Integer totalTables = jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT CONCAT(datasource_id, '_', table_name)) FROM column_metadata", 
                Integer.class
            );
            
            // LLM 增强的表数
            Integer llmEnhanced = jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT CONCAT(datasource_id, '_', table_name)) FROM column_metadata WHERE comment_source = 'LLM'", 
                Integer.class
            );
            
            // 规则推断的表数
            Integer inferred = jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT CONCAT(datasource_id, '_', table_name)) FROM column_metadata WHERE comment_source = 'INFERRED'", 
                Integer.class
            );
            
            // 手动的表数
            Integer manual = jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT CONCAT(datasource_id, '_', table_name)) FROM column_metadata WHERE comment_source = 'MANUAL' OR comment_source IS NULL", 
                Integer.class
            );
            
            // 计算覆盖率
            double coverageRate = (totalTables != null && totalTables > 0) 
                ? ((llmEnhanced != null ? llmEnhanced : 0) + (inferred != null ? inferred : 0)) * 100.0 / totalTables 
                : 0;
            
            // ✅ 新增：LLM 增强的字段数（更细粒度统计）
            Integer llmEnhancedColumns = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM column_metadata WHERE comment_source = 'LLM'", 
                Integer.class
            );
            
            log.info("[元数据统计] 总表数: {}, LLM增强表: {}, 规则推断表: {}, 手动表: {}, 覆盖率: {:.2f}%, LLM增强字段数: {}", 
                     totalTables, llmEnhanced, inferred, manual, coverageRate, llmEnhancedColumns);
            
        } catch (Exception e) {
            log.error("[元数据统计] 上报失败", e);
        }
    }
    
    /**
     * 每天凌晨 3 点清理过期统计数据（保留90天）
     */
    @Scheduled(cron = "0 0 3 * * *")
    public void cleanupOldStats() {
        if (tableQueryStatsService != null) {
            try {
                tableQueryStatsService.cleanupOldStats(90);
            } catch (Exception e) {
                log.error("[定时任务] 清理统计数据失败", e);
            }
        }
    }
}
