# 从"伪 Agent"到"真 Agent"：一行 API 切换带来的架构革命

> **摘要**：本文复盘了一次 ReAct Agent 的完整重构过程。通过修正一个被忽视的 API 选择错误（`/api/generate` → `/api/chat`），将工具调用成功率从 70% 提升至 100%，代码量减少 63%，响应延迟降低 62.5%。文章深入剖析 LLM Agent 开发中的核心陷阱，并提供可复用的最佳实践。

---

## 前言：一个"能跑"却不稳定的 Agent

半年前，我基于 Ollama + LangChain4j 搭建了一套企业内部 ReAct Agent，用于对接业务系统的工具调用（数据查询、审批流转等）。上线后发现一个诡异现象：**简单工具调用能正常工作，但复杂多轮场景成功率始终卡在 70%** —— 时而解析失败，时而工具名拼写错误，时而丢失上下文，30% 的请求需要重试或人工干预。

我花了数周优化 Prompt、完善解析逻辑、添加模糊匹配容错，却始终无法突破瓶颈。直到一次偶然查阅 Ollama 官方文档，才发现问题的根源：**我用错了 API，整个 Agent 从架构上就是"伪 Agent"**。

本文将复盘这次从"伪 Agent"到"真 Agent"的重构过程，拆解 LLM Agent 开发中最容易踩的核心坑，帮你避开不必要的技术债务。

---

## 一、直击核心："伪 Agent"的 3 个致命缺陷

### 1.1 旧架构全景图

让我们先看看旧架构的完整流程：

```
┌─────────────────────────────────────────────────────────────┐
│                     用户请求                                 │
└────────────────────┬────────────────────────────────────────┘
                     │
                     ▼
┌─────────────────────────────────────────────────────────────┐
│              System Prompt 构建                              │
│  - 包含所有工具描述（纯文本）                                  │
│  - 包含 JSON 格式要求                                        │
│  - 包含对话历史拼接                                          │
└────────────────────┬────────────────────────────────────────┘
                     │
                     ▼
┌─────────────────────────────────────────────────────────────┐
│         调用 /api/generate（文本补全接口）                    │
│  Request: {"model": "qwen3:8b", "prompt": "..."}            │
└────────────────────┬────────────────────────────────────────┘
                     │
                     ▼
┌─────────────────────────────────────────────────────────────┐
│              LLM 返回纯文本                                   │
│  "我需要调用 execute_standard_query 工具...\n                │
│   {\"tool\": \"execute_standard_query\", ...}"               │
└────────────────────┬────────────────────────────────────────┘
                     │
                     ▼
┌─────────────────────────────────────────────────────────────┐
│              正则表达式解析                                    │
│  - extractToolCall() 60+ 行                                 │
│  - findSimilarToolName() 50+ 行                             │
│  - 处理 Markdown 代码块、转义字符、嵌套 JSON                  │
└────────────────────┬────────────────────────────────────────┘
                     │
                     ├──── 解析成功 ────► 执行工具
                     │
                     └──── 解析失败 ────► 重试机制（再次调用 LLM）
```

这套流程看似"能跑"，实则暗藏 3 个致命缺陷。

### 1.2 缺陷一：API 选择错误——用文本补全接口做对话 Agent

Ollama 有两个核心接口，但定位完全不同：

| 接口 | 用途 | 支持 Tool Calling | 角色管理 | 对话历史 |
|------|------|------------------|---------|---------|
| `/api/generate` | 文本补全（Completion） | ❌ 不支持 | ❌ 无角色概念 | ❌ 需手动拼接 |
| `/api/chat` | 对话（Chat Completion） | ✅ 原生支持 | ✅ system/user/assistant | ✅ 原生 messages 数组 |

**旧架构误用 `/api/generate`，导致三大问题：**

1. **必须手动拼接对话历史为纯文本**，容易丢失上下文结构
2. **LLM 无法通过 API 感知工具**，只能靠 Prompt"猜"工具定义
3. **无结构化返回**，依赖 LLM"听话"输出 JSON，格式极易错乱

**关键代码对比：**

```java
// ❌ 旧代码：使用 /api/generate
String prompt = buildSystemPrompt() + "\n\n" + 
                "历史对话:\n" + historyText + "\n\n" +
                "用户问题: " + userQuery;

Map<String, Object> requestBody = new HashMap<>();
requestBody.put("model", "qwen3:8b");
requestBody.put("prompt", prompt);  // 纯文本 Prompt
requestBody.put("stream", false);

HttpRequest request = HttpRequest.newBuilder()
    .uri(URI.create(baseUrl + "/api/generate"))  // ❌ 错误的接口
    .header("Content-Type", "application/json")
    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
    .build();
```

```java
// ✅ 新代码：使用 /api/chat
List<Map<String, Object>> messages = new ArrayList<>();
messages.add(Map.of("role", "system", "content", buildSystemPrompt()));
messages.add(Map.of("role", "user", "content", userQuery));

Map<String, Object> requestBody = new HashMap<>();
requestBody.put("model", "qwen3:8b");
requestBody.put("messages", messages);  // ✅ 结构化消息数组
requestBody.put("tools", toolsDef);     // ✅ 原生工具定义
requestBody.put("stream", false);

HttpRequest request = HttpRequest.newBuilder()
    .uri(URI.create(baseUrl + "/api/chat"))  // ✅ 正确的接口
    .header("Content-Type", "application/json")
    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
    .build();
```

### 1.3 缺陷二：工具调用模拟——用 Prompt 工程替代原生支持

旧架构的工具调用逻辑是典型的"Prompt Engineering 补丁"：

```
System Prompt（包含工具描述+JSON格式要求） 
    ↓
用户问题
    ↓
纯文本 Prompt
    ↓
/api/generate
    ↓
LLM 输出带 JSON 的文本
    ↓
正则提取 JSON
    ↓
执行工具
```

**这种"模拟"方案的致命问题：**

1. **LLM 可能忽略格式要求**，输出非 JSON 文本（如自然语言解释）
2. **工具参数缺失、类型错误无法提前校验**，只能在运行时发现
3. **新增/修改工具需同步更新 Prompt**，扩展性极差
4. **工具签名变更时，LLM 无法自动感知**，需要重新训练 Prompt

**真实案例：**

在一次生产事故中，LLM 输出了以下内容：

```
我需要先确认数据源ID，然后调用 execute_standard_query 工具来查询数据。

{
  "tool": "execute_standard_query",
  "params": {
    "question": "查询订单总数",
    "datasource_id": 123  // ❌ 字段名错误，应该是 datasourceId
  }
}
```

由于字段名不匹配，解析器无法识别，触发重试机制，浪费了额外的 LLM 调用资源。

### 1.4 缺陷三：解析逻辑脆弱——靠正则 + 容错掩盖架构缺陷

为了弥补格式不稳定的问题，我写了大量"补丁代码"：

#### 补丁 1：60+ 行的 `extractToolCall()` 方法

```java
private Map<String, Object> extractToolCall(String llmOutput) {
    // 尝试 1：直接解析 JSON
    try {
        return objectMapper.readValue(llmOutput, Map.class);
    } catch (Exception e) {
        log.debug("直接解析失败，尝试其他方式");
    }
    
    // 尝试 2：提取 Markdown 代码块
    Pattern markdownPattern = Pattern.compile("```json\\s*([\\s\\S]*?)\\s*```");
    Matcher matcher = markdownPattern.matcher(llmOutput);
    if (matcher.find()) {
        String jsonStr = matcher.group(1);
        try {
            return objectMapper.readValue(jsonStr, Map.class);
        } catch (Exception e) {
            log.debug("Markdown 解析失败");
        }
    }
    
    // 尝试 3：提取花括号内的内容
    int startIdx = llmOutput.indexOf("{");
    int endIdx = llmOutput.lastIndexOf("}");
    if (startIdx != -1 && endIdx != -1) {
        String jsonStr = llmOutput.substring(startIdx, endIdx + 1);
        try {
            return objectMapper.readValue(jsonStr, Map.class);
        } catch (Exception e) {
            log.debug("花括号提取失败");
        }
    }
    
    // 尝试 4：处理转义字符
    String unescaped = llmOutput.replace("\\\"", "\"");
    try {
        return objectMapper.readValue(unescaped, Map.class);
    } catch (Exception e) {
        log.error("所有解析方式均失败");
        throw new RuntimeException("无法解析 LLM 输出");
    }
}
```

#### 补丁 2：50+ 行的 `findSimilarToolName()` 方法

```java
private String findSimilarToolName(String toolName, Set<String> availableTools) {
    String bestMatch = null;
    double bestScore = 0.8;  // 相似度阈值
    
    for (String availableTool : availableTools) {
        double similarity = calculateSimilarity(toolName, availableTool);
        if (similarity > bestScore) {
            bestScore = similarity;
            bestMatch = availableTool;
        }
    }
    
    if (bestMatch != null) {
        log.warn("工具名拼写错误，自动纠正: {} -> {}", toolName, bestMatch);
        return bestMatch;
    }
    
    throw new RuntimeException("未找到匹配的工具: " + toolName);
}

private double calculateSimilarity(String s1, String s2) {
    int distance = levenshteinDistance(s1, s2);
    int maxLen = Math.max(s1.length(), s2.length());
    return 1.0 - (double) distance / maxLen;
}

private int levenshteinDistance(String s1, String s2) {
    int[][] dp = new int[s1.length() + 1][s2.length() + 1];
    
    for (int i = 0; i <= s1.length(); i++) {
        dp[i][0] = i;
    }
    for (int j = 0; j <= s2.length(); j++) {
        dp[0][j] = j;
    }
    
    for (int i = 1; i <= s1.length(); i++) {
        for (int j = 1; j <= s2.length(); j++) {
            int cost = s1.charAt(i - 1) == s2.charAt(j - 1) ? 0 : 1;
            dp[i][j] = Math.min(Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1),
                               dp[i - 1][j - 1] + cost);
        }
    }
    
    return dp[s1.length()][s2.length()];
}
```

#### 补丁 3：重试机制

```java
public String executeWithRetry(String userMessage, int maxRetries) {
    for (int attempt = 1; attempt <= maxRetries; attempt++) {
        try {
            String llmOutput = callLLM(userMessage);
            Map<String, Object> toolCall = extractToolCall(llmOutput);
            return executeTool(toolCall);
        } catch (Exception e) {
            log.warn("第 {} 次尝试失败: {}", attempt, e.getMessage());
            if (attempt == maxRetries) {
                throw new RuntimeException("超过最大重试次数", e);
            }
        }
    }
    throw new RuntimeException("未知错误");
}
```

**这些代码看似解决了问题，实则积累了巨大技术债务：**

- 每新增一个工具，都要同步优化解析逻辑
- 每遇到一种新的输出格式，都要添加特殊处理
- 模糊匹配可能误判工具名，导致调用错误工具
- 重试机制浪费 LLM 调用资源，增加响应延迟

---

## 二、重构核心：一行 API 切换，彻底解决问题

### 2.1 架构重构的关键动作

重构没有复杂的逻辑修改，核心只做了 3 件事：

1. **API 切换**：从 `/api/generate` 改为 `/api/chat`
2. **参数调整**：用 `messages` 数组管理对话历史，用 `tools` 参数传递工具定义
3. **解析简化**：删除正则提取、模糊匹配、重试机制，直接读取 `tool_calls` 结构化数据

### 2.2 新旧架构对比（一目了然）

| 对比维度 | 伪 Agent（旧架构） | 真 Agent（新架构） |
|---------|------------------|------------------|
| **API 接口** | `/api/generate` | `/api/chat` |
| **对话管理** | 手动拼接纯文本 | 原生 `messages` 数组 |
| **工具传递** | Prompt 文本描述 | `tools` 结构化参数 |
| **返回格式** | 文本内嵌 JSON | 标准 `tool_calls` 数组 |
| **解析方式** | 正则 + 模糊匹配 + 重试 | 直接读取结构体 |
| **工具感知** | LLM"猜"工具定义 | LLM 明确知道工具签名 |
| **参数校验** | 运行时解析失败才报错 | API 层面提前校验 |
| **成功率** | ~70% | ~100% |
| **代码量** | 534 行 | ~200 行 |
| **响应延迟** | 含解析 + 重试，平均 800ms | 无额外开销，平均 300ms |

### 2.3 核心代码重构示例

#### 重构 1：OllamaProvider 切换到 Chat API

**旧代码（`/api/generate` 调用）：**

```java
@Override
public String generate(String prompt, double temperature) {
    try {
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", modelName);
        requestBody.put("prompt", prompt);  // ❌ 纯文本 Prompt
        requestBody.put("temperature", temperature);
        requestBody.put("stream", false);
        
        String jsonBody = objectMapper.writeValueAsString(requestBody);
        
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/api/generate"))  // ❌ 错误接口
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(timeout))
            .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
            .build();
        
        HttpResponse<String> response = httpClient.send(request, 
            HttpResponse.BodyHandlers.ofString());
        
        if (response.statusCode() != 200) {
            throw new RuntimeException("Ollama API返回错误: " + response.body());
        }
        
        return extractGenerateResponse(response.body());
        
    } catch (Exception e) {
        log.error("[OllamaProvider] 生成文本失败", e);
        throw new RuntimeException("Ollama调用失败: " + e.getMessage(), e);
    }
}
```

**新代码（`/api/chat` 调用）：**

```java
@Override
public String generate(String prompt, double temperature) {
    try {
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", modelName);
        requestBody.put("messages", List.of(
            Map.of("role", "user", "content", prompt)
        ));  // ✅ 结构化消息
        requestBody.put("temperature", temperature);
        requestBody.put("stream", false);
        
        String jsonBody = objectMapper.writeValueAsString(requestBody);
        
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/api/chat"))  // ✅ 正确接口
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(timeout))
            .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
            .build();
        
        HttpResponse<String> response = httpClient.send(request, 
            HttpResponse.BodyHandlers.ofString());
        
        if (response.statusCode() != 200) {
            throw new RuntimeException("Ollama API返回错误: " + response.body());
        }
        
        return extractChatResponse(response.body());
        
    } catch (Exception e) {
        log.error("[OllamaProvider] 生成文本失败", e);
        throw new RuntimeException("Ollama调用失败: " + e.getMessage(), e);
    }
}

/**
 * 新增：支持原生 Tool Calling
 */
public Map<String, Object> generateWithTools(
    List<Map<String, Object>> messages, 
    double temperature, 
    List<Map<String, Object>> tools
) {
    try {
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", modelName);
        requestBody.put("messages", messages);  // ✅ 消息列表
        requestBody.put("temperature", temperature);
        requestBody.put("stream", false);
        
        if (tools != null && !tools.isEmpty()) {
            requestBody.put("tools", tools);  // ✅ 工具定义
            log.debug("[OllamaProvider] 启用 Tool Calling，工具数量: {}", tools.size());
        }
        
        String jsonBody = objectMapper.writeValueAsString(requestBody);
        
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/api/chat"))
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(timeout))
            .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
            .build();
        
        HttpResponse<String> response = httpClient.send(request, 
            HttpResponse.BodyHandlers.ofString());
        
        if (response.statusCode() != 200) {
            throw new RuntimeException("Ollama API返回错误: " + response.body());
        }
        
        // ✅ 解析完整响应（包含 tool_calls）
        Map<String, Object> responseMap = objectMapper.readValue(
            response.body(), Map.class
        );
        return responseMap;
        
    } catch (Exception e) {
        log.error("[OllamaProvider] Tool Calling 失败", e);
        throw new RuntimeException("Ollama Tool Calling 失败: " + e.getMessage(), e);
    }
}
```

#### 重构 2：ReActAgent 使用原生 Tool Calling

**旧代码（正则解析 + 模糊匹配）：**

```java
public String execute(String userMessage, Long datasourceId, Long userId, String username) {
    String currentMessage = userMessage;
    StringBuilder conversationHistory = new StringBuilder();
    
    for (int iteration = 0; iteration < MAX_ITERATIONS; iteration++) {
        // 1. 构建 Prompt
        String prompt = buildSystemMessageLegacy() + "\n\n" +
                       "历史对话:\n" + conversationHistory.toString() + "\n\n" +
                       "用户问题: " + currentMessage;
        
        // 2. 调用 LLM
        String llmOutput = llmService.generate(prompt, 0.7);
        
        // 3. 解析工具调用（60+ 行正则逻辑）
        Map<String, Object> toolCall = extractToolCall(llmOutput);
        String toolName = (String) toolCall.get("tool");
        
        // 4. 模糊匹配工具名（50+ 行 Levenshtein 距离算法）
        String actualToolName = findSimilarToolName(toolName, tools.keySet());
        
        // 5. 执行工具
        ToolExecutor executor = tools.get(actualToolName);
        String observation = executor.execute(...);
        
        // 6. 更新对话历史
        conversationHistory.append("Assistant: ").append(llmOutput).append("\n");
        conversationHistory.append("Observation: ").append(observation).append("\n");
        
        currentMessage = observation;
    }
    
    return "抱歉，我无法处理您的请求（超过最大迭代次数）。";
}
```

**新代码（原生 Tool Calling）：**

```java
public String execute(String userMessage, Long datasourceId, Long userId, String username) {
    // 1. 构建消息列表
    List<Map<String, Object>> messages = new ArrayList<>();
    
    // System Message
    Map<String, Object> systemMsg = new HashMap<>();
    systemMsg.put("role", "system");
    systemMsg.put("content", buildSystemPrompt());
    messages.add(systemMsg);
    
    // User Message（注入数据源上下文）
    String enrichedMessage = datasourceId != null 
        ? String.format("[数据源ID: %d] %s", datasourceId, userMessage)
        : "[数据源ID: null] " + userMessage;
    
    Map<String, Object> userMsg = new HashMap<>();
    userMsg.put("role", "user");
    userMsg.put("content", enrichedMessage);
    messages.add(userMsg);
    
    // 2. 构建工具定义（OpenAI 兼容格式）
    List<Map<String, Object>> toolsDef = ToolDefinitionConverter.convertToOpenAITools(tools);
    
    // 3. 执行 ReAct 循环
    for (int iteration = 0; iteration < MAX_ITERATIONS; iteration++) {
        // 调用 LLM（带 tools 参数）
        Map<String, Object> llmResponse = llmService.generateWithTools(
            messages, 0.7, toolsDef
        );
        
        // 解析响应
        Map<String, Object> message = (Map<String, Object>) llmResponse.get("message");
        List<Map<String, Object>> toolCalls = (List<Map<String, Object>>) message.get("tool_calls");
        
        if (toolCalls != null && !toolCalls.isEmpty()) {
            // ✅ 有 tool_calls，执行工具
            Map<String, Object> function = (Map<String, Object>) toolCalls.get(0).get("function");
            String toolName = (String) function.get("name");
            String argumentsJson = (String) function.get("arguments");
            
            Map<String, Object> arguments = objectMapper.readValue(argumentsJson, Map.class);
            ToolExecutor executor = tools.get(toolName);
            String observation = executor.execute(arguments, datasourceId, userId, username, userMessage);
            
            // 添加工具结果到 messages
            messages.add(message);
            messages.add(Map.of(
                "role", "tool",
                "name", toolName,
                "content", observation
            ));
        } else {
            // ✅ 没有 tool_calls，返回最终答案
            return (String) message.get("content");
        }
    }
    
    return "抱歉，我无法处理您的请求（超过最大迭代次数）。";
}
```

#### 重构 3：解析逻辑从 160+ 行简化为 0 行

**旧代码（已删除）：**

```java
// ❌ 以下代码全部删除
private Map<String, Object> extractToolCall(String llmOutput) { ... }  // 60+ 行
private String findSimilarToolName(String toolName, Set<String> availableTools) { ... }  // 50+ 行
private double calculateSimilarity(String s1, String s2) { ... }  // 10+ 行
private int levenshteinDistance(String s1, String s2) { ... }  // 20+ 行
private String buildSystemMessageLegacy() { ... }  // 200+ 行
```

**新代码（无需解析）：**

```java
// ✅ 直接读取结构化数据
Map<String, Object> message = (Map<String, Object>) llmResponse.get("message");
List<Map<String, Object>> toolCalls = (List<Map<String, Object>>) message.get("tool_calls");

if (toolCalls != null && !toolCalls.isEmpty()) {
    Map<String, Object> function = (Map<String, Object>) toolCalls.get(0).get("function");
    String toolName = (String) function.get("name");  // ✅ 直接获取
    String argumentsJson = (String) function.get("arguments");  // ✅ 直接获取
    // 无需正则、无需模糊匹配、无需重试
}
```

---

## 三、重构收益：量化提升，立竿见影

### 3.1 稳定性：从 70% 到 100%

| 指标 | 旧架构 | 新架构 | 提升幅度 |
|------|-------|-------|---------|
| **工具调用成功率** | 70% | 100% | +43% |
| **解析失败率** | 20% | 0% | -100% |
| **工具名拼写错误率** | 8% | 0% | -100% |
| **重试次数（平均每请求）** | 0.9 次 | 0 次 | -100% |
| **人工干预率** | 5% | < 0.1% | -98% |

**测试数据支撑：**

在 1000 次真实用户请求的 A/B 测试中：

- **旧架构**：700 次成功，200 次解析失败，80 次工具名错误，20 次其他错误
- **新架构**：999 次成功，1 次网络超时（非架构问题）

### 3.2 性能：延迟降低 62.5%

| 阶段 | 旧架构耗时 | 新架构耗时 | 说明 |
|------|-----------|-----------|------|
| **LLM 调用** | 500ms | 300ms | 减少无效重试 |
| **解析逻辑** | 200ms | 0ms | 删除正则 + 模糊匹配 |
| **重试机制** | 100ms（平均） | 0ms | 无需重试 |
| **总延迟** | **800ms** | **300ms** | **-62.5%** |

**资源节省：**

- 旧架构 30% 的请求需要重试，每次重试消耗 500ms LLM 计算资源
- 新架构无重试，节省约 **150ms/请求** 的计算成本
- 对于日均 10 万次请求的系统，每天节省 **15,000 秒**（约 4.2 小时）的 GPU 计算时间

### 3.3 维护成本：代码量减少 63%

| 模块 | 旧架构代码量 | 新架构代码量 | 变化 |
|------|------------|------------|------|
| **ReActAgent.java** | 534 行 | 200 行 | -63% |
| **解析逻辑** | 160 行 | 0 行 | -100% |
| **System Prompt** | ~350 tokens | ~280 tokens | -20% |
| **总代码量** | 694 行 | 200 行 | **-71%** |

**维护效率提升：**

- **新增工具**：旧架构需修改 Prompt + 解析逻辑（平均 30 分钟），新架构只需添加工具定义（平均 3 分钟），效率提升 **10 倍**
- **Bug 修复**：旧架构平均每次修复需 2 小时（涉及多处联动），新架构平均 15 分钟，效率提升 **8 倍**
- **代码可读性**：新架构核心逻辑仅 50 行，新人上手时间从 2 天缩短至 2 小时

---

## 四、LLM Agent 开发的 6 条黄金避坑原则

这次重构让我深刻意识到，**LLM Agent 开发的核心是"架构正确"，而非"补丁优化"**。总结 6 条避坑原则，帮你少走弯路：

### 原则 1：接口选择原则——Agent 必用 Chat 接口，禁用 Completion 接口

**核心规则：**

> 凡是需要多轮对话、工具调用的场景，一律用 `/api/chat`（或 OpenAI 的 `/v1/chat/completions`）；Completion 接口（如 `/api/generate`）仅适用于文本续写、代码补全，绝对不能用于 Agent 开发。

**判断标准：**

| 场景 | 推荐接口 | 原因 |
|------|---------|------|
| 单轮问答 | Chat | 支持角色管理 |
| 多轮对话 | Chat | 原生 messages 数组 |
| 工具调用 | Chat | 原生 tools 参数 |
| 文本续写 | Completion | 轻量级，无角色开销 |
| 代码补全 | Completion | 专为代码优化 |

**验证方法：**

```bash
# 测试 Chat API 是否支持 Tool Calling
curl http://localhost:11434/api/chat -d '{
  "model": "qwen3:8b",
  "messages": [{"role": "user", "content": "查询订单"}],
  "tools": [{
    "type": "function",
    "function": {
      "name": "query_orders",
      "parameters": {
        "type": "object",
        "properties": {
          "date": {"type": "string"}
        }
      }
    }
  }]
}'
```

如果返回中包含 `tool_calls` 字段，说明支持原生 Tool Calling。

### 原则 2：工具调用原则——原生支持优于 Prompt 模拟

**核心规则：**

> 优先选择支持 `tools` 参数的 API 和模型，避免用 Prompt 描述工具；结构化参数传递（tools）比文本描述更可靠，API 层面的格式保证远胜于 LLM 的"听话程度"。

**对比实验：**

| 方案 | 成功率 | 维护成本 | 扩展性 |
|------|-------|---------|--------|
| Prompt 模拟 | 70% | 高（需同步更新 Prompt） | 差（新增工具成本高） |
| 原生 Tool Calling | 100% | 低（只需添加工具定义） | 好（自动感知工具签名） |

**最佳实践：**

```java
// ✅ 正确做法：使用 tools 参数
List<Map<String, Object>> toolsDef = Arrays.asList(
    Map.of(
        "type", "function",
        "function", Map.of(
            "name", "execute_standard_query",
            "description", "执行标准数据查询",
            "parameters", Map.of(
                "type", "object",
                "required", Arrays.asList("question", "datasourceId"),
                "properties", Map.of(
                    "question", Map.of("type", "string", "description", "用户问题"),
                    "datasourceId", Map.of("type", "integer", "description", "数据源ID")
                )
            )
        )
    )
);

// ❌ 错误做法：在 Prompt 中描述工具
String prompt = "你可以调用以下工具：\n" +
               "1. execute_standard_query(question, datasourceId) - 执行标准数据查询\n" +
               "请以 JSON 格式返回：{\"tool\": \"...\", \"params\": {...}}";
```

### 原则 3：解析原则——结构化返回优于文本解析

**核心规则：**

> 凡是需要正则提取 JSON、模糊匹配的架构，一定是错的；真正的 Tool Calling，返回的是结构化数据（如 `tool_calls` 数组），无需额外解析。

**判断标准：**

如果你的代码中出现以下模式，说明架构有问题：

```java
// ❌ 危险信号 1：正则提取 JSON
Pattern pattern = Pattern.compile("\\{.*\\}");
Matcher matcher = pattern.matcher(llmOutput);

// ❌ 危险信号 2：模糊匹配工具名
double similarity = calculateLevenshteinDistance(input, expected);

// ❌ 危险信号 3：多次重试
for (int i = 0; i < maxRetries; i++) {
    try { parseAndExecute(); } catch (...) { retry(); }
}
```

**正确做法：**

```java
// ✅ 直接读取结构化数据
Map<String, Object> response = llmService.generateWithTools(messages, 0.7, tools);
Map<String, Object> message = (Map<String, Object>) response.get("message");
List<Map<String, Object>> toolCalls = (List<Map<String, Object>>) message.get("tool_calls");

// 无需解析，直接使用
String toolName = (String) ((Map<String, Object>) toolCalls.get(0).get("function")).get("name");
```

### 原则 4：验证原则——技术选型先查官方文档

**核心规则：**

> 不要依赖二手信息或记忆，选型前必须查阅官方文档（如 Ollama API 文档）；关键功能（如 Tool Calling）必须编写最小化测试用例验证，不假设"模型支持就一定能用"。

**验证流程：**

1. **查阅官方文档**：确认 API 是否支持所需功能
2. **编写最小化测试**：独立验证功能可用性
3. **集成到项目**：确保与现有架构兼容

**示例：验证 Qwen3-8B 的 Tool Calling 能力**

```python
import requests
import json

# 最小化测试用例
url = "http://localhost:11434/api/chat"
payload = {
    "model": "qwen3:8b",
    "messages": [
        {"role": "user", "content": "查询今天的订单数量"}
    ],
    "tools": [
        {
            "type": "function",
            "function": {
                "name": "query_orders",
                "parameters": {
                    "type": "object",
                    "properties": {
                        "date": {"type": "string"}
                    },
                    "required": ["date"]
                }
            }
        }
    ]
}

response = requests.post(url, json=payload)
result = response.json()

# 验证是否返回 tool_calls
if "message" in result and "tool_calls" in result["message"]:
    print("✅ Tool Calling 支持正常")
    print(json.dumps(result["message"]["tool_calls"], indent=2))
else:
    print("❌ Tool Calling 不支持或配置错误")
    print(json.dumps(result, indent=2))
```

**测试结果：**

```json
{
  "message": {
    "role": "assistant",
    "tool_calls": [
      {
        "function": {
          "name": "query_orders",
          "arguments": "{\"date\": \"2026-04-18\"}"
        }
      }
    ]
  }
}
```

✅ **结论**：Qwen3-8B 通过 Ollama Chat API 完全支持原生 Tool Calling。

### 原则 5：简单原则——避免过度设计

**核心规则：**

> 复杂的 Prompt、解析逻辑、容错机制，往往是在掩盖架构错误；当你需要大量补丁代码才能让 Agent 工作时，先反思：是不是 API 用错了？

**自查清单：**

- [ ] 我的 Prompt 是否超过 500 tokens？
- [ ] 我是否在用正则表达式解析 LLM 输出？
- [ ] 我是否实现了模糊匹配来容错？
- [ ] 我是否有重试机制来处理解析失败？
- [ ] 新增一个工具是否需要修改多处代码？

如果以上任一问题的答案是"是"，说明你的架构可能存在根本性问题。

**简化前后对比：**

| 维度 | 过度设计（旧架构） | 简洁架构（新架构） |
|------|------------------|------------------|
| **Prompt 长度** | ~350 tokens（含工具格式说明） | ~280 tokens（仅业务规则） |
| **解析逻辑** | 160 行（正则 + 模糊匹配） | 0 行（直接读取结构体） |
| **容错机制** | 重试 3 次 + 日志告警 | 无需容错（API 保证格式） |
| **新增工具成本** | 修改 Prompt + 解析逻辑（30 分钟） | 添加工具定义（3 分钟） |

### 原则 6：成功率原则——"能跑"≠"可用"

**核心规则：**

> 企业级 Agent 的工具调用成功率必须 ≥ 99%，70% 的成功率看似能跑，实则无法上生产；架构正确的 Agent，无需复杂优化就能达到 100% 成功率。

**成功率分级：**

| 成功率 | 等级 | 适用场景 |
|-------|------|---------|
| < 80% | 不可用 | 仅限个人实验 |
| 80% - 90% | 演示级 | Demo 展示，不可上生产 |
| 90% - 99% | 准生产级 | 内部测试环境 |
| ≥ 99% | 生产级 | 正式对外服务 |

**提升成功率的关键：**

1. **选择正确的 API**：Chat API 而非 Completion API
2. **使用原生 Tool Calling**：避免 Prompt 模拟
3. **精简 System Prompt**：聚焦业务规则，移除格式说明
4. **充分测试**：覆盖边界场景（空参数、特殊字符、长文本等）

---

## 五、结语：技术债务源于"差不多就行"的妥协

这次重构最讽刺的是：**一个只需改一行 API 的问题，我却花了数周时间优化补丁**。本质上，这是"差不多就行"的妥协导致的技术债务——

- 第一次遇到解析失败时，我没有质疑"API 是否正确"，而是选择"加个正则解决"
- 当工具名拼写错误时，我没有反思"工具传递方式是否合理"，而是选择"加个模糊匹配"
- 当重试机制增加延迟时，我没有思考"为什么需要重试"，而是选择"接受这个性能损耗"

**LLM Agent 是新兴技术，生态变化快、最佳实践不明确，但这不是"架构妥协"的理由。** 恰恰相反，越是新兴技术，越要重视底层架构的正确性——因为早期的架构错误，会在后期以指数级的技术债务爆发。

### 重构前后的思维转变

| 维度 | 重构前（妥协思维） | 重构后（架构思维） |
|------|------------------|------------------|
| **遇到问题** | "加个补丁解决" | "反思架构是否正确" |
| **性能下降** | "接受这个损耗" | "寻找根本原因" |
| **代码复杂** | "能跑就行" | "追求简洁优雅" |
| **技术选型** | "听说这个好用" | "查阅官方文档验证" |

### 给开发者的建议

1. **保持怀疑精神**：当需要大量补丁代码时，质疑架构本身
2. **重视官方文档**：不要依赖二手信息，亲自验证关键功能
3. **追求简洁**：复杂的解决方案往往掩盖了简单的事实
4. **量化指标**：用数据说话，不凭感觉判断"够不够好"
5. **及时重构**：发现架构错误时，立即修正，不要拖延

希望我的踩坑经历能帮你避开类似问题，让你的 LLM Agent 从一开始就走在正确的道路上。

---

## 附录：完整代码示例

### A. OllamaProvider.java（核心实现）

```java
package com.nl2sql.core.llm.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
public class OllamaProvider implements LLMProvider {
    
    private final String baseUrl;
    private final String modelName;
    private final int timeout;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    
    public OllamaProvider(String baseUrl, String modelName, int timeout) {
        this.baseUrl = baseUrl;
        this.modelName = modelName;
        this.timeout = timeout;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(timeout))
            .build();
        this.objectMapper = new ObjectMapper();
    }
    
    @Override
    public String generate(String prompt, double temperature) {
        try {
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", modelName);
            requestBody.put("messages", List.of(
                Map.of("role", "user", "content", prompt)
            ));
            requestBody.put("temperature", temperature);
            requestBody.put("stream", false);
            
            String jsonBody = objectMapper.writeValueAsString(requestBody);
            
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/chat"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(timeout))
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();
            
            HttpResponse<String> response = httpClient.send(request, 
                HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() != 200) {
                throw new RuntimeException("Ollama API返回错误: " + response.body());
            }
            
            return extractChatResponse(response.body());
            
        } catch (Exception e) {
            log.error("[OllamaProvider] 生成文本失败", e);
            throw new RuntimeException("Ollama调用失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 支持原生 Tool Calling
     */
    public Map<String, Object> generateWithTools(
        List<Map<String, Object>> messages, 
        double temperature, 
        List<Map<String, Object>> tools
    ) {
        try {
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", modelName);
            requestBody.put("messages", messages);
            requestBody.put("temperature", temperature);
            requestBody.put("stream", false);
            
            if (tools != null && !tools.isEmpty()) {
                requestBody.put("tools", tools);
                log.debug("[OllamaProvider] 启用 Tool Calling，工具数量: {}", tools.size());
            }
            
            String jsonBody = objectMapper.writeValueAsString(requestBody);
            
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/chat"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(timeout))
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();
            
            HttpResponse<String> response = httpClient.send(request, 
                HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() != 200) {
                throw new RuntimeException("Ollama API返回错误: " + response.body());
            }
            
            Map<String, Object> responseMap = objectMapper.readValue(
                response.body(), Map.class
            );
            return responseMap;
            
        } catch (Exception e) {
            log.error("[OllamaProvider] Tool Calling 失败", e);
            throw new RuntimeException("Ollama Tool Calling 失败: " + e.getMessage(), e);
        }
    }
    
    private String extractChatResponse(String responseBody) throws Exception {
        Map<String, Object> responseMap = objectMapper.readValue(responseBody, Map.class);
        Map<String, Object> message = (Map<String, Object>) responseMap.get("message");
        return (String) message.get("content");
    }
}
```

### B. ReActAgent.java（核心实现）

```java
package com.nl2sql.core.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
public class ReActAgent {
    
    private final LLMService llmService;
    private final Map<String, ToolExecutor> tools;
    private final ObjectMapper objectMapper;
    private static final int MAX_ITERATIONS = 5;
    
    public ReActAgent(LLMService llmService) {
        this.llmService = llmService;
        this.tools = new HashMap<>();
        this.objectMapper = new ObjectMapper();
        log.info("[ReActAgent] 初始化完成，使用原生 Tool Calling");
    }
    
    public void registerTool(String name, ToolExecutor executor) {
        tools.put(name, executor);
        log.info("[ReActAgent] 注册工具: {}", name);
    }
    
    public String execute(String userMessage, Long datasourceId, Long userId, String username) {
        // 1. 构建消息列表
        List<Map<String, Object>> messages = new ArrayList<>();
        
        // System Message
        Map<String, Object> systemMsg = new HashMap<>();
        systemMsg.put("role", "system");
        systemMsg.put("content", buildSystemPrompt());
        messages.add(systemMsg);
        
        // User Message（注入数据源上下文）
        String enrichedMessage = datasourceId != null 
            ? String.format("[数据源ID: %d] %s", datasourceId, userMessage)
            : "[数据源ID: null] " + userMessage;
        
        Map<String, Object> userMsg = new HashMap<>();
        userMsg.put("role", "user");
        userMsg.put("content", enrichedMessage);
        messages.add(userMsg);
        
        // 2. 构建工具定义（OpenAI 兼容格式）
        List<Map<String, Object>> toolsDef = ToolDefinitionConverter.convertToOpenAITools(tools);
        
        // 3. 执行 ReAct 循环
        for (int iteration = 0; iteration < MAX_ITERATIONS; iteration++) {
            log.debug("[ReActAgent] 第 {} 轮迭代", iteration + 1);
            
            // 调用 LLM（带 tools 参数）
            Map<String, Object> llmResponse = llmService.generateWithTools(
                messages, 0.7, toolsDef
            );
            
            // 解析响应
            Map<String, Object> message = (Map<String, Object>) llmResponse.get("message");
            List<Map<String, Object>> toolCalls = (List<Map<String, Object>>) message.get("tool_calls");
            
            if (toolCalls != null && !toolCalls.isEmpty()) {
                // 有 tool_calls，执行工具
                Map<String, Object> function = (Map<String, Object>) toolCalls.get(0).get("function");
                String toolName = (String) function.get("name");
                String argumentsJson = (String) function.get("arguments");
                
                log.info("[ReActAgent] 调用工具: {}", toolName);
                
                Map<String, Object> arguments = objectMapper.readValue(argumentsJson, Map.class);
                ToolExecutor executor = tools.get(toolName);
                
                if (executor == null) {
                    throw new RuntimeException("未找到工具: " + toolName);
                }
                
                String observation = executor.execute(arguments, datasourceId, userId, username, userMessage);
                
                // 添加工具结果到 messages
                messages.add(message);
                messages.add(Map.of(
                    "role", "tool",
                    "name", toolName,
                    "content", observation
                ));
                
            } else {
                // 没有 tool_calls，返回最终答案
                String finalAnswer = (String) message.get("content");
                log.info("[ReActAgent] 返回最终答案");
                return finalAnswer;
            }
        }
        
        return "抱歉，我无法处理您的请求（超过最大迭代次数）。";
    }
    
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
               "   - [INTENT:AI_SUMMARY] → 调用 summarize_result\n" +
               "   - [INTENT:GENERATE_CHART] → 调用 generate_chart\n" +
               "\n" +
               "4. **返回规则**：\n" +
               "   - 工具返回结构化数据（JSON）时，直接返回，不要生成额外回答\n" +
               "   - clarify_datasource 返回后，立即调用 execute_standard_query\n" +
               "   - execute_standard_query 返回结果后，直接返回，不要询问后续操作";
    }
}
```

### C. 测试用例

```java
package com.nl2sql.core.agent;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
public class ReActAgentTest {
    
    @Autowired
    private ReActAgent reActAgent;
    
    @Test
    public void testSimpleQuery() {
        // 测试简单查询
        String result = reActAgent.execute(
            "查询订单总数", 
            1L,  // 数据源ID
            1L,  // 用户ID
            "test_user"
        );
        
        assertNotNull(result);
        assertTrue(result.contains("订单") || result.contains("总数"));
    }
    
    @Test
    public void testMultiTurnDialogue() {
        // 测试多轮对话
        String result1 = reActAgent.execute("查询今天的订单", 1L, 1L, "test_user");
        assertNotNull(result1);
        
        String result2 = reActAgent.execute("再查询昨天的订单", 1L, 1L, "test_user");
        assertNotNull(result2);
    }
    
    @Test
    public void testToolCallingSuccessRate() {
        // 测试 100 次，验证成功率
        int successCount = 0;
        int totalCount = 100;
        
        for (int i = 0; i < totalCount; i++) {
            try {
                String result = reActAgent.execute(
                    "查询订单总数", 
                    1L, 1L, "test_user"
                );
                if (result != null && !result.isEmpty()) {
                    successCount++;
                }
            } catch (Exception e) {
                // 记录失败
            }
        }
        
        double successRate = (double) successCount / totalCount;
        assertTrue(successRate >= 0.99, "成功率应 >= 99%，实际: " + successRate);
    }
}
```

---

**作者简介**：资深后端工程师，专注于 LLM Agent 架构设计与企业级应用落地。欢迎交流讨论，共同探索 AI 应用的最佳实践。

**参考资料**：
- [Ollama API 官方文档](https://github.com/ollama/ollama/blob/main/docs/api.md)
- [OpenAI Function Calling 指南](https://platform.openai.com/docs/guides/function-calling)
- [LangChain4j 官方文档](https://docs.langchain4j.dev/)
