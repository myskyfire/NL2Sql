package com.nl2sql.audit;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Slf4j
@Service
public class AuditService {
    
    private final RedisTemplate<String, Object> redisTemplate;
    
    public AuditService(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }
    
    @Data
    public static class AuditLog {
        private String username;
        private String query;
        private String sql;
        private String status;
        private int rowCount;
        private String errorMessage;
        private LocalDateTime timestamp;
        private double executionTime;
    }
    
    public void logQuery(AuditLog auditLog) {
        try {
            String key = "audit:log:" + System.currentTimeMillis();
            redisTemplate.opsForValue().set(key, auditLog);
            redisTemplate.expire(key, 30, java.util.concurrent.TimeUnit.DAYS);
            
            redisTemplate.opsForList().rightPush("audit:logs", auditLog);
            redisTemplate.opsForList().trim("audit:logs", -1000, -1);
            
            log.info("审计日志记录成功: {}", auditLog.getQuery());
        } catch (Exception e) {
            log.error("审计日志记录失败", e);
        }
    }
    
    public List<AuditLog> getLogs(int page, int size) {
        Long total = redisTemplate.opsForList().size("audit:logs");
        if (total == null || total == 0) {
            return Collections.emptyList();
        }
        
        int start = Math.max(0, (int) (total - page * size));
        int end = Math.max(0, (int) (total - (page - 1) * size - 1));
        
        List<Object> logs = redisTemplate.opsForList().range("audit:logs", start, end);
        if (logs == null) {
            return Collections.emptyList();
        }
        
        List<AuditLog> result = new ArrayList<>();
        for (Object log : logs) {
            if (log instanceof AuditLog) {
                result.add((AuditLog) log);
            }
        }
        
        Collections.reverse(result);
        return result;
    }
    
    public Map<String, Object> getMonitorStats() {
        Map<String, Object> stats = new HashMap<>();
        
        Long totalQueries = redisTemplate.opsForValue().increment("monitor:total_queries");
        Long slowQueries = Long.parseLong(redisTemplate.opsForValue().get("monitor:slow_queries").toString());
        Long failedQueries = Long.parseLong(redisTemplate.opsForValue().get("monitor:failed_queries").toString());
        
        stats.put("totalQueries", totalQueries);
        stats.put("slowQueries", slowQueries != null ? slowQueries : 0);
        stats.put("failedQueries", failedQueries != null ? failedQueries : 0);
        
        return stats;
    }
    
    public void recordSlowQuery(double executionTime) {
        redisTemplate.opsForValue().increment("monitor:slow_queries");
        log.warn("检测到慢查询: {}秒", executionTime);
    }
    
    public void recordFailedQuery() {
        redisTemplate.opsForValue().increment("monitor:failed_queries");
    }
}
