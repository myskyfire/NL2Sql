package com.nl2sql.core.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nl2sql.core.llm.LLMService;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

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
    
    // 最大迭代次数，防止无限循环
    private static final int MAX_ITERATIONS = 10;
    
    public ReActAgent(LLMService llmService) {
        this.llmService = llmService;
        this.tools = new HashMap<>();
        log.info("[ReActAgent] 初始化完成，使用原生 Tool Calling");
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
        
        // 2. 构建工具定义（OpenAI 格式）
        List<Map<String, Object>> toolsDef = ToolDefinitionConverter.convertToOpenAITools(tools);
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
                    
                    // 解析参数
                    Map<String, Object> arguments = objectMapper.readValue(argumentsJson, Map.class);
                    
                    // 执行工具
                    ToolExecutor executor = tools.get(toolName);
                    if (executor != null) {
                        String observation = executor.execute(arguments, datasourceId, userId, username, userMessage);
                        log.info("[ReActAgent] 工具执行结果 {}", observation);
                        
                        // ✅ 检查是否是数据源澄清且autoExecuted=true（优先处理）
                        if ("clarify_datasource".equals(toolName)) {
                            try {
                                Map<String, Object> clarificationResult = objectMapper.readValue(observation, Map.class);
                                
                                // ✅ 兼容旧格式：没有type字段但有status字段
                                if (!clarificationResult.containsKey("type") && clarificationResult.containsKey("status")) {
                                    log.warn("[ReActAgent] 检测到旧格式响应，自动转换");
                                    observation = convertLegacyClarificationFormat(clarificationResult);
                                    clarificationResult = objectMapper.readValue(observation, Map.class);
                                }
                                
                                // ✅ 新格式：从clarification嵌套对象中获取
                                Boolean autoExecuted = null;
                                Long recommendedDsId = null;
                                
                                if (clarificationResult.containsKey("clarification")) {
                                    Map<String, Object> clarification = (Map<String, Object>) clarificationResult.get("clarification");
                                    autoExecuted = (Boolean) clarification.get("autoExecuted");
                                    recommendedDsId = clarification.get("recommendedDatasourceId") != null ?
                                        ((Number) clarification.get("recommendedDatasourceId")).longValue() : null;
                                } else {
                                    // 兼容旧格式：直接在顶层
                                    autoExecuted = (Boolean) clarificationResult.get("autoExecuted");
                                    recommendedDsId = clarificationResult.get("recommendedDatasourceId") != null ?
                                        ((Number) clarificationResult.get("recommendedDatasourceId")).longValue() : null;
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
               "## 规则\n" +
               "1. **数据源**：消息以`[数据源 ID: XXX]`开头，null 时调用 clarify_datasource，有数字则直接使用该ID，禁止再次澄清\n" +
               "2. **查询**：调用 execute_standard_query(question, datasourceId)，禁用手调底层工具\n" +
               "3. **意图**：[INTENT:AI_SUMMARY]→summarize_result，[INTENT:GENERATE_CHART]→generate_chart\n" +
               "4. **返回**：工具返回 JSON 时直接返回，不添加额外内容\n" +
               "5. **禁止**：不输出思考过程，不知如何回答时必须调用工具";
    }
    
    /**
     * ✅ 检查是否是统一Tool响应格式（包含success和type字段）
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
            Map<String, Object> json = objectMapper.readValue(trimmed, Map.class);
            // ✅ 统一格式必须同时包含success和type字段
            return json.containsKey("success") && json.containsKey("type");
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * ✅ 兼容旧格式响应转换
     */
    private String convertLegacyClarificationFormat(Map<String, Object> legacy) {
        try {
            Map<String, Object> converted = new HashMap<>();
            converted.put("success", true);
            converted.put("type", "clarification");
            
            Map<String, Object> clarification = new HashMap<>();
            clarification.put("clarificationType", legacy.get("clarificationType"));
            clarification.put("message", legacy.get("message"));
            clarification.put("autoExecuted", legacy.get("autoExecuted"));
            if (legacy.containsKey("recommendedDatasourceId")) {
                clarification.put("recommendedDatasourceId", legacy.get("recommendedDatasourceId"));
            }
            
            converted.put("clarification", clarification);
            converted.put("metadata", Map.of("converted", true, "originalFormat", "legacy"));
            
            return objectMapper.writeValueAsString(converted);
        } catch (Exception e) {
            log.error("[ReActAgent] 旧格式转换失败", e);
            return legacy.toString();
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
    }
    
    /**
     * ✅ 带描述的ToolExecutor包装类
     */
    static class ToolExecutorWithDescription implements ToolExecutor {
        private final ToolExecutor delegate;
        private final String description;
        
        public ToolExecutorWithDescription(ToolExecutor delegate, String description) {
            this.delegate = delegate;
            this.description = description;
        }
        
        @Override
        public String execute(Map<String, Object> arguments, Long datasourceId, Long userId, String username, String userMessage) {
            return delegate.execute(arguments, datasourceId, userId, username, userMessage);
        }
        
        @Override
        public String getDescription() {
            return description;
        }
    }
}
