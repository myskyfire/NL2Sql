# P1 阶段架构改造设计文档

**版本**: v1.0  
**创建日期**: 2026-05-02  
**状态**: 设计中  
**负责人**: AI Assistant  

---

## 📋 **目录**

1. [概述](#1-概述)
2. [P1-1: 显式意图路由层](#2-p1-1-显式意图路由层)
3. [P1-2: 分离原子 Tools 和业务 Skills](#3-p1-2-分离原子-tools-和业务-skills)
4. [P1-3: 结构化监控指标](#4-p1-3-结构化监控指标)
5. [P1-4: 统一 Context 管理](#5-p1-4-统一-context-管理)
6. [实施计划](#6-实施计划)
7. [风险评估](#7-风险评估)
8. [验收标准](#8-验收标准)

---

## 1. 概述

### 1.1 背景

P0 阶段已完成：
- ✅ IntentClassifier - 意图分类器
- ✅ SkillResult - 统一响应格式
- ✅ ReActAgent 集成意图识别
- ✅ 所有 Groovy Skills 使用统一格式
- ✅ 移除旧格式兼容逻辑

**遗留问题**（来自架构审查报告）：
1. ❌ LLM 决策负担过重（需要从 20+ 工具中选择）
2. ❌ Skills 和 Tools 边界模糊（LLM 看到平铺的工具列表）
3. ❌ 缺少可观测性（只有日志，没有结构化指标）
4. ❌ 上下文管理混乱（状态分散在多处）

### 1.2 P1 目标

通过 4 个核心改造点，提升系统的：
- **性能**: 减少不必要的 LLM 调用，降低 Token 消耗
- **准确率**: 避免 LLM 选错工具，提升决策准确性
- **可维护性**: 清晰的架构分层，便于扩展和调试
- **可观测性**: 结构化指标，支持监控和告警

### 1.3 技术栈

- Java 21 (JDK: D:\Program Files\Java\jdk-21.0.6)
- Spring Boot 3.x
- Spring AOP (用于监控切面)
- Lombok
- Jackson (JSON 序列化)

---

## 2. P1-1: 显式意图路由层

### 2.1 问题分析

**当前架构**:
```
用户提问 → ReActAgent → LLM (带20+ tools) → LLM决策 → 执行Tool
```

**痛点**:
- LLM 需要从所有工具中盲目选择，决策空间大
- 每次调用都传递所有 tool definitions，Token 消耗高
- LLM 可能选错工具（如选择 `get_table_metadata` 而非 `execute_standard_query`）

### 2.2 设计方案

#### 2.2.1 核心组件

##### **RoutingStrategy.java** - 路由策略枚举
```java
package com.nl2sql.core.agent.routing;

public enum RoutingStrategy {
    /**
     * 直接调用：无需 LLM，直接执行指定 Skill
     * 适用场景：意图明确（QUERY/SUMMARY/CHART/CLARIFY）
     */
    DIRECT,
    
    /**
     * LLM 辅助决策：缩小工具范围，由 LLM 最终选择
     * 适用场景：意图不明确，但可缩小范围
     */
    LLM_ASSISTED,
    
    /**
     * 降级到完整 ReAct 流程
     * 适用场景：未知意图或路由失败
     */
    FALLBACK
}
```

##### **RoutingResult.java** - 路由结果
```java
package com.nl2sql.core.agent.routing;

import com.nl2sql.core.agent.intent.IntentClassifier.IntentClassification;
import lombok.Data;
import java.util.List;

@Data
public class RoutingResult {
    /**
     * 路由策略
     */
    private RoutingStrategy strategy;
    
    /**
     * 推荐的 Skills 列表
     * - DIRECT: 包含 1 个 Skill，直接调用
     * - LLM_ASSISTED: 包含 2-5 个 Skills，供 LLM 选择
     * - FALLBACK: 空列表，使用所有 Tools
     */
    private List<String> recommendedSkills;
    
    /**
     * 路由原因（用于日志和调试）
     */
    private String reason;
    
    /**
     * 原始意图分类
     */
    private IntentClassification intent;
    
    /**
     * 置信度（0-1）
     */
    private double confidence;
    
    // 便捷工厂方法
    public static RoutingResult direct(String skill, String reason, IntentClassification intent) {
        RoutingResult result = new RoutingResult();
        result.strategy = RoutingStrategy.DIRECT;
        result.recommendedSkills = List.of(skill);
        result.reason = reason;
        result.intent = intent;
        result.confidence = intent.getConfidence();
        return result;
    }
    
    public static RoutingResult llmAssisted(List<String> skills, String reason, IntentClassification intent) {
        RoutingResult result = new RoutingResult();
        result.strategy = RoutingStrategy.LLM_ASSISTED;
        result.recommendedSkills = skills;
        result.reason = reason;
        result.intent = intent;
        result.confidence = intent.getConfidence();
        return result;
    }
    
    public static RoutingResult fallback(String reason, IntentClassification intent) {
        RoutingResult result = new RoutingResult();
        result.strategy = RoutingStrategy.FALLBACK;
        result.recommendedSkills = List.of();
        result.reason = reason;
        result.intent = intent;
        result.confidence = 0.0;
        return result;
    }
}
```

##### **SkillRouter.java** - 技能路由器
```java
package com.nl2sql.core.agent.routing;

import com.nl2sql.core.agent.intent.IntentClassifier;
import com.nl2sql.core.agent.intent.IntentClassifier.IntentClassification;
import com.nl2sql.core.agent.intent.IntentClassifier.IntentType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.*;

@Slf4j
@Component
public class SkillRouter {
    
    private final IntentClassifier intentClassifier;
    
    /**
     * 意图 → Skills 映射表
     * Key: 意图类型
     * Value: 推荐的 Skills 列表
     */
    private Map<IntentType, List<String>> intentToSkillsMap;
    
    public SkillRouter(IntentClassifier intentClassifier) {
        this.intentClassifier = intentClassifier;
    }
    
    @PostConstruct
    public void init() {
        // 初始化路由规则
        intentToSkillsMap = new HashMap<>();
        
        // QUERY 意图 → 标准查询 Skill
        intentToSkillsMap.put(IntentType.QUERY, List.of(
            "execute_standard_query"
        ));
        
        // SUMMARY 意图 → AI 总结 Skill
        intentToSkillsMap.put(IntentType.SUMMARY, List.of(
            "summarize_result"
        ));
        
        // CHART 意图 → 图表生成 Skill
        intentToSkillsMap.put(IntentType.CHART, List.of(
            "generate_chart"
        ));
        
        // CLARIFY 意图 → 数据源澄清 Tool
        intentToSkillsMap.put(IntentType.CLARIFY, List.of(
            "clarify_datasource"
        ));
        
        // UNKNOWN 意图 → 所有 Skills（LLM 辅助决策）
        intentToSkillsMap.put(IntentType.UNKNOWN, List.of(
            "execute_standard_query",
            "summarize_result",
            "generate_chart",
            "clarify_datasource"
        ));
        
        log.info("[SkillRouter] 路由规则初始化完成，共 {} 条规则", intentToSkillsMap.size());
    }
    
    /**
     * 根据用户消息路由到对应的 Skill
     * 
     * @param userMessage 用户原始消息
     * @param datasourceId 数据源 ID（可选）
     * @return 路由结果
     */
    public RoutingResult route(String userMessage, Long datasourceId) {
        if (userMessage == null || userMessage.trim().isEmpty()) {
            return RoutingResult.fallback("消息为空", null);
        }
        
        // 步骤 1: 意图分类
        IntentClassification intent = intentClassifier.classify(userMessage);
        log.debug("[SkillRouter] 意图分类结果: type={}, confidence={}", 
            intent.getType(), intent.getConfidence());
        
        // 步骤 2: 根据意图和置信度决定路由策略
        return decideRoutingStrategy(intent, datasourceId);
    }
    
    /**
     * 决定路由策略
     */
    private RoutingResult decideRoutingStrategy(IntentClassification intent, Long datasourceId) {
        IntentType intentType = intent.getType();
        double confidence = intent.getConfidence();
        
        // 规则 1: 高置信度（>= 0.8）且非 UNKNOWN → 直接路由
        if (confidence >= 0.8 && intentType != IntentType.UNKNOWN) {
            List<String> skills = intentToSkillsMap.get(intentType);
            if (skills != null && !skills.isEmpty()) {
                String skill = skills.get(0);
                
                // 特殊处理：如果缺少 datasourceId，需要澄清
                if ("execute_standard_query".equals(skill) && datasourceId == null) {
                    log.info("[SkillRouter] 检测到 QUERY 意图但缺少 datasourceId，转为澄清");
                    return RoutingResult.direct(
                        "clarify_datasource",
                        "QUERY 意图但缺少数据源ID，先澄清",
                        intent
                    );
                }
                
                return RoutingResult.direct(
                    skill,
                    String.format("高置信度 %s 意图 (confidence=%.2f)", intentType, confidence),
                    intent
                );
            }
        }
        
        // 规则 2: 中等置信度（0.5-0.8）→ LLM 辅助决策
        if (confidence >= 0.5) {
            List<String> skills = intentToSkillsMap.getOrDefault(
                intentType, 
                intentToSkillsMap.get(IntentType.UNKNOWN)
            );
            
            return RoutingResult.llmAssisted(
                skills,
                String.format("中等置信度 %s 意图 (confidence=%.2f)，LLM 辅助决策", intentType, confidence),
                intent
            );
        }
        
        // 规则 3: 低置信度（< 0.5）→ 降级到完整 ReAct
        return RoutingResult.fallback(
            String.format("低置信度意图 (confidence=%.2f)，降级到完整 ReAct", confidence),
            intent
        );
    }
    
    /**
     * 获取所有已注册的 Skills（用于 FALLBACK 场景）
     */
    public List<String> getAllSkills() {
        Set<String> allSkills = new HashSet<>();
        for (List<String> skills : intentToSkillsMap.values()) {
            allSkills.addAll(skills);
        }
        return new ArrayList<>(allSkills);
    }
}
```

#### 2.2.2 集成到 ReActAgent

**修改 ReActAgent.execute() 方法**:

```java
public String execute(String userMessage, Long datasourceId, 
                     List<Map<String, Object>> historyMessages) {
    // ... 现有代码：获取 userId/username、构建消息列表 ...
    
    // ✅ P1-1: 意图识别 + 路由
    RoutingResult routing = skillRouter.route(userMessage, datasourceId);
    log.info("[ReActAgent] 路由结果: strategy={}, skills={}, reason={}", 
        routing.getStrategy(), routing.getRecommendedSkills(), routing.getReason());
    
    // 根据路由策略执行
    switch (routing.getStrategy()) {
        case DIRECT:
            // 直接调用推荐的 Skill，跳过 LLM
            return executeDirectSkill(routing.getRecommendedSkills().get(0), 
                                     arguments, datasourceId, userId, username, userMessage);
        
        case LLM_ASSISTED:
            // LLM 辅助决策：只传递推荐的 Skills
            List<Map<String, Object>> filteredTools = filterToolsByNames(
                routing.getRecommendedSkills(), toolsDef
            );
            log.info("[ReActAgent] LLM 辅助决策，过滤后工具数: {}", filteredTools.size());
            return executeWithFilteredTools(messages, filteredTools, MAX_ITERATIONS);
        
        case FALLBACK:
        default:
            // 降级：完整 ReAct 流程
            log.info("[ReActAgent] 降级到完整 ReAct 流程");
            return executeFullReAct(messages, toolsDef, MAX_ITERATIONS);
    }
}

/**
 * 直接调用 Skill（跳过 LLM）
 */
private String executeDirectSkill(String skillName, Map<String, Object> arguments,
                                  Long datasourceId, Long userId, String username, 
                                  String userMessage) {
    log.info("[ReActAgent] 直接调用 Skill: {}", skillName);
    
    ToolExecutor executor = tools.get(skillName);
    if (executor == null) {
        log.error("[ReActAgent] 未找到 Skill: {}", skillName);
        return SkillResult.error("SKILL_NOT_FOUND", "未找到 Skill: " + skillName).toJson();
    }
    
    try {
        String result = executor.execute(arguments, datasourceId, userId, username, userMessage);
        log.info("[ReActAgent] Skill 执行成功: {}", skillName);
        return result;
    } catch (Exception e) {
        log.error("[ReActAgent] Skill 执行失败: {}", skillName, e);
        return SkillResult.error("SKILL_EXECUTION_ERROR", e.getMessage()).toJson();
    }
}

/**
 * 根据名称过滤工具定义
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
 * 使用过滤后的工具列表执行 ReAct 循环
 */
private String executeWithFilteredTools(List<Map<String, Object>> messages,
                                        List<Map<String, Object>> filteredTools,
                                        int maxIterations) {
    // 复用现有的 ReAct 循环逻辑，但使用 filteredTools
    for (int iteration = 0; iteration < maxIterations; iteration++) {
        // ... 现有逻辑 ...
        Map<String, Object> llmResponse = llmService.generateWithTools(
            messages, 0.5, filteredTools  // 使用过滤后的工具
        );
        // ... 解析和执行 ...
    }
}

/**
 * 完整 ReAct 流程（原有逻辑）
 */
private String executeFullReAct(List<Map<String, Object>> messages,
                                List<Map<String, Object>> allTools,
                                int maxIterations) {
    // 将原有的 execute() 方法主体提取到这里
    // ... 现有代码 ...
}
```

### 2.3 配置管理

**在 AgentConfig.java 中注入 SkillRouter**:

```java
@Bean
public ReActAgent reActAgent(SkillRouter skillRouter) {  // ✅ 新增参数
    ReActAgent agent = new ReActAgent(llmService, skillRouter);  // ✅ 传入 router
    
    // ... 注册 Tools 和 Skills ...
    
    return agent;
}
```

### 2.4 预期效果

| 指标 | 改造前 | 改造后 | 提升 |
|------|--------|--------|------|
| **平均响应时间** | 3-5s | 0.5-2s | ⬇️ 60% |
| **Token 消耗** | 100% | 40-80% | ⬇️ 20-60% |
| **工具选择准确率** | ~85% | ~95% | ⬆️ 10% |
| **直接路由占比** | 0% | 40-60% | 新增 |

---

## 3. P1-2: 分离原子 Tools 和业务 Skills

### 3.1 问题分析

**当前问题**:
- LLM 看到的是一堆平铺的工具，分不清哪些是 Skills，哪些是原子 Tools
- LLM 可能选择原子 Tool（如 `get_table_metadata`）而非高层 Skill（如 `execute_standard_query`）
- 工具列表过长，增加 LLM 决策复杂度

### 3.2 设计方案

#### 3.2.1 ToolVisibility 枚举

```java
package com.nl2sql.core.agent.tool;

public enum ToolVisibility {
    /**
     * 公开：对 LLM 可见，LLM 可以直接调用
     * 适用：高层 Skills（如 execute_standard_query）
     */
    PUBLIC,
    
    /**
     * 内部：对 LLM 不可见，仅 Skills 内部调用
     * 适用：原子 Tools（如 get_table_metadata, generate_sql）
     */
    INTERNAL
}
```

#### 3.2.2 修改 ToolExecutor 接口

```java
@FunctionalInterface
public interface ToolExecutor {
    String execute(Map<String, Object> arguments, Long datasourceId, 
                  Long userId, String username, String userMessage);
    
    default String getDescription() {
        return "工具描述";
    }
    
    /**
     * ✅ P1-2: 工具可见性
     */
    default ToolVisibility getVisibility() {
        return ToolVisibility.PUBLIC;  // 默认公开
    }
}
```

#### 3.2.3 标记原子 Tools 为 INTERNAL

**在 AgentConfig.java 中标记**:

```java
// 原子 Tools（INTERNAL）
agent.registerTool("get_table_metadata", ..., ToolVisibility.INTERNAL);
agent.registerTool("generate_sql_from_schema", ..., ToolVisibility.INTERNAL);
agent.registerTool("execute_sql", ..., ToolVisibility.INTERNAL);
agent.registerTool("validate_sql", ..., ToolVisibility.INTERNAL);

// 业务 Skills（PUBLIC）
agent.registerTool("execute_standard_query", ..., ToolVisibility.PUBLIC);
agent.registerTool("summarize_result", ..., ToolVisibility.PUBLIC);
agent.registerTool("generate_chart", ..., ToolVisibility.PUBLIC);
agent.registerTool("clarify_datasource", ..., ToolVisibility.PUBLIC);
```

#### 3.2.4 过滤 INTERNAL Tools

**修改 ToolDefinitionConverter**:

```java
public static List<Map<String, Object>> convertToOpenAITools(
    Map<String, ToolExecutor> tools, 
    boolean includeInternal  // ✅ 新增参数
) {
    return tools.entrySet().stream()
        .filter(entry -> {
            ToolExecutor executor = entry.getValue();
            // 如果 includeInternal=false，过滤掉 INTERNAL tools
            return includeInternal || executor.getVisibility() == ToolVisibility.PUBLIC;
        })
        .map(entry -> {
            // ... 转换为 OpenAI 格式 ...
        })
        .collect(Collectors.toList());
}
```

**在 ReActAgent 中使用**:

```java
// LLM 辅助决策场景：仅传递 PUBLIC tools
List<Map<String, Object>> publicTools = ToolDefinitionConverter.convertToOpenAITools(
    tools, false  // 不包含 INTERNAL
);

// 完整 ReAct 场景：传递所有 tools（包括 INTERNAL）
List<Map<String, Object>> allTools = ToolDefinitionConverter.convertToOpenAITools(
    tools, true  // 包含 INTERNAL
);
```

### 3.3 预期效果

- ✅ LLM 看到的工具数量从 20+ 减少到 5-8 个
- ✅ 避免 LLM 选择错误的原子 Tool
- ✅ 提升决策准确率和响应速度

---

## 4. P1-3: 结构化监控指标

### 4.1 问题分析

**当前问题**:
- 只有日志，没有结构化指标
- 无法追踪：哪个 Skill 被调用了多少次？平均耗时？失败率？
- 无法分析：LLM 为什么选择了这个 Skill 而不是那个？

### 4.2 设计方案

#### 4.2.1 SkillMetricsRecorder.java

```java
package com.nl2sql.core.agent.monitoring;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
public class SkillMetricsRecorder {
    
    /**
     * Skill 执行指标
     */
    @Data
    public static class SkillMetrics {
        private String skillName;
        private AtomicLong totalCalls = new AtomicLong(0);
        private AtomicLong successCalls = new AtomicLong(0);
        private AtomicLong failedCalls = new AtomicLong(0);
        private AtomicLong totalExecutionTimeMs = new AtomicLong(0);
        private AtomicLong maxExecutionTimeMs = new AtomicLong(0);
        
        public void recordSuccess(long executionTimeMs) {
            totalCalls.incrementAndGet();
            successCalls.incrementAndGet();
            totalExecutionTimeMs.addAndGet(executionTimeMs);
            maxExecutionTimeMs.updateAndGet(current -> Math.max(current, executionTimeMs));
        }
        
        public void recordFailure(long executionTimeMs) {
            totalCalls.incrementAndGet();
            failedCalls.incrementAndGet();
            totalExecutionTimeMs.addAndGet(executionTimeMs);
        }
        
        public double getAverageExecutionTimeMs() {
            long total = totalCalls.get();
            return total > 0 ? (double) totalExecutionTimeMs.get() / total : 0.0;
        }
        
        public double getSuccessRate() {
            long total = totalCalls.get();
            return total > 0 ? (double) successCalls.get() / total : 0.0;
        }
    }
    
    private final ConcurrentHashMap<String, SkillMetrics> metricsMap = new ConcurrentHashMap<>();
    
    /**
     * 记录 Skill 执行开始
     */
    public void recordStart(String skillName) {
        metricsMap.computeIfAbsent(skillName, k -> new SkillMetrics());
    }
    
    /**
     * 记录 Skill 执行成功
     */
    public void recordSuccess(String skillName, long executionTimeMs) {
        SkillMetrics metrics = metricsMap.computeIfAbsent(skillName, k -> new SkillMetrics());
        metrics.recordSuccess(executionTimeMs);
        
        log.debug("[SkillMetrics] Skill={}, status=SUCCESS, time={}ms", 
            skillName, executionTimeMs);
    }
    
    /**
     * 记录 Skill 执行失败
     */
    public void recordFailure(String skillName, long executionTimeMs, String error) {
        SkillMetrics metrics = metricsMap.computeIfAbsent(skillName, k -> new SkillMetrics());
        metrics.recordFailure(executionTimeMs);
        
        log.warn("[SkillMetrics] Skill={}, status=FAILURE, time={}ms, error={}", 
            skillName, executionTimeMs, error);
    }
    
    /**
     * 获取所有指标
     */
    public Map<String, SkillMetrics> getAllMetrics() {
        return new ConcurrentHashMap<>(metricsMap);
    }
    
    /**
     * 获取指定 Skill 的指标
     */
    public SkillMetrics getMetrics(String skillName) {
        return metricsMap.get(skillName);
    }
    
    /**
     * 重置所有指标
     */
    public void resetAll() {
        metricsMap.clear();
        log.info("[SkillMetrics] 所有指标已重置");
    }
}
```

#### 4.2.2 SkillMetricsAspect.java (AOP 切面)

```java
package com.nl2sql.core.agent.monitoring;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class SkillMetricsAspect {
    
    private final SkillMetricsRecorder metricsRecorder;
    
    /**
     * 拦截所有 Skill 执行方法
     */
    @Around("execution(* com.nl2sql.core.agent.skills..*.execute(..))")
    public Object recordSkillExecution(ProceedingJoinPoint joinPoint) throws Throwable {
        String skillName = extractSkillName(joinPoint);
        long startTime = System.currentTimeMillis();
        
        try {
            metricsRecorder.recordStart(skillName);
            
            Object result = joinPoint.proceed();
            
            long executionTime = System.currentTimeMillis() - startTime;
            metricsRecorder.recordSuccess(skillName, executionTime);
            
            return result;
            
        } catch (Exception e) {
            long executionTime = System.currentTimeMillis() - startTime;
            metricsRecorder.recordFailure(skillName, executionTime, e.getMessage());
            throw e;
        }
    }
    
    /**
     * 从 JoinPoint 提取 Skill 名称
     */
    private String extractSkillName(ProceedingJoinPoint joinPoint) {
        String className = joinPoint.getTarget().getClass().getSimpleName();
        return className.replace("Skill", "").toLowerCase();
    }
}
```

#### 4.2.3 SkillMetricsController.java (查询接口)

```java
package com.nl2sql.web.controller;

import com.nl2sql.core.agent.monitoring.SkillMetricsRecorder;
import com.nl2sql.core.agent.monitoring.SkillMetricsRecorder.SkillMetrics;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/skill-metrics")
@RequiredArgsConstructor
public class SkillMetricsController {
    
    private final SkillMetricsRecorder metricsRecorder;
    
    /**
     * 获取所有 Skill 指标
     */
    @GetMapping
    public Map<String, Object> getAllMetrics() {
        Map<String, Object> response = new HashMap<>();
        
        Map<String, SkillMetrics> allMetrics = metricsRecorder.getAllMetrics();
        Map<String, Map<String, Object>> metricsData = new HashMap<>();
        
        for (Map.Entry<String, SkillMetrics> entry : allMetrics.entrySet()) {
            SkillMetrics m = entry.getValue();
            Map<String, Object> data = new HashMap<>();
            data.put("totalCalls", m.getTotalCalls().get());
            data.put("successCalls", m.getSuccessCalls().get());
            data.put("failedCalls", m.getFailedCalls().get());
            data.put("successRate", m.getSuccessRate());
            data.put("averageExecutionTimeMs", m.getAverageExecutionTimeMs());
            data.put("maxExecutionTimeMs", m.getMaxExecutionTimeMs().get());
            metricsData.put(entry.getKey(), data);
        }
        
        response.put("success", true);
        response.put("data", metricsData);
        response.put("timestamp", System.currentTimeMillis());
        
        return response;
    }
    
    /**
     * 重置所有指标
     */
    @PostMapping("/reset")
    public Map<String, Object> resetMetrics() {
        metricsRecorder.resetAll();
        
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", "指标已重置");
        return response;
    }
}
```

### 4.3 预期效果

✅ **可观测性提升**:
- 实时监控每个 Skill 的调用次数、成功率、平均耗时
- 快速定位性能瓶颈和错误热点
- 支持告警（如成功率 < 90% 时触发）

✅ **数据驱动优化**:
- 基于指标发现低频/失败的 Skills
- 优化高频 Skills 的性能
- 调整路由规则（P1-1）基于实际数据

---

## 5. P1-4: 统一 Context 管理

### 5.1 问题分析

**当前问题**:
- 状态分散在多处：SessionContext、SkillContext、消息历史
- `summarize_result` 从 SessionContext 获取 SQL，其他 Skills 从参数获取
- 不一致的数据来源导致难以维护

### 5.2 设计方案

#### 5.2.1 AgentContext.java

```java
package com.nl2sql.core.agent.context;

import lombok.Builder;
import lombok.Data;
import java.util.HashMap;
import java.util.Map;

@Data
@Builder
public class AgentContext {
    /**
     * 用户 ID
     */
    private Long userId;
    
    /**
     * 用户名
     */
    private String username;
    
    /**
     * 会话 ID
     */
    private String sessionId;
    
    /**
     * 数据源 ID
     */
    private Long datasourceId;
    
    /**
     * 最近一次生成的 SQL
     */
    private String lastSQL;
    
    /**
     * 最近一次的用户问题
     */
    private String lastQuery;
    
    /**
     * 扩展字段（用于传递自定义数据）
     */
    @Builder.Default
    private Map<String, Object> metadata = new HashMap<>();
    
    /**
     * 设置扩展字段
     */
    public void setMetadata(String key, Object value) {
        this.metadata.put(key, value);
    }
    
    /**
     * 获取扩展字段
     */
    @SuppressWarnings("unchecked")
    public <T> T getMetadata(String key, Class<T> type) {
        Object value = this.metadata.get(key);
        return value != null ? type.cast(value) : null;
    }
}
```

#### 5.2.2 集成到 ReActAgent

```java
public String execute(String userMessage, Long datasourceId, ...) {
    // ✅ P1-4: 创建统一的 AgentContext
    AgentContext context = AgentContext.builder()
        .userId(userId)
        .username(username)
        .sessionId(sessionId)
        .datasourceId(datasourceId)
        .lastQuery(userMessage)
        .build();
    
    // 传递给 SkillRouter
    RoutingResult routing = skillRouter.route(userMessage, context);
    
    // 执行 Skill 时传递 context
    return executeSkill(skillName, context, arguments);
}
```

#### 5.2.3 修改 SkillContext

```java
@Data
public class SkillContext {
    /**
     * ✅ P1-4: 统一的 AgentContext
     */
    private AgentContext agentContext;
    
    /**
     * 参数（保持向后兼容）
     */
    private Map<String, Object> parameters;
    
    // 从 agentContext 获取常用字段
    public Long getUserId() {
        return agentContext != null ? agentContext.getUserId() : null;
    }
    
    public Long getDatasourceId() {
        return agentContext != null ? agentContext.getDatasourceId() : null;
    }
    
    public String getLastSQL() {
        return agentContext != null ? agentContext.getLastSQL() : null;
    }
}
```

### 5.3 预期效果

- ✅ 统一的数据来源，消除不一致
- ✅ 便于扩展（通过 metadata 传递自定义数据）
- ✅ 简化 Skills 的参数获取逻辑

---

## 6. 实施计划

### 6.1 优先级排序

| 改造点 | 优先级 | 预计工时 | 依赖 |
|--------|--------|----------|------|
| P1-1: 显式意图路由层 | 🔴 P0 | 2-3 天 | 无 |
| P1-3: 结构化监控指标 | 🟡 P1 | 1-2 天 | 无 |
| P1-2: 分离原子 Tools | 🟡 P1 | 1-2 天 | P1-1 |
| P1-4: 统一 Context | 🟢 P2 | 2-3 天 | P1-1, P1-2 |

### 6.2 详细时间表

**Week 1**:
- Day 1-2: P1-1 实施（SkillRouter + ReActAgent 集成）
- Day 3: P1-1 测试和优化
- Day 4-5: P1-3 实施（监控指标 + AOP 切面）

**Week 2**:
- Day 1-2: P1-2 实施（ToolVisibility + 过滤逻辑）
- Day 3-5: P1-4 实施（AgentContext + 迁移现有代码）

**Week 3**:
- Day 1-3: 集成测试和性能测试
- Day 4-5: 文档更新和代码审查

---

## 7. 风险评估

### 7.1 技术风险

| 风险 | 概率 | 影响 | 缓解措施 |
|------|------|------|----------|
| 路由规则不准确导致错误调用 | 中 | 高 | 保留 FALLBACK 策略，通过监控迭代优化 |
| AOP 切面影响性能 | 低 | 中 | 异步记录指标，最小化切面开销 |
| Context 迁移破坏现有 Skills | 中 | 高 | 保持向后兼容，逐步迁移 |

### 7.2 业务风险

| 风险 | 概率 | 影响 | 缓解措施 |
|------|------|------|----------|
| 响应时间波动 | 低 | 中 | 灰度发布，监控关键指标 |
| 用户体验下降 | 低 | 高 | A/B 测试，快速回滚机制 |

---

## 8. 验收标准

### 8.1 P1-1 验收标准

- ✅ 直接路由场景响应时间 < 100ms
- ✅ LLM 辅助场景 Token 消耗降低 ≥ 50%
- ✅ 路由准确率 ≥ 90%（通过监控验证）
- ✅ 无回归测试失败

### 8.2 P1-2 验收标准

- ✅ LLM 看到的工具数量 ≤ 8 个
- ✅ 原子 Tools 无法被 LLM 直接调用
- ✅ 所有 Skills 正常工作

### 8.3 P1-3 验收标准

- ✅ 提供 `/api/admin/skill-metrics` 接口
- ✅ 实时监控所有 Skill 的执行指标
- ✅ 指标数据持久化（可选：存入数据库）

### 8.4 P1-4 验收标准

- ✅ 所有 Skills 使用统一的 AgentContext
- ✅ 无状态不一致问题
- ✅ 代码覆盖率 ≥ 80%

---

## 附录

### A. 相关文件清单

**P1-1 新增文件**:
- `nl2sql-core/src/main/java/com/nl2sql/core/agent/routing/RoutingStrategy.java`
- `nl2sql-core/src/main/java/com/nl2sql/core/agent/routing/RoutingResult.java`
- `nl2sql-core/src/main/java/com/nl2sql/core/agent/routing/SkillRouter.java`

**P1-2 修改文件**:
- `nl2sql-core/src/main/java/com/nl2sql/core/agent/tool/ToolVisibility.java`
- `nl2sql-core/src/main/java/com/nl2sql/core/agent/tool/ToolExecutor.java`
- `nl2sql-core/src/main/java/com/nl2sql/core/agent/ToolDefinitionConverter.java`

**P1-3 新增文件**:
- `nl2sql-core/src/main/java/com/nl2sql/core/agent/monitoring/SkillMetricsRecorder.java`
- `nl2sql-core/src/main/java/com/nl2sql/core/agent/monitoring/SkillMetricsAspect.java`
- `nl2sql-web/src/main/java/com/nl2sql/web/controller/SkillMetricsController.java`

**P1-4 新增文件**:
- `nl2sql-core/src/main/java/com/nl2sql/core/agent/context/AgentContext.java`

### B. 参考资料

- [P0 阶段总结](./P0_IMPLEMENTATION_SUMMARY.md)
- [架构审查报告](./ARCHITECTURE_REVIEW.md)
- [Skills 架构设计](./SKILLS_ARCHITECTURE.md)

---

**文档版本历史**:
- v1.0 (2026-05-02): 初始版本，完成 P1 阶段详细设计
