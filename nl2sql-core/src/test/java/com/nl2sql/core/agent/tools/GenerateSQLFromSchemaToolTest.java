package com.nl2sql.core.agent.tools;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * GenerateSQLFromSchemaTool 单元测试
 */
class GenerateSQLFromSchemaToolTest {
    
    @Mock
    private NL2SQLTool nl2sqlTool;
    
    @InjectMocks
    private GenerateSQLFromSchemaTool tool;
    
    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }
    
    @Test
    void testGenerateSQLSuccess() throws Exception {
        // 准备测试数据
        String question = "查询最近10条订单";
        String schema = "{\"tables\":[\"orders\"]}";
        Long datasourceId = 1L;
        String expectedSQL = "SELECT * FROM orders ORDER BY created_at DESC LIMIT 10";
        
        when(nl2sqlTool.generateSQL(eq(question), eq(datasourceId)))
            .thenReturn(expectedSQL);
        
        // 执行测试
        String result = tool.generateSQLFromSchema(question, schema, datasourceId);
        
        // 验证结果
        assertNotNull(result);
        assertTrue(result.contains("\"success\":true"));
        assertTrue(result.contains("\"sql\""));
        assertTrue(result.contains(expectedSQL));
        
        verify(nl2sqlTool, times(1)).generateSQL(question, datasourceId);
    }
    
    @Test
    void testGenerateSQLNeedsClarification() throws Exception {
        // 测试需要澄清的场景
        String clarificationMsg = "CLARIFY_请指定时间范围";
        
        when(nl2sqlTool.generateSQL(anyString(), anyLong()))
            .thenReturn(clarificationMsg);
        
        String result = tool.generateSQLFromSchema("查询订单", null, 1L);
        
        assertNotNull(result);
        assertTrue(result.contains("\"success\":false"));
        assertTrue(result.contains("\"needsClarification\":true"));
        assertTrue(result.contains(clarificationMsg));
    }
    
    @Test
    void testGenerateSQLFailure() throws Exception {
        // 测试生成失败
        when(nl2sqlTool.generateSQL(anyString(), anyLong()))
            .thenReturn("错误：无法理解查询意图");
        
        String result = tool.generateSQLFromSchema("模糊查询", null, 1L);
        
        assertNotNull(result);
        assertTrue(result.contains("\"success\":false"));
        assertTrue(result.contains("\"error\""));
    }
    
    @Test
    void testGenerateSQLNullResult() throws Exception {
        // 测试返回null
        when(nl2sqlTool.generateSQL(anyString(), anyLong()))
            .thenReturn(null);
        
        String result = tool.generateSQLFromSchema("查询", null, 1L);
        
        assertNotNull(result);
        assertTrue(result.contains("\"success\":false"));
    }
    
    @Test
    void testGenerateSQLException() throws Exception {
        // 模拟异常
        when(nl2sqlTool.generateSQL(anyString(), anyLong()))
            .thenThrow(new RuntimeException("LLM服务不可用"));
        
        String result = tool.generateSQLFromSchema("查询", null, 1L);
        
        assertNotNull(result);
        assertTrue(result.contains("\"success\":false"));
        assertTrue(result.contains("\"error\""));
    }
}
