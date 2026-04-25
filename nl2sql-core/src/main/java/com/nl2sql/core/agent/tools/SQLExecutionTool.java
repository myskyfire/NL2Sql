package com.nl2sql.core.agent.tools;

import com.nl2sql.core.agent.validation.SQLValidationService;
import com.nl2sql.core.executor.SQLExecutor;
import com.nl2sql.core.rag.RagAutoLearner;
import com.nl2sql.core.rag.RagLearningContext;
import com.nl2sql.core.service.NL2SQLService;
import dev.langchain4j.agent.tool.Tool;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * SQL执行 Tool - 执行SQL并返回结果
 */
@Slf4j
@Component
public class SQLExecutionTool {
    
    @Autowired
    private SQLExecutor sqlExecutor;
    
    @Autowired
    private NL2SQLService nl2sqlService;  // 用于SQL修正
    
    @Autowired(required = false)
    private RagAutoLearner ragAutoLearner;  // RAG自动学习器
    
    /**
     * 执行SQL查询（带智能重试）
     * 
     * @param sql SQL语句
     * @param datasourceId 数据源ID
     * @param userId 用户ID
     * @param username 用户名
     * @return 执行结果（包含数据和元信息）
     */
    @Tool("执行SQL查询并返回结果数据")
    public ExecutionResult executeSQL(String sql, Long datasourceId, Long userId, String username) {
        return executeSQLWithRetry(sql, datasourceId, userId, username, 3);
    }
    
    /**
     * 带智能重试的SQL执行
     * 
     * @param sql SQL语句
     * @param datasourceId 数据源ID
     * @param userId 用户ID
     * @param username 用户名
     * @param maxRetries 最大重试次数
     * @return 执行结果
     */
    private ExecutionResult executeSQLWithRetry(String sql, Long datasourceId, Long userId, 
                                                String username, int maxRetries) {
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                log.info("[SQLExecutionTool] 开始执行SQL (attempt={}): datasourceId={}", attempt, datasourceId);
                
                SQLExecutor.QueryResult result = sqlExecutor.executeQuery(
                    sql, datasourceId, userId, username, "unknown"
                );
                
                if (result.getError() != null) {
                    log.error("[SQLExecutionTool] 执行失败 (attempt={}): {}", attempt, result.getError());
                    
                    // 如果是最后一次尝试，直接返回错误
                    if (attempt == maxRetries) {
                        return new ExecutionResult(false, null, 0, 0.0, result.getError());
                    }
                    
                    // 根据错误类型决定是否重试
                    ErrorType errorType = classifyError(result.getError());
                    if (!errorType.isRetryable()) {
                        log.warn("[SQLExecutionTool] 错误类型不可重试: {}", errorType);
                        return new ExecutionResult(false, null, 0, 0.0, result.getError());
                    }
                    
                    // 尝试修正SQL并重试
                    log.info("[SQLExecutionTool] 尝试修正SQL并重试 (attempt={})", attempt + 1);
                    sql = attemptAutoCorrection(sql, result.getError(), errorType, datasourceId);
                    continue;
                }
                
                log.info("[SQLExecutionTool] 执行成功: rowCount={}, executionTime={}ms", 
                    result.getRowCount(), result.getExecutionTime());
                
                // ⚠️ P0优化：触发 RAG 自动学习（从执行结果中学习）
                if (ragAutoLearner != null) {
                    try {
                        String question = RagLearningContext.getCurrentQuestion();
                        String currentSql = RagLearningContext.getCurrentSql();
                        
                        if (question != null && currentSql != null) {
                            ragAutoLearner.learnFromExecution(
                                question, 
                                currentSql, 
                                result.getRowCount(), 
                                (long) result.getExecutionTime()
                            );
                            
                            // 清理上下文
                            RagLearningContext.clear();
                        }
                    } catch (Exception e) {
                        log.warn("[SQLExecutionTool] RAG学习触发失败: {}", e.getMessage());
                    }
                }
                
                return new ExecutionResult(
                    true,
                    result.getData(),
                    result.getRowCount(),
                    result.getExecutionTime(),
                    null,
                    sql  // ✅ 返回实际执行的SQL（可能是修正后的）
                );
                
            } catch (Exception e) {
                log.error("[SQLExecutionTool] 执行异常 (attempt={})", attempt, e);
                
                if (attempt == maxRetries) {
                    return new ExecutionResult(false, null, 0, 0.0, e.getMessage());
                }
                
                // 网络异常等可以重试
                sql = attemptAutoCorrection(sql, e.getMessage(), ErrorType.NETWORK_ERROR, datasourceId);
            }
        }
        
        return new ExecutionResult(false, null, 0, 0.0, "达到最大重试次数");
    }
    
    /**
     * 错误分类
     */
    private ErrorType classifyError(String errorMessage) {
        if (errorMessage == null) {
            return ErrorType.UNKNOWN;
        }
        
        String lowerMsg = errorMessage.toLowerCase();
        
        if (lowerMsg.contains("table") && lowerMsg.contains("doesn't exist")) {
            return ErrorType.TABLE_NOT_FOUND;
        }
        if (lowerMsg.contains("unknown column") || lowerMsg.contains("column") && lowerMsg.contains("not found")) {
            return ErrorType.COLUMN_NOT_FOUND;
        }
        if (lowerMsg.contains("syntax error") || lowerMsg.contains("sql syntax")) {
            return ErrorType.SYNTAX_ERROR;
        }
        if (lowerMsg.contains("ambiguous")) {
            return ErrorType.AMBIGUOUS_COLUMN;
        }
        if (lowerMsg.contains("timeout") || lowerMsg.contains("timed out")) {
            return ErrorType.TIMEOUT;
        }
        if (lowerMsg.contains("connection") || lowerMsg.contains("network")) {
            return ErrorType.NETWORK_ERROR;
        }
        
        return ErrorType.UNKNOWN;
    }
    
    /**
     * 尝试自动修正SQL
     */
    private String attemptAutoCorrection(String failedSql, String errorMessage, ErrorType errorType, Long datasourceId) {
        switch (errorType) {
            case SYNTAX_ERROR:
            case TABLE_NOT_FOUND:
            case COLUMN_NOT_FOUND:
            case AMBIGUOUS_COLUMN:
                // ✅ 关键修复：传入 datasourceId，让 autoFixSQL 能获取表结构
                return nl2sqlService.autoFixSQL(failedSql, errorMessage, datasourceId);
            
            case TIMEOUT:
                // 超时错误，添加 LIMIT 限制
                log.info("[SQLExecutionTool] 超时错误，尝试添加 LIMIT 100");
                if (!failedSql.toUpperCase().contains("LIMIT")) {
                    return failedSql.trim() + " LIMIT 100";
                }
                break;
            
            case NETWORK_ERROR:
                // 网络错误，无需修正SQL
                log.info("[SQLExecutionTool] 网络错误，直接重试");
                break;
            
            default:
                log.warn("[SQLExecutionTool] 未知错误类型，不修正SQL");
        }
        
        return failedSql;
    }
    
    /**
     * 错误类型枚举
     */
    private enum ErrorType {
        SYNTAX_ERROR(true),           // 语法错误，可修正
        TABLE_NOT_FOUND(true),        // 表不存在，可修正
        COLUMN_NOT_FOUND(true),       // 字段不存在，可修正
        AMBIGUOUS_COLUMN(true),       // 歧义字段，可修正
        TIMEOUT(true),                // 超时，可添加LIMIT重试
        NETWORK_ERROR(true),          // 网络错误，可直接重试
        UNKNOWN(false);               // 未知错误，不重试
        
        private final boolean retryable;
        
        ErrorType(boolean retryable) {
            this.retryable = retryable;
        }
        
        public boolean isRetryable() {
            return retryable;
        }
    }
    
    @Data
    public static class ExecutionResult {
        public boolean success;
        public List<Map<String, Object>> data;
        public int rowCount;
        public Double executionTime;  // 改为Double匹配SQLExecutor.QueryResult
        public String error;
        public String sql;  // ✅ 返回实际执行的SQL（可能是修正后的）
        
        public ExecutionResult(boolean success, List<Map<String, Object>> data, 
                              int rowCount, Double executionTime, String error) {
            this.success = success;
            this.data = data;
            this.rowCount = rowCount;
            this.executionTime = executionTime;
            this.error = error;
            this.sql = null;
        }
        
        public ExecutionResult(boolean success, List<Map<String, Object>> data, 
                              int rowCount, Double executionTime, String error, String sql) {
            this.success = success;
            this.data = data;
            this.rowCount = rowCount;
            this.executionTime = executionTime;
            this.error = error;
            this.sql = sql;
        }
    }
}
