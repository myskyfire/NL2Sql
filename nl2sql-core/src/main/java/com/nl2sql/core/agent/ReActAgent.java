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
        tools.put(name, executor);
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
     * @return JSON字符串（工具结果）或自然语言（LLM回答）
     */
    public String execute(String userMessage, Long datasourceId, Long userId, String username, 
                         List<Map<String, Object>> historyMessages) {
        log.info("[ReActAgent] 开始执行，用户消息: {}, datasourceId={}, 历史消息数={}", 
            userMessage, datasourceId, historyMessages != null ? historyMessages.size() : 0);
        
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
                Map<String, Object> llmResponse = llmService.generateWithTools(messages, 0.7, toolsDef);
                
                // 调试：打印完整响应
                log.debug("[ReActAgent] LLM 完整响应: {}", objectMapper.writeValueAsString(llmResponse));
                
                // 解析响应
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
                        log.info("[ReActAgent] 工具执行结果长度: {}", observation.length());
                        
                        // 检查是否是结构化数据（JSON格式）
                        if (isStructuredData(observation)) {
                            log.info("[ReActAgent] 检测到结构化数据，直接返回");
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
                return "执行错误: " + e.getMessage();
            }
        }
        
        log.warn("[ReActAgent] 达到最大迭代次数");
        return "抱歉，我无法处理您的请求（超过最大迭代次数）。";
    }
    
    /**
     * 构建 System Prompt（极简版，LLM 通过 tools 参数已知工具）
     */
    private String buildSystemPrompt() {
        return "你是一个智能数据分析助手。\n" +
               "\n" +
               "## 核心规则\n" +
               "1. **数据源处理**：\n" +
               "   - 用户消息以 `[数据源ID: XXX]` 开头\n" +
               "   - 如果为 null → 调用 clarify_datasource\n" +
               "   - 如果有数字 → 直接使用该 ID，禁止再次澄清\n" +
               "\n" +
               "2. **查询执行**：\n" +
               "   - 数据源明确时，调用 execute_standard_query(question, datasourceId)\n" +
               "   - 该工具自动完成：检索表结构、生成 SQL、评估风险、执行查询\n" +
               "   - 禁止手动调用底层工具（analyze_sql_risk、execute_direct_sql 等）\n" +
               "   - 禁止自己生成 SQL\n" +
               "\n" +
               "3. **特殊意图**：\n" +
               "   - [INTENT:AI_SUMMARY] → 必须调用 summarize_result(lastQuery=\"...\", generatedSQL=\"...\")\n" +
               "   - [INTENT:GENERATE_CHART] → 必须调用 generate_chart(chartType=\"bar/line/pie/area 或 null\", generatedSQL=\"...\")\n" +
               "   - ⚠️ **重要**：当用户消息包含 [INTENT:XXX] 标记时，必须调用对应工具，不要返回空内容\n" +
               "\n" +
               "4. **返回规则**：\n" +
               "   - 工具返回结构化数据（JSON）时，直接返回，不要生成额外回答\n" +
               "   - clarify_datasource 返回后，立即调用 execute_standard_query\n" +
               "   - execute_standard_query 返回结果后，直接返回，不要询问后续操作\n" +
               "\n" +
               "5. **重要**：\n" +
               "   - 禁止输出 thinking/reasoning 内容\n" +
               "   - 直接调用工具或返回最终答案\n" +
               "   - 不要在 content 中解释你的思考过程\n" +
               "   - **如果不知道如何回答，必须调用工具，不要返回空字符串**";
    }
    
    /**
     * 检查是否是结构化数据（JSON格式）
     */
    private boolean isStructuredData(String text) {
        if (text == null || text.trim().isEmpty()) {
            return false;
        }
        
        String trimmed = text.trim();
        if (trimmed.startsWith("{") && (trimmed.contains("\"status\"") || trimmed.contains("\"success\""))) {
            try {
                Map<String, Object> json = objectMapper.readValue(trimmed, Map.class);
                return json.containsKey("status") || json.containsKey("success");
            } catch (Exception e) {
                return false;
            }
        }
        
        return false;
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
}
