package com.nl2sql.core.executor;

import com.nl2sql.core.mapper.ExecutionLogMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class SQLExecutionLogService {
    
    @Autowired(required = false)
    private ExecutionLogMapper executionLogMapper;
    
    private final JdbcTemplate jdbcTemplate;
    
    public SQLExecutionLogService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }
    
    @PostConstruct
    public void initTable() {
        try {
            String createTableSQL = "CREATE TABLE IF NOT EXISTS sql_execution_logs (" +
                "id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '日志ID', " +
                "user_id BIGINT COMMENT '用户ID', " +
                "username VARCHAR(50) COMMENT '用户名', " +
                "sql_text TEXT NOT NULL COMMENT '执行的SQL', " +
                "execution_time_ms BIGINT COMMENT '执行时间(毫秒)', " +
                "row_count INT COMMENT '返回行数', " +
                "is_slow_query TINYINT(1) DEFAULT 0 COMMENT '是否慢查询', " +
                "status VARCHAR(20) DEFAULT 'SUCCESS' COMMENT '状态: SUCCESS/FAILED/TIMEOUT', " +
                "error_message TEXT COMMENT '错误信息', " +
                "ip_address VARCHAR(50) COMMENT 'IP地址', " +
                "created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '执行时间', " +
                "INDEX idx_user (user_id), " +
                "INDEX idx_created (created_at), " +
                "INDEX idx_slow (is_slow_query), " +
                "INDEX idx_status (status)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='SQL执行日志表'";
            
            jdbcTemplate.execute(createTableSQL);
            log.info("SQL执行日志表初始化完成");
            
        } catch (Exception e) {
            log.error("SQL执行日志表初始化失败", e);
        }
    }
    
    /**
     * 记录SQL执行日志
     */
    public void logExecution(ExecutionLog execLog) {
        try {
            if (executionLogMapper != null) {
                // 使用MyBatis Mapper
                executionLogMapper.insertExecutionLog(
                    execLog.getUserId(),
                    execLog.getUsername(),
                    execLog.getSqlText(),
                    execLog.getExecutionTimeMs(),
                    execLog.getRowCount() != null ? execLog.getRowCount().longValue() : 0L,
                    execLog.isSlowQuery(),
                    execLog.getStatus(),
                    execLog.getErrorMessage(),
                    execLog.getIpAddress()
                );
            } else {
                // 降级到JdbcTemplate
                String sql = "INSERT INTO sql_execution_logs (user_id, username, sql_text, execution_time_ms, " +
                    "row_count, is_slow_query, status, error_message, ip_address, created_at) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NOW())";
                
                jdbcTemplate.update(sql,
                    execLog.getUserId(),
                    execLog.getUsername(),
                    execLog.getSqlText(),
                    execLog.getExecutionTimeMs(),
                    execLog.getRowCount(),
                    execLog.isSlowQuery() ? 1 : 0,
                    execLog.getStatus(),
                    execLog.getErrorMessage(),
                    execLog.getIpAddress()
                );
                log.debug("使用JdbcTemplate记录SQL执行日志（降级模式）");
            }
            
            log.debug("SQL执行日志记录成功: userId={}, status={}", execLog.getUserId(), execLog.getStatus());
            
        } catch (Exception e) {
            log.error("记录SQL执行日志失败", e);
        }
    }
    
    /**
     * 查询执行日志
     */
    public List<Map<String, Object>> queryLogs(QueryCondition condition) {
        try {
            StringBuilder sql = new StringBuilder(
                "SELECT id, user_id, username, sql_text, execution_time_ms, " +
                "row_count, is_slow_query, status, error_message, ip_address, created_at " +
                "FROM sql_execution_logs WHERE 1=1"
            );
            
            if (condition.getUserId() != null) {
                sql.append(" AND user_id = ").append(condition.getUserId());
            }
            
            if (condition.getStartDate() != null) {
                sql.append(" AND created_at >= '").append(condition.getStartDate()).append("'");
            }
            
            if (condition.getEndDate() != null) {
                sql.append(" AND created_at <= '").append(condition.getEndDate()).append("'");
            }
            
            if (condition.getIsSlowQuery() != null) {
                sql.append(" AND is_slow_query = ").append(condition.getIsSlowQuery() ? 1 : 0);
            }
            
            if (condition.getStatus() != null && !condition.getStatus().isEmpty()) {
                sql.append(" AND status = '").append(condition.getStatus()).append("'");
            }
            
            sql.append(" ORDER BY created_at DESC LIMIT ").append(condition.getLimit());
            
            return jdbcTemplate.queryForList(sql.toString());
            
        } catch (Exception e) {
            log.error("查询执行日志失败", e);
            return java.util.Collections.emptyList();
        }
    }
    
    /**
     * 获取统计信息
     */
    public Map<String, Object> getStatistics(Long userId, String startDate, String endDate) {
        try {
            StringBuilder sql = new StringBuilder(
                "SELECT " +
                "COUNT(*) as total_count, " +
                "SUM(CASE WHEN is_slow_query = 1 THEN 1 ELSE 0 END) as slow_count, " +
                "SUM(CASE WHEN status = 'FAILED' THEN 1 ELSE 0 END) as failed_count, " +
                "SUM(CASE WHEN status = 'TIMEOUT' THEN 1 ELSE 0 END) as timeout_count, " +
                "AVG(execution_time_ms) as avg_time, " +
                "MAX(execution_time_ms) as max_time " +
                "FROM sql_execution_logs WHERE 1=1"
            );
            
            if (userId != null) {
                sql.append(" AND user_id = ").append(userId);
            }
            
            if (startDate != null) {
                sql.append(" AND created_at >= '").append(startDate).append("'");
            }
            
            if (endDate != null) {
                sql.append(" AND created_at <= '").append(endDate).append("'");
            }
            
            List<Map<String, Object>> result = jdbcTemplate.queryForList(sql.toString());
            return result.isEmpty() ? java.util.Collections.emptyMap() : result.get(0);
            
        } catch (Exception e) {
            log.error("获取统计信息失败", e);
            return java.util.Collections.emptyMap();
        }
    }
    
    @Data
    public static class ExecutionLog {
        private Long userId;
        private String username;
        private String sqlText;
        private Long executionTimeMs;
        private Integer rowCount;
        private boolean isSlowQuery;
        private String status;
        private String errorMessage;
        private String ipAddress;
    }
    
    @Data
    public static class QueryCondition {
        private Long userId;
        private String startDate;
        private String endDate;
        private Boolean isSlowQuery;
        private String status;
        private int limit = 100;
    }
}
