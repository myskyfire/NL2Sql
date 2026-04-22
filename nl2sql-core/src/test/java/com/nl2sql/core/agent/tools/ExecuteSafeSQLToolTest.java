package com.nl2sql.core.agent.tools;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ExecuteSafeSQLTool 单元测试
 */
class ExecuteSafeSQLToolTest {
    
    @Mock
    private SQLExecutionTool sqlExecutionTool;
    
    @InjectMocks
    private ExecuteSafeSQLTool tool;
    
    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }
    
    @Test
    void testExecuteSQLSuccess() throws Exception {
        // 准备测试数据
        String sql = "SELECT * FROM orders LIMIT 10";
        Long datasourceId = 1L;
        Long userId = 123L;
        String username = "test_user";
        
        List<Map<String, Object>> mockData = new ArrayList<>();
        Map<String, Object> row = new HashMap<>();
        row.put("id", 1);
        row.put("name", "订单1");
        mockData.add(row);
        
        SQLExecutionTool.ExecutionResult execResult = new SQLExecutionTool.ExecutionResult(
            true, mockData, mockData.size(), 150.0, null
        );
        
        when(sqlExecutionTool.executeSQL(eq(sql), eq(datasourceId), eq(userId), eq(username)))
            .thenReturn(execResult);
        
        // 执行测试
        String result = tool.executeSafeSQL(sql, datasourceId, userId, username);
        
        // 验证结果
        assertNotNull(result);
        assertTrue(result.contains("\"success\":true"));
        assertTrue(result.contains("\"rowCount\":1"));
        assertTrue(result.contains("\"executionTime\":150"));
        assertTrue(result.contains("\"data\""));
        
        verify(sqlExecutionTool, times(1)).executeSQL(sql, datasourceId, userId, username);
    }
    
    @Test
    void testExecuteSQLFailure() throws Exception {
        // 测试执行失败
        String sql = "SELECT * FROM non_existent_table";
        
        SQLExecutionTool.ExecutionResult execResult = new SQLExecutionTool.ExecutionResult(
            false, null, 0, null, "表不存在"
        );
        
        when(sqlExecutionTool.executeSQL(anyString(), anyLong(), anyLong(), anyString()))
            .thenReturn(execResult);
        
        String result = tool.executeSafeSQL(sql, 1L, 123L, "user");
        
        assertNotNull(result);
        assertTrue(result.contains("\"success\":false"));
        assertTrue(result.contains("\"error\":\"表不存在\""));
    }
    
    @Test
    void testExecuteSQLWithEmptyResult() throws Exception {
        // 测试空结果集
        String sql = "SELECT * FROM orders WHERE id = -1";
        
        SQLExecutionTool.ExecutionResult execResult = new SQLExecutionTool.ExecutionResult(
            true, new ArrayList<>(), 0, 50.0, null
        );
        
        when(sqlExecutionTool.executeSQL(anyString(), anyLong(), anyLong(), anyString()))
            .thenReturn(execResult);
        
        String result = tool.executeSafeSQL(sql, 1L, 123L, "user");
        
        assertNotNull(result);
        assertTrue(result.contains("\"success\":true"));
        assertTrue(result.contains("\"rowCount\":0"));
    }
    
    @Test
    void testExecuteSQLException() throws Exception {
        // 模拟异常
        when(sqlExecutionTool.executeSQL(anyString(), anyLong(), anyLong(), anyString()))
            .thenThrow(new RuntimeException("数据库连接超时"));
        
        String result = tool.executeSafeSQL("SELECT 1", 1L, 123L, "user");
        
        assertNotNull(result);
        assertTrue(result.contains("\"success\":false"));
        assertTrue(result.contains("\"error\""));
        assertTrue(result.contains("数据库连接超时"));
    }
    
    @Test
    void testExecuteSQLWithNullDatasourceId() throws Exception {
        // 测试空数据源ID
        SQLExecutionTool.ExecutionResult execResult = new SQLExecutionTool.ExecutionResult(
            true, new ArrayList<>(), 0, null, null
        );
        
        when(sqlExecutionTool.executeSQL(anyString(), isNull(), anyLong(), anyString()))
            .thenReturn(execResult);
        
        String result = tool.executeSafeSQL("SELECT 1", null, 123L, "user");
        
        assertNotNull(result);
        assertTrue(result.contains("\"success\":true"));
    }
}
