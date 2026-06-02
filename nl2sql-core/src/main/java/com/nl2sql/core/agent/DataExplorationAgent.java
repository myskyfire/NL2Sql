package com.nl2sql.core.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nl2sql.core.agent.tools.ToolRegistry;
import com.nl2sql.core.llm.LLMService;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

@Slf4j
public class DataExplorationAgent {

    private static final int MAX_ITERATIONS = 5;

    private final LLMService llmService;
    private final ToolRegistry toolRegistry;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String SYSTEM_PROMPT =
        "你是数据探索助手，帮助用户发现数据中的模式、异常和洞察。\n\n" +
        "## 工作流程\n" +
        "1. 先检索 schema 了解有哪些数据可用\n" +
        "2. 生成 SQL 查询来探索数据\n" +
        "3. 观察结果，决定下一步调查什么\n" +
        "4. 持续探索直到找到有价值的发现\n" +
        "5. 汇总发现给用户\n\n" +
        "## 规则\n" +
        "- 始终先了解 schema 再写 SQL\n" +
        "- 如果查询失败，尝试修复后重试\n" +
        "- 发现有趣的内容时，深入挖掘\n" +
        "- 3-4 轮迭代后，汇总你的发现\n" +
        "- 完成后以 JSON 格式输出最终结果\n" +
        "- 不要输出思考过程，只输出最终结果";

    private static final List<String> EXPLORATION_TOOLS = List.of(
        "retrieveSchema",
        "generateSQL",
        "quickRiskCheck",
        "executeRawSQL",
        "autoFixSQL",
        "detectChartIntent",
        "generateChartConfig",
        "generateAISummary",
        "assembleResult"
    );

    public DataExplorationAgent(LLMService llmService, ToolRegistry toolRegistry) {
        this.llmService = llmService;
        this.toolRegistry = toolRegistry;
        log.info("[DataExplorationAgent] initialized with {} available tools", EXPLORATION_TOOLS.size());
    }

    public String explore(String userMessage, Long datasourceId) {
        log.info("[DataExplorationAgent] starting exploration: question={}, datasourceId={}", userMessage, datasourceId);

        Long userId = com.nl2sql.common.context.UserContext.getUserId();
        String username = com.nl2sql.common.context.UserContext.getUsername();

        List<Map<String, Object>> messages = new ArrayList<>();

        Map<String, Object> systemMsg = new HashMap<>();
        systemMsg.put("role", "system");
        systemMsg.put("content", SYSTEM_PROMPT);
        messages.add(systemMsg);

        String enrichedMessage = datasourceId != null
            ? String.format("[datasourceId: %d] %s", datasourceId, userMessage)
            : userMessage;
        Map<String, Object> userMsg = new HashMap<>();
        userMsg.put("role", "user");
        userMsg.put("content", enrichedMessage);
        messages.add(userMsg);

        List<Map<String, Object>> toolsDef = buildToolDefinitions();

        String lastObservation = null;
        int successfulQueries = 0;

        for (int iteration = 0; iteration < MAX_ITERATIONS; iteration++) {
            log.info("[DataExplorationAgent] iteration {}/{}", iteration + 1, MAX_ITERATIONS);

            try {
                Map<String, Object> llmResponse = llmService.generateWithTools(messages, 0.3, toolsDef);

                Map<String, Object> message = (Map<String, Object>) llmResponse.get("message");
                if (message == null) {
                    log.error("[DataExplorationAgent] invalid LLM response format");
                    return buildErrorResponse("LLM response format error");
                }

                List<Map<String, Object>> toolCalls = (List<Map<String, Object>>) message.get("tool_calls");
                String content = (String) message.get("content");

                if (toolCalls == null || toolCalls.isEmpty()) {
                    log.info("[DataExplorationAgent] LLM returned final answer (no tool_calls)");
                    if (content != null && !content.trim().isEmpty()) {
                        return content;
                    }
                    if (lastObservation != null) {
                        return lastObservation;
                    }
                    return buildErrorResponse("No results from exploration");
                }

                Map<String, Object> firstToolCall = toolCalls.get(0);
                Map<String, Object> function = (Map<String, Object>) firstToolCall.get("function");
                String toolName = (String) function.get("name");

                Object argumentsObj = function.get("arguments");
                String argumentsJson;
                if (argumentsObj instanceof String) {
                    argumentsJson = (String) argumentsObj;
                } else {
                    argumentsJson = objectMapper.writeValueAsString(argumentsObj);
                }

                Map<String, Object> arguments = parseArguments(argumentsJson);
                if (arguments == null) {
                    arguments = new HashMap<>();
                }

                if (datasourceId != null && !arguments.containsKey("datasourceId")) {
                    arguments.put("datasourceId", datasourceId);
                }
                if (!arguments.containsKey("question") && !arguments.containsKey("query")) {
                    arguments.put("question", userMessage);
                }

                log.info("[DataExplorationAgent] calling tool: {}, args keys: {}", toolName, arguments.keySet());

                String observation;
                try {
                    Object result = toolRegistry.callTool(toolName, arguments);
                    observation = result instanceof String ? (String) result : objectMapper.writeValueAsString(result);
                } catch (Exception e) {
                    log.warn("[DataExplorationAgent] tool {} failed: {}", toolName, e.getMessage());
                    observation = "{\"success\":false,\"error\":\"Tool " + toolName + " failed: " + e.getMessage() + "\"}";
                }

                lastObservation = observation;
                log.info("[DataExplorationAgent] tool {} result length: {}", toolName, observation.length());

                if ("executeRawSQL".equals(toolName) || "executeSQL".equals(toolName)) {
                    try {
                        Map<String, Object> resultMap = objectMapper.readValue(observation, Map.class);
                        Map<String, Object> data = (Map<String, Object>) resultMap.get("data");
                        if (data != null && Boolean.TRUE.equals(data.get("success"))) {
                            successfulQueries++;
                        }
                    } catch (Exception ignored) {}
                }

                if (isFinalResult(observation)) {
                    log.info("[DataExplorationAgent] detected final result, returning");
                    return observation;
                }

                messages.add(message);

                Map<String, Object> toolResultMsg = new HashMap<>();
                toolResultMsg.put("role", "tool");
                toolResultMsg.put("name", toolName);
                String truncatedObs = observation.length() > 3000
                    ? observation.substring(0, 3000) + "...(truncated)"
                    : observation;
                toolResultMsg.put("content", truncatedObs);
                messages.add(toolResultMsg);

                if (successfulQueries >= 3) {
                    log.info("[DataExplorationAgent] {} successful queries, forcing summary", successfulQueries);
                    Map<String, Object> summaryPrompt = new HashMap<>();
                    summaryPrompt.put("role", "user");
                    summaryPrompt.put("content", "基于你已经探索到的数据，请提供一份全面的发现汇总。以 JSON 格式输出最终结果。");
                    messages.add(summaryPrompt);
                }

            } catch (Exception e) {
                log.error("[DataExplorationAgent] iteration {} failed", iteration + 1, e);
                if (lastObservation != null) {
                    return lastObservation;
                }
                return buildErrorResponse("Exploration failed: " + e.getMessage());
            }
        }

        log.warn("[DataExplorationAgent] max iterations reached");
        if (lastObservation != null) {
            return lastObservation;
        }
        return buildErrorResponse("Max iterations reached without results");
    }

    private boolean isFinalResult(String observation) {
        if (observation == null || !observation.trim().startsWith("{")) return false;
        try {
            Map<String, Object> map = objectMapper.readValue(observation, Map.class);
            Object type = map.get("type");
            return "assembled_result".equals(type) || "final_result".equals(type);
        } catch (Exception e) {
            return false;
        }
    }

    private Map<String, Object> parseArguments(String json) {
        if (json == null || json.trim().isEmpty()) return new HashMap<>();
        try {
            return com.nl2sql.common.util.JsonUtils.parseToJsonMap(json);
        } catch (Exception e) {
            log.warn("[DataExplorationAgent] failed to parse arguments: {}", e.getMessage());
            return new HashMap<>();
        }
    }

    private List<Map<String, Object>> buildToolDefinitions() {
        List<Map<String, Object>> tools = new ArrayList<>();
        for (String toolName : EXPLORATION_TOOLS) {
            ToolRegistry.ToolDescriptor descriptor = toolRegistry.getToolDescriptor(toolName);
            if (descriptor == null) {
                log.debug("[DataExplorationAgent] tool {} not registered, skipping", toolName);
                continue;
            }

            Map<String, Object> toolDef = new HashMap<>();
            toolDef.put("type", "function");

            Map<String, Object> functionDef = new HashMap<>();
            functionDef.put("name", descriptor.getName());
            functionDef.put("description", descriptor.getDescription());

            Map<String, Object> parameters = new HashMap<>();
            parameters.put("type", "object");

            Map<String, Object> properties = new HashMap<>();
            List<String> required = new ArrayList<>();

            if (descriptor.getParameters() != null) {
                for (ToolRegistry.ToolParamDescriptor param : descriptor.getParameters()) {
                    Map<String, Object> propDef = new HashMap<>();
                    String paramType = jsonType(param.getType());
                    propDef.put("type", paramType);
                    if (param.getDescription() != null) {
                        propDef.put("description", param.getDescription());
                    }
                    properties.put(param.getName(), propDef);
                    if (param.isRequired()) {
                        required.add(param.getName());
                    }
                }
            }

            parameters.put("properties", properties);
            if (!required.isEmpty()) {
                parameters.put("required", required);
            }
            functionDef.put("parameters", parameters);

            toolDef.put("function", functionDef);
            tools.add(toolDef);
        }

        log.info("[DataExplorationAgent] built {} tool definitions", tools.size());
        return tools;
    }

    private String jsonType(Class<?> type) {
        if (type == Long.class || type == long.class || type == Integer.class || type == int.class) return "integer";
        if (type == Double.class || type == double.class || type == Float.class || type == float.class) return "number";
        if (type == Boolean.class || type == boolean.class) return "boolean";
        return "string";
    }

    private String buildErrorResponse(String error) {
        try {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", false);
            result.put("type", "exploration_error");
            result.put("error", error);
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            return "{\"success\":false,\"error\":\"" + error + "\"}";
        }
    }
}
