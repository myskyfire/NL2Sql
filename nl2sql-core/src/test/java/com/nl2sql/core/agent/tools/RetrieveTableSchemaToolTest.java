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
 * RetrieveTableSchemaTool 单元测试
 */
class RetrieveTableSchemaToolTest {
    
    @Mock
    private NL2SQLTool nl2sqlTool;
    
    @InjectMocks
    private RetrieveTableSchemaTool tool;
    
    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }
    
    @Test
    void testRetrieveTableSchemaSuccess() throws Exception {
        // 准备测试数据
        String question = "查询订单信息";
        Long datasourceId = 1L;
        String expectedSchema = "表名: orders";
        
        when(nl2sqlTool.retrieveSchema(eq(question), eq(datasourceId)))
            .thenReturn(expectedSchema);
        
        // 执行测试
        String result = tool.retrieveTableSchema(question, datasourceId);
        
        // 验证结果
        assertNotNull(result);
        assertTrue(result.contains("\"success\":true"));
        assertTrue(result.contains("\"schema\":\"表名: orders\""));
        
        verify(nl2sqlTool, times(1)).retrieveSchema(question, datasourceId);
    }
    
    @Test
    void testRetrieveTableSchemaWithNullQuestion() throws Exception {
        // 测试空问题
        when(nl2sqlTool.retrieveSchema(isNull(), anyLong()))
            .thenReturn("");
        
        String result = tool.retrieveTableSchema(null, 1L);
        
        assertNotNull(result);
        assertTrue(result.contains("\"success\":true"));
    }
    
    @Test
    void testRetrieveTableSchemaException() throws Exception {
        // 模拟异常
        when(nl2sqlTool.retrieveSchema(anyString(), anyLong()))
            .thenThrow(new RuntimeException("数据库连接失败"));
        
        String result = tool.retrieveTableSchema("查询", 1L);
        
        assertNotNull(result);
        assertTrue(result.contains("\"success\":false"));
        assertTrue(result.contains("\"error\""));
    }
}
