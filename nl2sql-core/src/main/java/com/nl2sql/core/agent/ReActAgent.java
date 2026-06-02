package com.nl2sql.core.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nl2sql.common.util.JsonUtils;
import com.nl2sql.core.agent.intent.IntentClassifier;
import com.nl2sql.core.agent.routing.RoutingResult;
import com.nl2sql.core.agent.routing.SkillRouter;
import com.nl2sql.core.agent.skills.SkillResult;
import com.nl2sql.core.agent.tool.ToolVisibility;
import com.nl2sql.core.llm.LLMService;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.stream.Collectors;

/**
 * ReAct Agent - 基于 Ollama 原生 Tool Calling 的推理-行动循环
 * 
 * 工作流程（原生 Tool Calling）：
 * 1. 构建消息列表 + 工具定义（OpenAI 格式）
 * 2. 调用 LLMService.generateWithTools()，传递 tools 参数
 * 3. LLM 返回结构化 tool_calls（无需解析 JSON 文本）
 * 4. 执行工具并获取结果
 * 5. 将工具结果反馈给 LLM（多轮对话）
 * 6. 当 LLM 不再返回 tool_calls 时，输出最终答案
 */
@Slf4j
public class ReActAgent {
    
    private final LLMService llmService;
    private final Map<String, ToolExecutor> tools;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final IntentClassifier intentClassifier;  // ✅ P0: 意图分类器
    private final SkillRouter skillRouter;  // ✅ P1-1: 技能路由器
    
    // ✅ P1优化：降低最大迭代次数，qwen3.5-plus通常2-3次即可完成
    private static final int MAX_ITERATIONS = 5;
    
    /**
     * ✅ P1-1: 构造函数注入 SkillRouter
     */
    public ReActAgent(LLMService llmService, SkillRouter skillRouter) {
        this.llmService = llmService;
        this.tools = new HashMap<>();
        this.intentClassifier = new IntentClassifier();
        this.skillRouter = skillRouter;
        log.info("[ReActAgent] 初始化完成，使用原生 Tool Calling + 意图识别层 + 显式路由");
    }
    
    /**
     * 注册工具（带描述）
     */
    public void registerTool(String name, ToolExecutor executor, String description) {
        // ✅ 包装executor，覆盖getDescription()方法
        ToolExecutor wrappedExecutor = new ToolExecutorWithDescription(executor, description);
        tools.put(name, wrappedExecutor);
        log.info("[ReActAgent] 注册工具: {} - {}", name, description);
    }
    
    /**
     * 获取已注册工具数量
     */
    public int getToolCount() {
        return tools.size();
    }
    
    /**
     * 获取所有注册的工具
     */
    public Map<String, ToolExecutor> getTools() {
        return Collections.unmodifiableMap(tools);
    }
    
    /**
     * 执行 ReAct 循环（使用原生 Tool Calling）
     * ✅ P1-1: 集成显式意图路由层，根据意图直接路由或缩小 LLM 决策范围
     * ✅ 优化：userId/username从UserContext获取，避免层层传参
     * @return JSON字符串（工具结果）或自然语言（LLM回答）
     */
    public String execute(String userMessage, Long datasourceId, 
                         List<Map<String, Object>> historyMessages) {
        // ✅ 从 UserContext 获取用户信息
        Long userId = com.nl2sql.common.context.UserContext.getUserId();
        String username = com.nl2sql.common.context.UserContext.getUsername();
        
        log.info("[ReActAgent] 开始执行，用户消息: {}, datasourceId={}, userId={}, 历史消息数={}", 
            userMessage, datasourceId, userId, historyMessages != null ? historyMessages.size() : 0);
        
        // ✅ P1-1: 意图识别 + 路由
        RoutingResult routing = skillRouter.route(userMessage, datasourceId);
        log.info("[ReActAgent] 路由结果: strategy={}, skills={}, reason={}", 
            routing.getStrategy(), routing.getRecommendedSkills(), routing.getReason());
        
        // 根据路由策略执行
        switch (routing.getStrategy()) {
            case DIRECT:
                // 直接调用推荐的 Skill，跳过 LLM
                return executeDirectSkill(routing.getRecommendedSkills().get(0), 
                                         datasourceId, userId, username, userMessage);
            
            case PLAN_AND_EXECUTE:
                List<Map<String, Object>> allToolsDef = ToolDefinitionConverter.convertToOpenAITools(tools, true);
                List<Map<String, Object>> filteredTools = filterToolsByNames(
                    routing.getRecommendedSkills(), allToolsDef
                );
                log.info("[ReActAgent] Plan-and-Execute, filtered tools: {} -> {}", 
                    allToolsDef.size(), filteredTools.size());
                return executeWithFilteredTools(userMessage, datasourceId, userId, username, 
                                               historyMessages, filteredTools);
            
            case REACT:
            default:
                log.info("[ReActAgent] Full ReAct loop");
                return executeFullReAct(userMessage, datasourceId, userId, username, historyMessages);
        }
    }
    
    /**
     * ✅ P1-1: 直接调用 Skill（跳过 LLM）
     */
    private String executeDirectSkill(String skillName, Long datasourceId, 
                                     Long userId, String username, String userMessage) {
        log.info("[ReActAgent] 直接调用 Skill: {}", skillName);
        
        ToolExecutor executor = tools.get(skillName);
        if (executor == null) {
            log.error("[ReActAgent] 未找到 Skill: {}", skillName);
            return SkillResult.error("SKILL_NOT_FOUND", "未找到 Skill: " + skillName).toJson();
        }
        
        try {
            // 构造参数
            Map<String, Object> arguments = new HashMap<>();
            arguments.put("question", userMessage);
            if (datasourceId != null) {
                arguments.put("datasourceId", datasourceId);
            }
            
            String result = executor.execute(arguments, datasourceId, userId, username, userMessage);
            log.info("[ReActAgent] Skill 执行成功: {}", skillName);
            return result;
        } catch (Exception e) {
            log.error("[ReActAgent] Skill 执行失败: {}", skillName, e);
            return SkillResult.error("SKILL_EXECUTION_ERROR", e.getMessage()).toJson();
        }
    }
    
    /**
     * ✅ P1-1: 根据名称过滤工具定义
     */
    private List<Map<String, Object>> filterToolsByNames(List<String> skillNames, 
                                                          List<Map<String, Object>> allTools) {
        return allTools.stream()
            .filter(tool -> {
                String toolName = (String) ((Map<String, Object>) tool.get("function")).get("name");
                return skillNames.contains(toolName);
            })
            .collect(Collectors.toList());
    }
    
    /**
     * ✅ P1-1: 使用过滤后的工具列表执行 ReAct 循环
     */
    private String executeWithFilteredTools(String userMessage, Long datasourceId,
                                           Long userId, String username,
                                           List<Map<String, Object>> historyMessages,
                                           List<Map<String, Object>> filteredTools) {
        // 复用 executeFullReAct 的逻辑，但使用 filteredTools
        return executeReActLoop(userMessage, datasourceId, userId, username, historyMessages, filteredTools);
    }
    
    /**
     * ✅ P1-1: 完整 ReAct 流程（降级场景）
     */
    private String executeFullReAct(String userMessage, Long datasourceId,
                                   Long userId, String username,
                                   List<Map<String, Object>> historyMessages) {
        // 使用所有 tools（包括 INTERNAL）
        List<Map<String, Object>> allTools = ToolDefinitionConverter.convertToOpenAITools(tools, true);
        return executeReActLoop(userMessage, datasourceId, userId, username, historyMessages, allTools);
    }
    
    /**
     * ✅ P1-1: ReAct 循环核心逻辑（抽取公共部分）
     */
    private String executeReActLoop(String userMessage, Long datasourceId,
                                   Long userId, String username,
                                   List<Map<String, Object>> historyMessages,
                                   List<Map<String, Object>> toolsDef) {
        // 1. 构建消息列表
        List<Map<String, Object>> messages = new ArrayList<>();
        
        // System Message
        Map<String, Object> systemMsg = new HashMap<>();
        systemMsg.put("role", "system");
        systemMsg.put("content", buildSystemPrompt());
        messages.add(systemMsg);
        
        // ✅ 注入历史消息（已过滤clarify_datasource）
        if (historyMessages != null && !historyMessages.isEmpty()) {
            messages.addAll(historyMessages);
            log.info("[ReActAgent] 注入 {} 条历史消息", historyMessages.size());
        }
        
        // User Message（注入数据源上下文）
        String enrichedMessage;
        if (datasourceId != null) {
            enrichedMessage = String.format("[数据源ID: %d] %s", datasourceId, userMessage);
            log.info("[ReActAgent] 已注入数据源上下文: datasourceId={}", datasourceId);
        } else {
            enrichedMessage = "[数据源ID: null] " + userMessage;
            log.warn("[ReActAgent] 数据源ID为null，LLM需要先调用 clarify_datasource");
        }
        
        Map<String, Object> userMsg = new HashMap<>();
        userMsg.put("role", "user");
        userMsg.put("content", enrichedMessage);
        messages.add(userMsg);
        
        log.info("[ReActAgent] 工具数量: {}", toolsDef.size());
        
        // 3. 执行 ReAct 循环
        for (int iteration = 0; iteration < MAX_ITERATIONS; iteration++) {
            log.info("[ReActAgent] 第{}次迭代", iteration + 1);
            
            try {
                // 调用 LLM（带 tools 参数）
                Map<String, Object> llmResponse = llmService.generateWithTools(messages, 0.5, toolsDef);
                
                // 调试：打印完整响应
                log.info("[ReActAgent] LLM 完整响应: {}", objectMapper.writeValueAsString(llmResponse));
                
                // 解析响应（统一格式：所有 Provider 都返回 {message: {...}}）
                Map<String, Object> message = (Map<String, Object>) llmResponse.get("message");
                if (message == null) {
                    log.error("[ReActAgent] LLM 响应格式错误");
                    return "LLM 响应错误";
                }
                
                // 检查是否有 tool_calls
                List<Map<String, Object>> toolCalls = (List<Map<String, Object>>) message.get("tool_calls");
                String content = (String) message.get("content");
                
                log.info("[ReActAgent] LLM 响应 - content: {}, tool_calls: {}", 
                    content != null ? content.substring(0, Math.min(100, content.length())) : "null",
                    toolCalls != null ? toolCalls.size() : 0);
                
                if (toolCalls != null && !toolCalls.isEmpty()) {
                    // LLM 想要调用工具
                    log.info("[ReActAgent] 检测到 {} 个工具调用", toolCalls.size());
                    
                    // 处理第一个工具调用（单工具模式）
                    Map<String, Object> firstToolCall = toolCalls.get(0);
                    Map<String, Object> function = (Map<String, Object>) firstToolCall.get("function");
                    String toolName = (String) function.get("name");
                    
                    // arguments 可能是 String 或 Map，需要统一处理
                    Object argumentsObj = function.get("arguments");
                    String argumentsJson;
                    if (argumentsObj instanceof String) {
                        argumentsJson = (String) argumentsObj;
                    } else {
                        // 如果是 Map/List，序列化为 JSON 字符串
                        argumentsJson = objectMapper.writeValueAsString(argumentsObj);
                    }
                    
                    log.info("[ReActAgent] 调用工具: {}, 参数: {}", toolName, argumentsJson);
                    
                    // ✅ 使用公共工具类修复并解析JSON
                    Map<String, Object> arguments = JsonUtils.parseToJsonMap(argumentsJson);
                    
                    if (arguments == null) {
                        log.error("[ReActAgent] JSON解析失败，原始参数: {}", argumentsJson);
                        // 返回友好的错误提示
                        Map<String, Object> errorMsg = new HashMap<>();
                        errorMsg.put("role", "tool");
                        errorMsg.put("name", toolName);
                        errorMsg.put("content", "错误：工具参数格式不正确，请重试");
                        messages.add(errorMsg);
                        continue; // 继续下一轮迭代
                    }
                    
                    // 执行工具
                    ToolExecutor executor = tools.get(toolName);
                    if (executor != null) {
                        String observation = executor.execute(arguments, datasourceId, userId, username, userMessage);
                        log.info("[ReActAgent] 工具执行结果 {}", observation);
                        
                        // ✅ 检查是否是数据源澄清且autoExecuted=true（优先处理）
                        if ("clarify_datasource".equals(toolName)) {
                            try {
                                Map<String, Object> clarificationResult = objectMapper.readValue(observation, Map.class);
                                
                                // ✅ 新格式：从clarification嵌套对象中获取
                                Boolean autoExecuted = null;
                                Long recommendedDsId = null;
                                
                                if (clarificationResult.containsKey("clarification")) {
                                    Map<String, Object> clarification = (Map<String, Object>) clarificationResult.get("clarification");
                                    autoExecuted = com.nl2sql.common.util.BooleanUtils.toBoolean(clarification.get("autoExecuted"));
                                    recommendedDsId = clarification.get("recommendedDatasourceId") != null ?
                                        ((Number) clarification.get("recommendedDatasourceId")).longValue() : null;
                                }
                                
                                if (Boolean.TRUE.equals(autoExecuted) && recommendedDsId != null) {
                                    log.info("[ReActAgent] ✅ 数据源自动选择: ID={}, 继续执行查询", recommendedDsId);
                                    // ✅ 更新datasourceId，继续下一轮迭代执行execute_standard_query
                                    datasourceId = recommendedDsId;
                                    
                                    // 将澄清结果添加到消息历史，让LLM知道已自动选择
                                    messages.add(message);
                                    Map<String, Object> toolResultMsg = new HashMap<>();
                                    toolResultMsg.put("role", "tool");
                                    toolResultMsg.put("name", toolName);
                                    toolResultMsg.put("content", observation);
                                    messages.add(toolResultMsg);
                                    
                                    // 继续循环，LLM会调用execute_standard_query
                                    continue;
                                }
                            } catch (Exception e) {
                                log.warn("[ReActAgent] 解析澄清响应失败: {}", e.getMessage());
                            }
                        }
                        
                        // ✅ 检查是否是统一格式的Tool响应（包含success和type字段）
                        // ⚠️ 注意：clarify_datasource的autoExecuted=true已在上面处理并continue，不会到达这里
                        if (isUnifiedToolResponse(observation)) {
                            log.info("[ReActAgent] 检测到统一Tool响应，直接返回");
                            return observation;
                        }
                        
                        // 添加工具调用到消息历史
                        messages.add(message); // Assistant message with tool_calls
                        
                        // 添加工具结果
                        Map<String, Object> toolResultMsg = new HashMap<>();
                        toolResultMsg.put("role", "tool");
                        toolResultMsg.put("name", toolName);
                        toolResultMsg.put("content", observation);
                        messages.add(toolResultMsg);
                        
                    } else {
                        log.warn("[ReActAgent] 未找到工具: {}", toolName);
                        messages.add(message);
                        
                        Map<String, Object> errorMsg = new HashMap<>();
                        errorMsg.put("role", "tool");
                        errorMsg.put("name", toolName);
                        errorMsg.put("content", "错误：未知工具 " + toolName);
                        messages.add(errorMsg);
                    }
                    
                } else {
                    // 没有 tool_calls，说明是最终答案
                    log.info("[ReActAgent] 最终答案: {}", content);
                    return content != null && !content.trim().isEmpty() ? content : "无法生成回答";
                }
                
            } catch (Exception e) {
                log.error("[ReActAgent] 执行失败", e);
                throw new RuntimeException("ReAct Agent 执行失败: " + e.getMessage(), e);
            }
        }
        
        log.warn("[ReActAgent] 达到最大迭代次数");
        return "抱歉，我无法处理您的请求（超过最大迭代次数）。";
    }
    
    /**
     * 构建 System Prompt（极简版，LLM 通过 tools 参数已知工具）
     */
    private String buildSystemPrompt() {
        return "你是数据分析助手。\n" +
               "规则：\n" +
               "1. 数据源ID为null时调用clarify_datasource，有ID直接使用\n" +
               "2. 查询调用execute_standard_query(question, datasourceId)\n" +
               "3. [INTENT:AI_SUMMARY]→summarize_result，[INTENT:GENERATE_CHART]→generate_chart\n" +
               "4. 工具返回JSON直接输出，不添加内容\n" +
               "5. 禁止输出思考过程，未知时调用工具";
    }
    
    /**
     * ✅ P0-2: 检查是否是统一Skill响应格式（使用 SkillResult 类）
     */
    private boolean isUnifiedToolResponse(String text) {
        if (text == null || text.trim().isEmpty()) {
            return false;
        }
        
        String trimmed = text.trim();
        if (!trimmed.startsWith("{")) {
            return false;
        }
        
        try {
            // ✅ 尝试解析为 SkillResult
            com.nl2sql.core.agent.skills.SkillResult result = 
                com.nl2sql.core.agent.skills.SkillResult.fromJson(trimmed);
            
            // ✅ 统一格式必须包含 success 和 type 字段
            return result != null && result.getType() != null;
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * 工具执行器接口
     */
    @FunctionalInterface
    public interface ToolExecutor {
        String execute(Map<String, Object> arguments, Long datasourceId, Long userId, String username, String userMessage);
        
        default String getDescription() {
            return "工具描述";
        }
        
        /**
         * ✅ P1-2: 获取工具可见性
         * 默认返回 PUBLIC，子类可覆写
         */
        default ToolVisibility getVisibility() {
            return ToolVisibility.PUBLIC;
        }
    }
    
    /**
     * ✅ 带描述的ToolExecutor包装类
     */
    static class ToolExecutorWithDescription implements ToolExecutor {
        private final ToolExecutor delegate;
        private final String description;
        private final ToolVisibility visibility;
        
        public ToolExecutorWithDescription(ToolExecutor delegate, String description) {
            this.delegate = delegate;
            this.description = description;
            this.visibility = ToolVisibility.PUBLIC;  // 默认公开
        }
        
        public ToolExecutorWithDescription(ToolExecutor delegate, String description, ToolVisibility visibility) {
            this.delegate = delegate;
            this.description = description;
            this.visibility = visibility;
        }
        
        @Override
        public String execute(Map<String, Object> arguments, Long datasourceId, Long userId, String username, String userMessage) {
            return delegate.execute(arguments, datasourceId, userId, username, userMessage);
        }
        
        @Override
        public String getDescription() {
            return description;
        }
        
        @Override
        public ToolVisibility getVisibility() {
            return visibility;
        }
    }
}
