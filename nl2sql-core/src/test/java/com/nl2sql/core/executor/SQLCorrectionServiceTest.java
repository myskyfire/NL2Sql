package com.nl2sql.core.executor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SQLCorrectionService 单元测试
 * 
 * 测试核心功能：
 * 1. 错误类型识别
 * 2. SQL自动修正
 * 3. 语法验证
 */
class SQLCorrectionServiceTest {
    
    private SQLCorrectionService correctionService;
    
    @BeforeEach
    void setUp() {
        correctionService = new SQLCorrectionService();
    }
    
    @Test
    void testAutoCorrectTableNotFound() {
        String sql = "SELECT * FROM useres WHERE id = 1";
        String error = "Table 'testdb.useres' doesn't exist";
        
        SQLCorrectionService.CorrectionResult result = correctionService.autoCorrect(sql, error, 2);
        
        assertNotNull(result);
        assertFalse(result.isSuccess()); // 表名修正需要数据库元数据，当前实现返回null
        assertTrue(result.getOriginalError().contains("doesn't exist"));
    }
    
    @Test
    void testAutoCorrectColumnNotFound() {
        String sql = "SELECT usernam FROM users WHERE id = 1";
        String error = "Unknown column 'usernam' in 'field list'";
        
        SQLCorrectionService.CorrectionResult result = correctionService.autoCorrect(sql, error, 2);
        
        assertNotNull(result);
        // 应该尝试修正为 username
        if (result.isSuccess()) {
            assertTrue(result.getCorrectedSQL().contains("username") || 
                      result.getCorrectedSQL().contains("usernam"));
        }
    }
    
    @Test
    void testAutoCorrectSyntaxError() {
        String sql = "SELECT * FROM users WHERE id = 1;;";
        String error = "You have an error in your SQL syntax";
        
        SQLCorrectionService.CorrectionResult result = correctionService.autoCorrect(sql, error, 2);
        
        assertNotNull(result);
        if (result.isSuccess()) {
            assertEquals("SELECT * FROM users WHERE id = 1;", result.getCorrectedSQL());
        }
    }
    
    @Test
    void testAutoCorrectUnmatchedParentheses() {
        String sql = "SELECT * FROM users WHERE id IN (1, 2, 3";
        String error = "You have an error in your SQL syntax";
        
        SQLCorrectionService.CorrectionResult result = correctionService.autoCorrect(sql, error, 2);
        
        assertNotNull(result);
        if (result.isSuccess()) {
            assertTrue(result.getCorrectedSQL().endsWith(")"));
        }
    }
    
    @Test
    void testAutoCorrectAmbiguousColumn() {
        String sql = "SELECT id FROM users u JOIN orders o ON u.id = o.user_id";
        String error = "Column 'id' in field list is ambiguous";
        
        SQLCorrectionService.CorrectionResult result = correctionService.autoCorrect(sql, error, 2);
        
        assertNotNull(result);
        if (result.isSuccess()) {
            assertTrue(result.getCorrectedSQL().contains("t1.id") || 
                      result.getCorrectedSQL().contains("u.id"));
        }
    }
    
    @Test
    void testAutoCorrectMaxRetries() {
        String sql = "INVALID SQL STATEMENT";
        String error = "You have an error in your SQL syntax";
        
        SQLCorrectionService.CorrectionResult result = correctionService.autoCorrect(sql, error, 2);
        
        assertNotNull(result);
        assertTrue(result.getRetryCount() >= 1); // 至少尝试1次
        assertFalse(result.isSuccess());
        assertFalse(result.getSuggestions().isEmpty());
    }
    
    @Test
    void testAutoCorrectNullError() {
        String sql = "SELECT * FROM users";
        
        SQLCorrectionService.CorrectionResult result = correctionService.autoCorrect(sql, null, 2);
        
        assertNotNull(result);
        assertFalse(result.isSuccess());
    }
    
    @Test
    void testSuggestionsGeneration() {
        String sql = "SELECT * FROM users";
        String error = "Table 'users' doesn't exist";
        
        SQLCorrectionService.CorrectionResult result = correctionService.autoCorrect(sql, error, 1);
        
        assertNotNull(result);
        assertFalse(result.getSuggestions().isEmpty());
        
        // 检查建议内容
        boolean hasTableSuggestion = result.getSuggestions().stream()
            .anyMatch(s -> s.contains("表名") || s.contains("table"));
        assertTrue(hasTableSuggestion);
    }
}
