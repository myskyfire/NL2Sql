package com.nl2sql.core.agent;

import com.nl2sql.core.agent.context.AgentContext;
import com.nl2sql.core.agent.intent.IntentClassifier;
import com.nl2sql.core.agent.monitoring.SkillMetricsRecorder;
import com.nl2sql.core.agent.routing.RoutingResult;
import com.nl2sql.core.agent.routing.RoutingStrategy;
import com.nl2sql.core.agent.routing.SkillRouter;
import com.nl2sql.core.agent.tool.ToolVisibility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P1 阶段架构改造单元测试
 * 
 * 测试范围：
 * - P1-1: 显式意图路由层（SkillRouter）
 * - P1-2: 工具可见性（ToolVisibility）
 * - P1-3: 监控指标（SkillMetricsRecorder）
 * - P1-4: 统一上下文（AgentContext）
 */
@DisplayName("P1 阶段架构改造单元测试")
public class P1ArchitectureTest {
    
    private SkillRouter skillRouter;
    private SkillMetricsRecorder metricsRecorder;
    private IntentClassifier intentClassifier;
    
    @BeforeEach
    void setUp() {
        // 手动创建实例
        intentClassifier = new IntentClassifier();
        skillRouter = new SkillRouter(intentClassifier);
        skillRouter.init();  // ✅ 手动初始化路由规则
        metricsRecorder = new SkillMetricsRecorder();
    }
    
    // ==================== P1-1: 显式意图路由层测试 ====================
    
    @Test
    @DisplayName("P1-1: 高置信度 QUERY 意图应直接路由")
    void testDirectRoutingForQueryIntent() {
        // Given: 明确的查询意图（使用显式标记）
        String userMessage = "[INTENT:QUERY] 查询订单表的数据";
        Long datasourceId = 1L;
        
        // When: 执行路由
        RoutingResult result = skillRouter.route(userMessage, datasourceId);
        
        // Then: 应该是 DIRECT 策略，推荐 execute_standard_query
        assertEquals(RoutingStrategy.DIRECT, result.getStrategy());
        assertEquals(1, result.getRecommendedSkills().size());
        assertTrue(result.getRecommendedSkills().contains("execute_standard_query"));
        assertEquals(1.0, result.getConfidence(), 0.01);  // 显式标记 confidence=1.0
    }
    
    @Test
    @DisplayName("P1-1: 缺少 datasourceId 时应路由到澄清")
    void testClarifyWhenDatasourceIdMissing() {
        // Given: 查询意图但缺少数据源ID（使用显式标记）
        String userMessage = "[INTENT:QUERY] 查询订单表的数据";
        Long datasourceId = null;
        
        // When: 执行路由
        RoutingResult result = skillRouter.route(userMessage, datasourceId);
        
        // Then: 应该路由到 clarify_datasource
        assertEquals(RoutingStrategy.DIRECT, result.getStrategy());
        assertEquals("clarify_datasource", result.getRecommendedSkills().get(0));
    }
    
    @Test
    @DisplayName("P1-1: 低置信度意图应降级到 FALLBACK")
    void testFallbackForLowConfidence() {
        // Given: 模糊的消息（无显式标记，规则未匹配）
        String userMessage = "asdfghjkl";
        Long datasourceId = 1L;
        
        // When: 执行路由
        RoutingResult result = skillRouter.route(userMessage, datasourceId);
        
        // Then: 由于默认是 QUERY (confidence=0.6)，应该是 LLM_ASSISTED
        assertEquals(RoutingStrategy.LLM_ASSISTED, result.getStrategy());
        assertFalse(result.getRecommendedSkills().isEmpty());
    }
    
    @Test
    @DisplayName("P1-1: SUMMARY 意图应直接路由到 summarize_result")
    void testDirectRoutingForSummaryIntent() {
        // Given: 总结意图（使用显式标记）
        String userMessage = "[INTENT:AI_SUMMARY] 总结一下刚才的查询结果";
        Long datasourceId = 1L;
        
        // When: 执行路由
        RoutingResult result = skillRouter.route(userMessage, datasourceId);
        
        // Then: 应该路由到 summarize_result
        assertEquals(RoutingStrategy.DIRECT, result.getStrategy());
        assertEquals("summarize_result", result.getRecommendedSkills().get(0));
    }
    
    // ==================== P1-2: 工具可见性测试 ====================
    
    @Test
    @DisplayName("P1-2: ToolVisibility 枚举应包含 PUBLIC 和 INTERNAL")
    void testToolVisibilityEnum() {
        // Then: 枚举值存在
        assertNotNull(ToolVisibility.PUBLIC);
        assertNotNull(ToolVisibility.INTERNAL);
        assertEquals(2, ToolVisibility.values().length);
    }
    
    @Test
    @DisplayName("P1-2: ToolDefinitionConverter 应支持过滤 INTERNAL tools")
    void testToolDefinitionConverterFiltering() {
        // Given: 混合 PUBLIC 和 INTERNAL tools
        Map<String, ReActAgent.ToolExecutor> tools = Map.of(
            "public_skill", createMockExecutor(ToolVisibility.PUBLIC),
            "internal_tool", createMockExecutor(ToolVisibility.INTERNAL)
        );
        
        // When: 转换为 OpenAI 格式（不包含 INTERNAL）
        List<Map<String, Object>> publicTools = ToolDefinitionConverter.convertToOpenAITools(tools, false);
        List<Map<String, Object>> allTools = ToolDefinitionConverter.convertToOpenAITools(tools, true);
        
        // Then: 正确过滤
        assertEquals(1, publicTools.size());
        assertEquals(2, allTools.size());
    }
    
    private ReActAgent.ToolExecutor createMockExecutor(ToolVisibility visibility) {
        return new ReActAgent.ToolExecutor() {
            @Override
            public String execute(Map<String, Object> arguments, Long datasourceId, 
                                 Long userId, String username, String userMessage) {
                return "{}";
            }
            
            @Override
            public ToolVisibility getVisibility() {
                return visibility;
            }
        };
    }
    
    // ==================== P1-3: 监控指标测试 ====================
    
    @Test
    @DisplayName("P1-3: 记录成功执行应更新指标")
    void testRecordSuccess() {
        // Given
        String skillName = "test_skill";
        
        // When: 记录成功执行
        metricsRecorder.recordStart(skillName);
        metricsRecorder.recordSuccess(skillName, 100);
        metricsRecorder.recordSuccess(skillName, 200);
        
        // Then: 指标正确
        SkillMetricsRecorder.SkillMetrics metrics = metricsRecorder.getMetrics(skillName);
        assertNotNull(metrics);
        assertEquals(2, metrics.getTotalCalls().get());
        assertEquals(2, metrics.getSuccessCalls().get());
        assertEquals(0, metrics.getFailedCalls().get());
        assertEquals(150.0, metrics.getAverageExecutionTimeMs(), 0.1);
        assertEquals(200, metrics.getMaxExecutionTimeMs().get());
        assertEquals(1.0, metrics.getSuccessRate(), 0.01);
    }
    
    @Test
    @DisplayName("P1-3: 记录失败执行应更新指标")
    void testRecordFailure() {
        // Given
        String skillName = "test_skill";
        
        // When: 记录成功和失败
        metricsRecorder.recordSuccess(skillName, 100);
        metricsRecorder.recordFailure(skillName, 50, "Error message");
        
        // Then: 指标正确
        SkillMetricsRecorder.SkillMetrics metrics = metricsRecorder.getMetrics(skillName);
        assertEquals(2, metrics.getTotalCalls().get());
        assertEquals(1, metrics.getSuccessCalls().get());
        assertEquals(1, metrics.getFailedCalls().get());
        assertEquals(0.5, metrics.getSuccessRate(), 0.01);
    }
    
    @Test
    @DisplayName("P1-3: 重置指标应清空所有数据")
    void testResetMetrics() {
        // Given: 已有指标
        metricsRecorder.recordSuccess("skill1", 100);
        metricsRecorder.recordSuccess("skill2", 200);
        
        // When: 重置
        metricsRecorder.resetAll();
        
        // Then: 所有指标清空
        assertTrue(metricsRecorder.getAllMetrics().isEmpty());
    }
    
    // ==================== P1-4: 统一上下文测试 ====================
    
    @Test
    @DisplayName("P1-4: AgentContext 应正确存储基本字段")
    void testAgentContextBasicFields() {
        // Given & When: 创建上下文
        AgentContext context = AgentContext.builder()
            .userId(123L)
            .username("test_user")
            .sessionId("session_001")
            .datasourceId(1L)
            .lastSQL("SELECT * FROM orders")
            .lastQuery("查询订单")
            .build();
        
        // Then: 字段正确
        assertEquals(123L, context.getUserId());
        assertEquals("test_user", context.getUsername());
        assertEquals("session_001", context.getSessionId());
        assertEquals(1L, context.getDatasourceId());
        assertEquals("SELECT * FROM orders", context.getLastSQL());
        assertEquals("查询订单", context.getLastQuery());
    }
    
    @Test
    @DisplayName("P1-4: AgentContext metadata 应支持扩展字段")
    void testAgentContextMetadata() {
        // Given: 创建上下文
        AgentContext context = AgentContext.builder().build();
        
        // When: 设置元数据
        context.setMetadata("custom_key", "custom_value");
        context.setMetadata("number_key", 42);
        
        // Then: 正确获取
        assertEquals("custom_value", context.getMetadata("custom_key", String.class));
        assertEquals(42, context.getMetadata("number_key", Integer.class));
    }
    
    @Test
    @DisplayName("P1-4: AgentContext 不存在 key 应返回 null")
    void testAgentContextMetadataNotFound() {
        // Given: 空上下文
        AgentContext context = AgentContext.builder().build();
        
        // When & Then: 获取不存在的 key
        assertNull(context.getMetadata("non_existent", String.class));
    }
}
