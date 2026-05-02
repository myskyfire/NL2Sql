# P1-1 ReActAgent 集成剩余工作

## 当前状态

✅ **已完成**：
1. SkillRouter、RoutingStrategy、RoutingResult 核心组件创建完成
2. ReActAgent 构造函数已修改为接受 SkillRouter 参数
3. AgentConfig.java 已注入 SkillRouter Bean
4. 编译通过，基础架构就绪

⏸️ **待完成**：
ReActAgent.execute() 方法的路由逻辑集成

---

## 剩余工作内容

### 需要修改的方法

#### 1. `execute()` 方法（第70行开始）

**当前逻辑**：
```java
public String execute(String userMessage, Long datasourceId, 
                     List<Map<String, Object>> historyMessages) {
    // 获取用户信息
    Long userId = UserContext.getUserId();
    String username = UserContext.getUsername();
    
    // ✅ P0: 意图识别（仅记录日志）
    IntentClassification intent = intentClassifier.classify(userMessage);
    log.info("意图识别结果: type={}, confidence={}", ...);
    
    // 继续执行完整的 ReAct 循环...
}
```

**需要改为**：
```java
public String execute(String userMessage, Long datasourceId, 
                     List<Map<String, Object>> historyMessages) {
    // 获取用户信息
    Long userId = UserContext.getUserId();
    String username = UserContext.getUsername();
    
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
        
        case LLM_ASSISTED:
            // LLM 辅助决策：只传递推荐的 Skills
            List<Map<String, Object>> allToolsDef = ToolDefinitionConverter.convertToOpenAITools(tools, true);
            List<Map<String, Object>> filteredTools = filterToolsByNames(
                routing.getRecommendedSkills(), allToolsDef
            );
            log.info("[ReActAgent] LLM 辅助决策，过滤后工具数: {} -> {}", 
                allToolsDef.size(), filteredTools.size());
            return executeWithFilteredTools(userMessage, datasourceId, userId, username, 
                                           historyMessages, filteredTools);
        
        case FALLBACK:
        default:
            // 降级：完整 ReAct 流程
            log.info("[ReActAgent] 降级到完整 ReAct 流程");
            return executeFullReAct(userMessage, datasourceId, userId, username, historyMessages);
    }
}
```

---

#### 2. 新增 `executeDirectSkill()` 方法

**位置**：在 `buildSystemPrompt()` 方法之前添加

```java
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
```

---

#### 3. 新增 `filterToolsByNames()` 方法

```java
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
```

---

#### 4. 新增 `executeWithFilteredTools()` 方法

```java
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
```

---

#### 5. 新增 `executeFullReAct()` 方法

```java
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
```

---

#### 6. 提取 `executeReActLoop()` 方法

**说明**：将当前 `execute()` 方法中从"构建消息列表"到"返回结果"的整个 ReAct 循环逻辑提取到这个新方法中。

```java
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
    
    // 注入历史消息
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
            
            // ... （原有的 ReAct 循环逻辑保持不变）
            
        } catch (Exception e) {
            log.error("[ReActAgent] 执行失败", e);
            throw new RuntimeException("ReAct Agent 执行失败: " + e.getMessage(), e);
        }
    }
    
    log.warn("[ReActAgent] 达到最大迭代次数");
    return "抱歉，我无法处理您的请求（超过最大迭代次数）。";
}
```

---

## 实施建议

### 方案 A：谨慎渐进式（推荐）

1. **第一步**：先添加三个辅助方法（`executeDirectSkill`、`filterToolsByNames`、`executeReActLoop`）
2. **第二步**：修改 `execute()` 方法，调用新的路由逻辑
3. **第三步**：编译测试，确保没有破坏现有功能
4. **第四步**：编写单元测试验证三种路由策略

**优点**：
- 每一步都可验证
- 出现问题容易回滚
- 代码审查更容易

**缺点**：
- 需要多次提交

---

### 方案 B：一次性完成

在一个大的 search_replace 操作中完成所有修改。

**优点**：
- 一次提交完成
- 代码原子性更好

**缺点**：
- 风险较高，容易出错
- 如果失败需要完全回滚
- 难以定位具体问题

---

## 风险提示

⚠️ **重要注意事项**：

1. **ReActAgent 是核心组件**：任何修改都可能影响整个 NL2SQL 系统
2. **建议在新会话中实施**：避免当前会话上下文过长导致工具调用失败
3. **充分测试**：修改后必须运行所有现有测试用例
4. **备份原文件**：在修改前使用 `git stash` 或创建分支

---

## 下一步行动

建议在新的会话中执行以下命令开始实施：

```bash
# 1. 创建新分支
git checkout -b feature/p1-1-react-agent-integration

# 2. 读取 ReActAgent.java 完整内容
# 3. 按上述方案逐步修改
# 4. 每步完成后编译测试
# 5. 全部完成后运行单元测试
```

---

## 相关文档

- [P1_ARCHITECTURE_DESIGN.md](./P1_ARCHITECTURE_DESIGN.md) - P1 阶段架构设计
- [P1_IMPLEMENTATION_SUMMARY.md](./P1_IMPLEMENTATION_SUMMARY.md) - P1 阶段实施总结
- SkillRouter.java - 技能路由器实现
- RoutingStrategy.java - 路由策略枚举
- RoutingResult.java - 路由结果类

---

**创建时间**：2026-05-02  
**最后更新**：2026-05-02  
**状态**：待实施
