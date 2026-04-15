# Skills vs Tools 架构分析

## 一、核心概念对比

### 1. Tools（原子工具）
- **粒度**：小，单一职责
- **示例**：`retrieve_schema`, `generate_sql`, `execute_sql`
- **调用者**：LLM Agent 或 Skills
- **特点**：无状态、可独立测试

### 2. Skills（业务技能）⭐ 新增
- **粒度**：中，封装完整业务流程
- **示例**：`StandardQuerySkill`, `ReportWithInsightsSkill`
- **调用者**：LLM Agent 或其他Skills
- **特点**：有流程、有状态、可复用

### 3. SystemMessage（策略指导）
- **粒度**：大，定义整体行为
- **示例**："当用户询问数据时，先确认时间范围"
- **调用者**：LLM（隐式遵循）
- **特点**：软约束、灵活性高

---

## 二、DeepSeek的3个Skills评估

### ✅ Skill 1: standard_query（强烈推荐）

**价值评分**：⭐⭐⭐⭐⭐

**理由**：
1. **高频复用**：80%的查询都会走这个流程
2. **流程标准化**：确保每次都经过权限检查、成本评估等关键步骤
3. **降低LLM负担**：LLM不需要记住7步流程，只需调用一个Skill
4. **易于维护**：修改流程只需改一处代码

**实现位置**：
```
nlp2sql-core/src/main/java/com/nl2sql/core/agent/skills/StandardQuerySkill.java
```

**使用方式**：
```java
// LLM调用
String result = agent.execute("standard_query", {
    "question": "查询上月订单",
    "datasourceId": 1,
    "userId": 123
});
```

---

### ✅ Skill 2: report_with_insights（推荐）

**价值评分**：⭐⭐⭐⭐

**理由**：
1. **组合技能**：依赖Skill 1，增加增值功能
2. **场景明确**：适用于分析型、报表型需求
3. **用户体验好**：自动生成总结和图表推荐

**实现位置**：
```
nlp2sql-core/src/main/java/com/nl2sql/core/agent/skills/ReportWithInsightsSkill.java
```

**使用方式**：
```java
// LLM调用
String result = agent.execute("report_with_insights", {
    "question": "分析上月销售趋势并给出结论",
    "datasourceId": 1,
    "userId": 123
});
```

---

### ❌ Skill 3: clarify_if_needed（不推荐独立Skill）

**价值评分**：⭐⭐

**理由**：
1. **不是完整流程**：只是一个触发条件+动作
2. **应该内嵌**：在StandardQuerySkill内部处理即可
3. **增加复杂度**：独立的澄清Skill会让Agent决策更复杂

**更好的实现**：
```java
// 在StandardQuerySkill内部处理
public QueryResult execute(String question, ...) {
    // 检测是否需要澄清
    if (needsClarification(question)) {
        return QueryResult.needsClarification(getMissingFields(question));
    }
    
    // 继续正常流程...
}
```

---

## 三、是否需要Skills？

### ✅ 需要Skills的理由

| 维度 | 说明 |
|------|------|
| **代码复用** | StandardQuery会被多处调用（直接查询、报表生成、数据导出） |
| **流程标准化** | 确保每次查询都经过权限检查、成本评估等关键步骤 |
| **降低LLM负担** | LLM不需要记住复杂的7步流程，只需调用一个Skill |
| **易于测试** | Skill可以独立单元测试，不依赖LLM |
| **可观测性** | 可以记录每个Skill的执行时间和成功率 |
| **版本管理** | Skill可以有版本号，方便灰度发布和回滚 |

### ❌ 不需要Skills的理由

| 维度 | 说明 |
|------|------|
| **增加复杂度** | 需要维护额外的Skill类 |
| **灵活性降低** | 硬编码的流程可能不适合所有场景 |
| **LangChain4j已支持** | AiServices本身就有工具编排能力 |

---

## 四、最终建议：混合架构

### 推荐架构（三层）

```
┌─────────────────────────────────────┐
│  Layer 3: Agent (智能编排)           │
│  - 理解用户意图                       │
│  - 选择合适的Skill                    │
│  - 处理多轮对话                       │
└──────────────┬──────────────────────┘
               │
┌──────────────▼──────────────────────┐
│  Layer 2: Skills (业务技能) ⭐       │
│  - StandardQuerySkill                │
│  - ReportWithInsightsSkill           │
│  - DataExportSkill                   │
│  - TrendAnalysisSkill                │
└──────────────┬──────────────────────┘
               │
┌──────────────▼──────────────────────┐
│  Layer 1: Atomic Tools (原子工具)    │
│  - retrieve_schema                   │
│  - generate_sql                      │
│  - execute_sql                       │
│  - check_permissions                 │
│  - summarize_data                    │
└─────────────────────────────────────┘
```

### 何时使用Skill？何时使用Tool？

| 场景 | 选择 | 原因 |
|------|------|------|
| 单一操作（如查schema） | Tool | 简单、原子化 |
| 多步流程（如标准查询） | Skill | 封装复杂性 |
| 条件分支（如权限检查） | Skill | 流程控制 |
| 可复用逻辑（如报表生成） | Skill | DRY原则 |
| LLM需要灵活组合 | Tool | 保持灵活性 |

---

## 五、实施计划

### Phase 1: 基础Skills（已完成）
- [x] StandardQuerySkill
- [x] ReportWithInsightsSkill

### Phase 2: 集成到Agent
- [ ] 更新NL2SQLAgent的SystemMessage，告知LLM可用的Skills
- [ ] 添加Skill路由逻辑
- [ ] 前端支持Skill返回的特殊结果类型（澄清、成本过高等）

### Phase 3: 更多Skills
- [ ] DataExportSkill（数据导出）
- [ ] TrendAnalysisSkill（趋势分析）
- [ ] AnomalyDetectionSkill（异常检测）

### Phase 4: 监控与优化
- [ ] Skill执行时间监控
- [ ] Skill成功率统计
- [ ] A/B测试不同Skill的效果

---

## 六、代码示例

### 1. Agent如何调用Skill

```java
@SystemMessage("""
    你是一个智能数据分析助手。你可以根据用户需求选择合适的技能：
    
    1. 如果用户只是查询数据，使用 standard_query 技能
    2. 如果用户需要分析和结论，使用 report_with_insights 技能
    3. 如果信息不足，主动询问用户
    
    可用技能：
    - standard_query: 标准查询流程（包含权限检查、成本评估）
    - report_with_insights: 生成报表+AI总结+图表推荐
    """)
public interface NL2SQLAgent {
    @UserMessage("{{userQuestion}}")
    String chat(String userQuestion);
}
```

### 2. Skill内部调用Tools

```java
@Component
public class StandardQuerySkill {
    @Autowired private NL2SQLTool nl2sqlTool;      // Tool
    @Autowired private SQLExecutionTool execTool;   // Tool
    @Autowired private SQLSecurityValidator validator; // Tool
    
    public QueryResult execute(...) {
        // Step 1: 调用Tool
        String schema = nl2sqlTool.retrieveSchema(question, datasourceId);
        
        // Step 2: 调用Tool
        String sql = nl2sqlTool.generateSQL(question, schema);
        
        // Step 3: 调用Tool
        validator.checkColumnPermission(sql, userId);
        
        // ... 更多步骤
        
        return result;
    }
}
```

---

## 七、总结

### DeepSeek的建议是否合理？

| Skill | 是否采纳 | 原因 |
|-------|---------|------|
| standard_query | ✅ 完全采纳 | 核心价值高，已实现 |
| report_with_insights | ✅ 完全采纳 | 增值功能，已实现 |
| clarify_if_needed | ❌ 部分采纳 | 内嵌到其他Skill中 |

### 关键洞察

1. **Skills不是必须的，但强烈推荐**
   - 对于简单项目，直接用Tools就够了
   - 对于复杂业务，Skills能显著提升可维护性

2. **Skills的核心价值**
   - **封装复杂性**：把7步流程封装成1个调用
   - **标准化**：确保关键步骤不被遗漏
   - **复用性**：多个场景共用同一套逻辑

3. **不要过度设计**
   - 不是所有流程都需要封装成Skill
   - 只有满足以下条件才考虑：
     - 被多处调用
     - 流程超过3步
     - 有明确的业务语义

### 下一步行动

1. **立即可做**：
   - 编译并测试已创建的2个Skills
   - 更新Agent的SystemMessage，让LLM知道如何使用Skills

2. **短期规划**（1-2周）：
   - 前端适配Skill返回的特殊结果类型
   - 添加Skill执行日志和监控

3. **长期规划**（1-2月）：
   - 根据实际使用情况，逐步增加更多Skills
   - 建立Skill市场，支持动态加载
