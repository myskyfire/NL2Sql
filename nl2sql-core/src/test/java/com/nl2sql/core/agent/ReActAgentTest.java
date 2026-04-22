package com.nl2sql.core.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nl2sql.core.llm.LLMService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ReActAgent 单元测试
 * 
 * 测试核心功能：
 * 1. Tool注册与执行
 * 2. ReAct循环逻辑
 * 3. 结构化数据检测
 */
@ExtendWith(MockitoExtension.class)
class ReActAgentTest {
    
    @Mock
    private LLMService llmService;
    
    private ReActAgent agent;
    private ObjectMapper objectMapper;
    
    @BeforeEach
    void setUp() {
        agent = new ReActAgent(llmService);
        objectMapper = new ObjectMapper();
    }
    
    @Test
    void testToolRegistration() {
        // 注册工具
        agent.registerTool("test_tool", (args, dsId, userId, username, userMessage) -> {
            return "{\"status\":\"success\",\"message\":\"test\"}";
        }, "测试工具");
        
        assertEquals(1, agent.getToolCount());
        assertTrue(agent.getTools().containsKey("test_tool"));
    }
    
    @Test
    void testExecuteWithDirectAnswer() throws Exception {
        // 模拟LLM直接返回答案（无tool_calls）
        Map<String, Object> llmResponse = new HashMap<>();
        Map<String, Object> message = new HashMap<>();
        message.put("role", "assistant");
        message.put("content", "这是最终答案");
        message.put("tool_calls", null);
        llmResponse.put("message", message);
        
        when(llmService.generateWithTools(anyList(), anyDouble(), anyList()))
            .thenReturn(llmResponse);
        
        String result = agent.execute("测试问题", 1L, 123L, "user", null);
        
        assertEquals("这是最终答案", result);
        verify(llmService, times(1)).generateWithTools(anyList(), anyDouble(), anyList());
    }
    
    @Test
    void testExecuteWithToolCall() throws Exception {
        // 第一次调用：返回tool_calls
        Map<String, Object> firstResponse = createToolCallResponse("test_tool", "{\"param\":\"value\"}");
        
        // 第二次调用：返回最终答案
        Map<String, Object> secondResponse = createDirectAnswerResponse("工具执行完成");
        
        when(llmService.generateWithTools(anyList(), anyDouble(), anyList()))
            .thenReturn(firstResponse)
            .thenReturn(secondResponse);
        
        // 注册测试工具（返回非结构化数据，触发第二轮LLM调用）
        agent.registerTool("test_tool", (args, dsId, userId, username, userMessage) -> {
            return "工具执行结果";  // 非JSON格式，不会直接返回
        }, "测试工具");
        
        String result = agent.execute("测试问题", 1L, 123L, "user", null);
        
        assertEquals("工具执行完成", result);
        verify(llmService, times(2)).generateWithTools(anyList(), anyDouble(), anyList());
    }
    
    @Test
    void testExecuteWithStructuredData() throws Exception {
        // 模拟工具返回结构化数据
        Map<String, Object> toolCallResponse = createToolCallResponse("query_tool", "{}");
        
        when(llmService.generateWithTools(anyList(), anyDouble(), anyList()))
            .thenReturn(toolCallResponse);
        
        // 注册返回结构化数据的工具
        agent.registerTool("query_tool", (args, dsId, userId, username, userMessage) -> {
            return "{\"status\":\"success\",\"data\":[{\"id\":1,\"name\":\"test\"}],\"rowCount\":1}";
        }, "查询工具");
        
        String result = agent.execute("查询数据", 1L, 123L, "user");
        
        // 应该直接返回结构化数据，不再调用LLM
        assertTrue(result.contains("\"status\":\"success\""));
        assertTrue(result.contains("\"data\""));
        verify(llmService, times(1)).generateWithTools(anyList(), anyDouble(), anyList());
    }
    
    @Test
    void testExecuteMaxIterations() throws Exception {
        // 模拟LLM一直返回tool_calls，触发最大迭代限制
        Map<String, Object> toolCallResponse = createToolCallResponse("loop_tool", "{}");
        
        when(llmService.generateWithTools(anyList(), anyDouble(), anyList()))
            .thenReturn(toolCallResponse);
        
        agent.registerTool("loop_tool", (args, dsId, userId, username, userMessage) -> {
            return "继续循环";
        }, "循环工具");
        
        String result = agent.execute("测试问题", 1L, 123L, "user", null);
        
        assertTrue(result.contains("超过最大迭代次数"));
        verify(llmService, times(10)).generateWithTools(anyList(), anyDouble(), anyList());
    }
    
    @Test
    void testExecuteWithNullDatasourceId() throws Exception {
        Map<String, Object> response = createDirectAnswerResponse("请先选择数据源");
        
        when(llmService.generateWithTools(anyList(), anyDouble(), anyList()))
            .thenReturn(response);
        
        String result = agent.execute("测试问题", null, 123L, "user", null);
        
        assertEquals("请先选择数据源", result);
    }
    
    @Test
    void testUnknownTool() throws Exception {
        // 第一次调用：请求不存在的工具
        Map<String, Object> firstResponse = createToolCallResponse("unknown_tool", "{}");
        
        // 第二次调用：返回错误提示
        Map<String, Object> secondResponse = createDirectAnswerResponse("未知工具");
        
        when(llmService.generateWithTools(anyList(), anyDouble(), anyList()))
            .thenReturn(firstResponse)
            .thenReturn(secondResponse);
        
        String result = agent.execute("测试问题", 1L, 123L, "user", null);
        
        assertEquals("未知工具", result);
        verify(llmService, times(2)).generateWithTools(anyList(), anyDouble(), anyList());
    }
    
    // ==================== 辅助方法 ====================
    
    private Map<String, Object> createToolCallResponse(String toolName, String arguments) {
        Map<String, Object> response = new HashMap<>();
        Map<String, Object> message = new HashMap<>();
        message.put("role", "assistant");
        message.put("content", "");
        
        List<Map<String, Object>> toolCalls = new ArrayList<>();
        Map<String, Object> toolCall = new HashMap<>();
        Map<String, Object> function = new HashMap<>();
        function.put("name", toolName);
        function.put("arguments", arguments);
        toolCall.put("function", function);
        toolCalls.add(toolCall);
        
        message.put("tool_calls", toolCalls);
        response.put("message", message);
        
        return response;
    }
    
    private Map<String, Object> createDirectAnswerResponse(String content) {
        Map<String, Object> response = new HashMap<>();
        Map<String, Object> message = new HashMap<>();
        message.put("role", "assistant");
        message.put("content", content);
        message.put("tool_calls", null);
        response.put("message", message);
        
        return response;
    }
}
