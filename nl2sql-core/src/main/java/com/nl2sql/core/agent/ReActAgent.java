package com.nl2sql.core.agent;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.nl2sql.core.agent.skills.SkillsMetadataLoader;
import com.nl2sql.core.monitor.PerformanceMonitor;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ReAct Agent - 自行实现的推理-行动循环
 * 
 * 工作流程：
 * 1. LLM接收用户问题和可用工具列表
 * 2. LLM输出Thought（思考）和Action（行动）
 * 3. 解析Action中的JSON，执行对应工具
 * 4. 将工具结果返回给LLM作为Observation
 * 5. 重复直到LLM给出最终答案
 */
@Slf4j
public class ReActAgent {
    
    private final ChatModel chatModel;
    private final Map<String, ToolExecutor> tools;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private SkillsMetadataLoader skillsMetadataLoader;
    private PerformanceMonitor performanceMonitor;
    
    // 最大迭代次数，防止无限循环
    private static final int MAX_ITERATIONS = 10;
    
    public ReActAgent(ChatModel chatModel) {
        this.chatModel = chatModel;
        this.tools = new HashMap<>();
    }
    
    /**
     * 注册工具
     */
    public void registerTool(String name, ToolExecutor executor) {
        tools.put(name, executor);
        log.info("注册工具: {}", name);
    }
    
    /**
     * 注册工具（带描述）
     */
    public void registerTool(String name, ToolExecutor executor, String description) {
        ToolExecutor wrapper = new ToolExecutor() {
            @Override
            public String execute(Map<String, Object> arguments, Long datasourceId, Long userId, String username, String userMessage) {
                return executor.execute(arguments, datasourceId, userId, username, userMessage);
            }
            
            @Override
            public String getDescription() {
                return description;
            }
        };
        tools.put(name, wrapper);
        log.info("注册工具: {} - {}", name, description);
    }
    
    /**
     * 设置 Skills 元数据加载器（可选）
     */
    public void setSkillsMetadataLoader(SkillsMetadataLoader loader) {
        this.skillsMetadataLoader = loader;
        log.info("[ReActAgent] 已设置 Skills 元数据加载器");
    }
    
    /**
     * 设置性能监控器（可选）
     */
    public void setPerformanceMonitor(PerformanceMonitor monitor) {
        this.performanceMonitor = monitor;
        log.info("[ReActAgent] 已设置性能监控器");
    }
    
    /**
     * 获取已注册工具数量
     */
    public int getToolCount() {
        return tools.size();
    }
    
    /**
     * 执行ReAct循环
     * @return JSON字符串（工具结果）或自然语言（LLM回答）
     */
    public String execute(String userMessage, Long datasourceId, Long userId, String username) {
        log.info("[ReActAgent] 开始执行，用户消息: {}", userMessage);
        
        List<ChatMessage> messages = new ArrayList<>();
        
        // 添加SystemMessage
        messages.add(new SystemMessage(buildSystemMessage()));
        
        // 添加用户消息
        messages.add(new UserMessage(userMessage));
        
        String finalAnswer = null;
        String lastToolResult = null; // 保存最后一次工具执行的原始结果
        
        for (int iteration = 0; iteration < MAX_ITERATIONS; iteration++) {
            log.info("[ReActAgent] 第{}次迭代", iteration + 1);
            
            // 调用LLM（将消息列表转换为字符串）
            StringBuilder promptBuilder = new StringBuilder();
            for (ChatMessage msg : messages) {
                if (msg instanceof SystemMessage) {
                    promptBuilder.append("System: ").append(((SystemMessage) msg).text()).append("\n\n");
                } else if (msg instanceof UserMessage) {
                    promptBuilder.append("User: ").append(((UserMessage) msg).singleText()).append("\n\n");
                } else if (msg instanceof AiMessage) {
                    promptBuilder.append("Assistant: ").append(((AiMessage) msg).text()).append("\n\n");
                }
            }
            
            String llmOutput = chatModel.chat(promptBuilder.toString());
            
            log.info("[ReActAgent] LLM输出:\n{}", llmOutput);
            
            // 尝试提取工具调用
            Map<String, Object> toolCall = extractToolCall(llmOutput);
            
            if (toolCall != null) {
                // LLM想要调用工具
                String toolName = (String) toolCall.get("name");
                Map<String, Object> arguments = (Map<String, Object>) toolCall.get("arguments");
                
                log.info("[ReActAgent] 检测到工具调用: {}, 参数: {}", toolName, arguments);
                
                // ✅ 删除硬编码校验，由 AgentConfig 中的 Skills 元数据自动处理
                
                // 执行工具
                ToolExecutor executor = tools.get(toolName);
                if (executor != null) {
                    // ⚠️ P0优化：记录开始时间
                    long startTime = performanceMonitor != null ? performanceMonitor.recordToolStart(toolName) : 0;
                    
                    try {
                        String observation = executor.execute(arguments, datasourceId, userId, username, userMessage);
                        lastToolResult = observation; // 保存原始结果
                        
                        // ⚠️ P0优化：记录成功
                        if (performanceMonitor != null) {
                            performanceMonitor.recordToolEnd(toolName, startTime, true);
                        }
                        
                        log.info("[ReActAgent] 工具执行结果: {}", observation.substring(0, Math.min(200, observation.length())));
                        
                        // 检查是否是结构化数据（JSON格式）
                        if (isStructuredData(observation)) {
                            log.info("[ReActAgent] 检测到结构化数据，直接返回");
                            return observation; // 直接返回JSON，不让LLM再生成Final Answer
                        }
                        
                        // 添加工具调用和结果到对话历史
                        messages.add(new AiMessage(llmOutput));
                        messages.add(new UserMessage("Observation: " + observation));
                        
                    } catch (Exception e) {
                        log.error("[ReActAgent] 工具执行失败", e);
                        
                        // ⚠️ P0优化：记录失败
                        if (performanceMonitor != null) {
                            performanceMonitor.recordToolEnd(toolName, startTime, false);
                        }
                        
                        messages.add(new AiMessage(llmOutput));
                        messages.add(new UserMessage("Observation: 工具执行失败: " + e.getMessage()));
                    }
                } else {
                    // ✅ 优化：工具名模糊匹配（处理LLM拼写错误）
                    String matchedToolName = findSimilarToolName(toolName);
                    if (matchedToolName != null) {
                        log.warn("[ReActAgent] 工具名拼写错误: {} → {}", toolName, matchedToolName);
                        ToolExecutor matchedExecutor = tools.get(matchedToolName);
                        
                        long startTime = performanceMonitor != null ? performanceMonitor.recordToolStart(matchedToolName) : 0;
                        
                        try {
                            String observation = matchedExecutor.execute(arguments, datasourceId, userId, username, userMessage);
                            lastToolResult = observation;
                            
                            if (performanceMonitor != null) {
                                performanceMonitor.recordToolEnd(matchedToolName, startTime, true);
                            }
                            
                            log.info("[ReActAgent] 工具执行结果: {}", observation.substring(0, Math.min(200, observation.length())));
                            
                            if (isStructuredData(observation)) {
                                log.info("[ReActAgent] 检测到结构化数据，直接返回");
                                return observation;
                            }
                            
                            messages.add(new AiMessage(llmOutput));
                            messages.add(new UserMessage("Observation: " + observation));
                            
                        } catch (Exception e) {
                            log.error("[ReActAgent] 工具执行失败", e);
                            
                            if (performanceMonitor != null) {
                                performanceMonitor.recordToolEnd(matchedToolName, startTime, false);
                            }
                            
                            messages.add(new AiMessage(llmOutput));
                            messages.add(new UserMessage("Observation: 工具执行失败: " + e.getMessage()));
                        }
                    } else {
                        log.warn("[ReActAgent] 未找到工具: {}", toolName);
                        messages.add(new AiMessage(llmOutput));
                        messages.add(new UserMessage("Observation: 未知工具: " + toolName + "。可用工具: " + String.join(", ", tools.keySet())));
                    }
                }
            } else {
                // ⚠️ 关键修复：检测LLM是否输出了数据源确认问句（而不是调用工具）
                if (llmOutput.contains("数据源") || llmOutput.contains("推荐使用") || 
                    llmOutput.contains("请确认") || llmOutput.contains("是否使用")) {
                    log.warn("[ReActAgent] LLM输出了数据源确认问句，但没有调用工具！这是错误的行为。");
                    log.warn("[ReActAgent] LLM输出: {}", llmOutput.substring(0, Math.min(100, llmOutput.length())));
                    
                    // ⚠️ 如果当前有 datasourceId，说明之前已经调用了 clarify_datasource
                    // LLM应该直接调用 execute_standard_query，但它失败了
                    // 我们在这里不强制修正，让 AgentController 处理这种情况
                }
                
                // 没有工具调用，说明是最终答案
                log.info("[ReActAgent] 没有检测到工具调用，作为最终答案返回");
                finalAnswer = llmOutput;
                break;
            }
        }
        
        if (finalAnswer == null) {
            finalAnswer = "抱歉，我无法处理您的请求。";
        }
        
        log.info("[ReActAgent] 执行完成");
        return finalAnswer;
    }
    
    /**
     * 检查是否是结构化数据（JSON格式）
     */
    private boolean isStructuredData(String text) {
        if (text == null || text.trim().isEmpty()) {
            return false;
        }
        
        String trimmed = text.trim();
        // 检查是否以 { 开头且包含 status 或 success 字段
        if (trimmed.startsWith("{") && (trimmed.contains("\"status\"") || trimmed.contains("\"success\""))) {
            try {
                Map<String, Object> json = objectMapper.readValue(trimmed, Map.class);
                // ⚠️ 只要有 status 或 success 字段就认为是结构化数据
                return json.containsKey("status") || json.containsKey("success");
            } catch (Exception e) {
                return false;
            }
        }
        
        return false;
    }
    
    /**
     * 构建SystemMessage
     */
    private String buildSystemMessage() {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个智能数据分析助手。\n\n");
        
        // 动态注入 Skills 描述
        if (skillsMetadataLoader != null) {
            sb.append(skillsMetadataLoader.generateSkillsDescription());
            sb.append("\n\n");
        }
        
        sb.append("## 可用工具\n");
        
        for (Map.Entry<String, ToolExecutor> entry : tools.entrySet()) {
            sb.append("- ").append(entry.getKey()).append(": ").append(entry.getValue().getDescription()).append("\n");
        }
        
        sb.append("\n## 工作流程\n");
        sb.append("对于每个问题，你需要：\n");
        sb.append("1. **检查消息是否包含 [INTENT:XXX] 标记**\n");
        sb.append("   - 如果包含 [INTENT:AI_SUMMARY] → 从消息中提取用户问题和 SQL，然后调用 summarize_result 工具\n");
        sb.append("     格式：{\"name\": \"summarize_result\", \"arguments\": {\"context\": {\"lastQuery\": \"提取的用户问题\", \"generatedSQL\": \"提取的SQL\"}}}\n");
        sb.append("   - 如果包含 [INTENT:GENERATE_CHART] → 从消息中提取图表类型和 SQL，然后调用 generate_chart 工具\n");
        sb.append("     格式：{\"name\": \"generate_chart\", \"arguments\": {\"context\": {\"chartType\": \"bar/line/pie\", \"generatedSQL\": \"提取的SQL\"}}}\n");
        sb.append("   - 不要询问数据源，不要调用其他工具\n");
        sb.append("2. **⚠️ 关键第一步：检查 datasourceId 是否为 null**\n");
        sb.append("   - ⚠️ **绝对禁止**：如果 datasourceId 为 null，绝对不能调用 execute_standard_query！\n");
        sb.append("   - 必须先调用 clarify_datasource 获取推荐的数据源\n");
        sb.append("   - 示例：用户问“统计销售额”，datasourceId=null → 必须调用 clarify_datasource\n");
        sb.append("   - ⚠️ **重要**：如果 datasourceId 不为 null（已有数据源），**绝对禁止**再次调用 clarify_datasource！\n");
        sb.append("   - 正确做法：直接使用 execute_standard_query(question, datasourceId) 执行查询\n");
        sb.append("3. **处理 clarify_datasource 工具的返回结果**\n");
        sb.append("   - ⚠️ **关键规则**：如果 clarify_datasource 返回了 recommendedDatasourceId 和 high/medium 置信度\n");
        sb.append("     → 立即调用 execute_standard_query(question=原始问题, datasourceId=recommendedDatasourceId)\n");
        sb.append("     → ⚠️ **绝对禁止**：不要输出任何确认问句，如“请确认”、“是否使用”等\n");
        sb.append("     → ⚠️ **绝对禁止**：不要说“好的”、“明白了”等废话\n");
        sb.append("     → 直接输出工具调用 JSON，没有任何其他文字\n");
        sb.append("   - 示例：clarify_datasource 返回 {recommendedDatasourceId: 1, confidence: \"high\"}\n");
        sb.append("     → ✅ 正确：{\"name\": \"execute_standard_query\", \"arguments\": {\"question\": \"统计销售额\", \"datasourceId\": 1}}\n");
        sb.append("     → ❌ 错误：“好的，请确认您要使用XXX数据源”\n");
        sb.append("4. **检查是否是数据源确认回复**（如“是的”、“好的”、“确认”等）\n");
        sb.append("   - 如果用户消息很短且是确认语气，并且上下文中有 datasourceId → 直接使用原始问题 + datasourceId 调用 execute_standard_query\n");
        sb.append("   - 例如：用户之前问“查询订单”，你推荐了数据源，用户回复“是的” → 调用 execute_standard_query(question=\"查询订单\", datasourceId=xxx)\n");
        sb.append("5. **判断是否需要澄清数据源**（仅当 clarify_datasource 无法匹配时）\n");
        sb.append("   - ⚠️ **关键原则**：LLM 无法知道哪个数据源包含用户需要的字段\n");
        sb.append("   - 如果用户没有明确指定数据源（datasourceId 未知），必须调用 clarify_datasource 获取可用数据源列表\n");
        sb.append("   - 即使问题是“统计销售额”，也不能假设某个数据源一定有这些字段\n");
        sb.append("6. **执行查询**（数据源明确时）：\n");
        sb.append("   a) **先评估SQL复杂度**：\n");
        sb.append("      - 简单查询（单表、少量字段）→ 直接调用 execute_standard_query\n");
        sb.append("      - 复杂查询（多表JOIN≥3张、子查询、大数据量）→ 先调用 analyze_sql_risk 评估风险\n");
        sb.append("   b) **如果调用了 analyze_sql_risk**：\n");
        sb.append("      - 风险等级为 HIGH → 返回警告信息给用户，建议优化SQL\n");
        sb.append("      - 风险等级为 MEDIUM/LOW → 调用 execute_direct_sql 执行已分析的SQL\n");
        sb.append("      - 注意：execute_direct_sql 需要提供 sql 和 datasourceId 参数\n");
        sb.append("6. 等待工具返回结果后，用中文给出最终答案\n\n");
        
        sb.append("## ⚠️ 重要规则\n");
        sb.append("- 不要输出Thought、Action、Observation等标记\n");
        sb.append("- 不要尝试调用 retrieve_schema、generate_sql、execute_sql 等不存在的工具\n");
        sb.append("- ⚠️ **强制规则**：直接输出纯JSON，不要包含```json或任何其他Markdown标记\n");
        sb.append("  ✅ 正确：{\"name\": \"execute_standard_query\", \"arguments\": {...}}\n");
        sb.append("  ❌ 错误：```json\\n{\"name\": ...}\\n```\n");
        sb.append("- ⚠️ **绝对禁止**：当工具返回结构化数据（JSON格式）时，不要再生成任何Final Answer！\n");
        sb.append("- ⚠️ **正确做法**：工具返回 JSON 后，立即停止，让系统直接返回该 JSON 给前端\n");
        sb.append("- ⚠️ **错误示例**：clarify_datasource 返回推荐数据源后，你又说“好的，请确认您要使用XXX数据源”\n");
        sb.append("- ⚠️ **错误示例**：execute_standard_query 返回查询结果后，你又说“是否需要进一步分析或生成图表”\n");
        sb.append("- ⚠️ **强制规则**：clarify_datasource 返回 recommendedDatasourceId 后，必须立即调用 execute_standard_query，不得有任何中间对话！\n\n");
        
        sb.append("## 输出示例\n");
        sb.append("✅ 好的（总结意图）：{\"name\": \"summarize_result\", \"arguments\": {\"context\": {\"lastQuery\": \"查询销售额\", \"generatedSQL\": \"SELECT ...\"}}}\n");
        sb.append("✅ 好的（简单查询）：{\"name\": \"execute_standard_query\", \"arguments\": {\"question\": \"统计销售额\", \"datasourceId\": 1}}\n");
        sb.append("✅ 好的（复杂查询先评估）：{\"name\": \"analyze_sql_risk\", \"arguments\": {\"sql\": \"SELECT o.*, u.name FROM orders o JOIN users u ON o.user_id = u.id JOIN order_items oi ON o.id = oi.order_id\"}}\n");
        sb.append("✅ 好的（风险评估后执行）：{\"name\": \"execute_direct_sql\", \"arguments\": {\"sql\": \"SELECT o.*, u.name FROM orders o JOIN users u ON o.user_id = u.id\", \"datasourceId\": 1}}\n");
        sb.append("❌ 不好的：Thought: 我需要...\\nAction: ...\n");
        
        return sb.toString();
    }
    
    /**
     * 提取工具调用JSON
     */
    private Map<String, Object> extractToolCall(String text) {
        if (text == null || text.trim().isEmpty()) {
            return null;
        }
        
        String cleanedText = text.trim();
        
        // 步骤1: 去除Markdown代码块标记
        // 匹配 ```json ... ``` 或 ``` ... ```
        Pattern markdownPattern = Pattern.compile("```(?:json)?\\s*([\\s\\S]*?)\\s*```");
        Matcher matcher = markdownPattern.matcher(cleanedText);
        if (matcher.find()) {
            cleanedText = matcher.group(1).trim();
            log.debug("[ReActAgent] 从Markdown中提取JSON");
        }
        
        // 步骤2: 如果文本看起来是纯JSON，直接解析
        if (cleanedText.startsWith("{") && cleanedText.endsWith("}")) {
            try {
                Map<String, Object> result = objectMapper.readValue(cleanedText, Map.class);
                if (result.containsKey("name")) {
                    log.debug("[ReActAgent] 直接解析JSON成功");
                    return result;
                }
            } catch (Exception e) {
                log.debug("[ReActAgent] 直接解析JSON失败，尝试提取策略", e.getMessage());
            }
        }
        
        // 步骤3: 从文本中提取JSON对象
        try {
            int lastBraceStart = cleanedText.lastIndexOf("{");
            
            if (lastBraceStart >= 0) {
                int braceCount = 0;
                int jsonEnd = -1;
                
                for (int i = lastBraceStart; i < cleanedText.length(); i++) {
                    char c = cleanedText.charAt(i);
                    if (c == '{') {
                        braceCount++;
                    } else if (c == '}') {
                        braceCount--;
                        if (braceCount == 0) {
                            jsonEnd = i + 1;
                            break;
                        }
                    }
                }
                
                if (jsonEnd > lastBraceStart) {
                    String toolJson = cleanedText.substring(lastBraceStart, jsonEnd);
                    Map<String, Object> result = objectMapper.readValue(toolJson, Map.class);
                    if (result.containsKey("name")) {
                        log.debug("[ReActAgent] 提取JSON成功");
                        return result;
                    }
                }
            }
        } catch (Exception e) {
            log.error("[ReActAgent] 提取工具调用失败", e);
        }
        
        return null;
    }
    
    /**
     * ✅ 优化工具名模糊匹配（处理LLM拼写错误）
     * 使用Levenshtein距离算法，相似度 > 0.8 时自动纠正
     */
    private String findSimilarToolName(String inputName) {
        if (inputName == null || inputName.isEmpty()) {
            return null;
        }
        
        String bestMatch = null;
        double bestSimilarity = 0.8; // 阈值：80%相似度
        
        for (String toolName : tools.keySet()) {
            double similarity = calculateSimilarity(inputName.toLowerCase(), toolName.toLowerCase());
            if (similarity > bestSimilarity) {
                bestSimilarity = similarity;
                bestMatch = toolName;
            }
        }
        
        return bestMatch;
    }
    
    /**
     * 计算两个字符串的相似度（基于Levenshtein距离）
     * @return 0.0-1.0，1.0表示完全相同
     */
    private double calculateSimilarity(String s1, String s2) {
        if (s1.equals(s2)) {
            return 1.0;
        }
        
        int distance = levenshteinDistance(s1, s2);
        int maxLen = Math.max(s1.length(), s2.length());
        
        return maxLen == 0 ? 1.0 : 1.0 - (double) distance / maxLen;
    }
    
    /**
     * 计算Levenshtein编辑距离
     */
    private int levenshteinDistance(String s1, String s2) {
        int m = s1.length();
        int n = s2.length();
        
        int[][] dp = new int[m + 1][n + 1];
        
        for (int i = 0; i <= m; i++) {
            dp[i][0] = i;
        }
        for (int j = 0; j <= n; j++) {
            dp[0][j] = j;
        }
        
        for (int i = 1; i <= m; i++) {
            for (int j = 1; j <= n; j++) {
                int cost = s1.charAt(i - 1) == s2.charAt(j - 1) ? 0 : 1;
                dp[i][j] = Math.min(
                    Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1),
                    dp[i - 1][j - 1] + cost
                );
            }
        }
        
        return dp[m][n];
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
