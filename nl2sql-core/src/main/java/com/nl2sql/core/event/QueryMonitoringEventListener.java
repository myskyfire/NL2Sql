package com.nl2sql.core.event;

import com.nl2sql.metadata.mapper.MetadataQueryMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 查询监控事件监听器 - 异步写入监控数据到数据库
 */
@Slf4j
@Component
public class QueryMonitoringEventListener {
    
    @Autowired(required = false)
    private JdbcTemplate jdbcTemplate;
    
    @Autowired
    private MetadataQueryMapper metadataMapper;
    
    /**
     * 异步处理监控事件
     * 使用@Async确保不阻塞主线程
     */
    @Async
    @EventListener
    public void handleQueryMonitoringEvent(QueryMonitoringEvent event) {
        if (jdbcTemplate == null) {
            log.debug("[QueryMonitoring] JdbcTemplate未注入,跳过监控记录");
            return;
        }
        
        try {
            Map<String, Object> data = event.getMonitoringData();
            
            metadataMapper.insertQueryLog(
                (String) data.get("sessionId"),
                toLong(data.get("userId")),
                (String) data.get("question"),
                (String) data.get("normalizedQuery"),
                toInteger(data.get("hasPersonEntity")),
                toInteger(data.get("hasLocationEntity")),
                (String) data.get("normalizationMethod"),
                (String) data.get("cacheLevel"),
                toInteger(data.get("cacheHit")),
                toInteger(data.get("ragExamplesCount")),
                (String) data.get("industryTermsMatched"),
                (String) data.get("generatedSql"),
                (String) data.get("executedSql"),
                toInteger(data.get("executionSuccess")),
                toLong(data.get("rowCount")),
                toLong(data.get("executionTimeMs")),
                toLong(data.get("datasourceId")),
                (String) data.get("selectedTables")
            );
            
            log.debug("[QueryMonitoring] 异步记录成功: sessionId={}, cacheLevel={}", 
                data.get("sessionId"), data.get("cacheLevel"));
                
        } catch (Exception e) {
            log.warn("[QueryMonitoring] 异步记录失败", e);
        }
    }
    
    private Integer toInteger(Object value) {
        if (value == null) return 0;
        if (value instanceof Boolean) return (Boolean) value ? 1 : 0;
        if (value instanceof Number) return ((Number) value).intValue();
        return 0;
    }
    
    private Long toLong(Object value) {
        if (value == null) return 0L;
        if (value instanceof Number) return ((Number) value).longValue();
        return 0L;
    }
}
