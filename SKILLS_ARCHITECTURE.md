# Skills 架构设计文档

## 1. 什么是 Skills？

**Skills（技能）** 是封装了完整业务流程的高级抽象单元，由多个原子 **Tools（工具）** 组合而成。

### Skills vs Tools 对比

| 维度 | Tools（工具） | Skills（技能） |
|------|--------------|---------------|
| **粒度** | 原子操作 | 业务流程 |
| **示例** | `retrieve_schema`, `generate_sql`, `execute_sql` | `StandardQuerySkill`, `ReportWithInsightsSkill` |
| **职责** | 单一功能 | 端到端解决方案 |
| **复用性** | 被 Skills 调用 | 可被其他 Skills 或 Agent 直接调用 |
| **复杂度** | 低 | 高（包含错误处理、重试、风险评估等） |

---

## 2. 当前实现的 Skills

### 2.1 StandardQuerySkill（标准查询技能）

**文件位置**: `nlp2sql-core/src/main/java/com/nl2sql/core/agent/skills/StandardQuerySkill.java`

**功能描述**:  
封装完整的查询生命周期，适用于大多数数据查询场景。

**执行流程**:
```
用户问题 
  ↓
Step 1: 检索表结构 (NL2SQLTool.retrieveSchema)
  ↓
Step 2: 生成 SQL (NL2SQLTool.generateSQL)
  ↓
Step 2.5: SQL 优化与风险评估
  - 检测 IN 子查询关联问题
  - LLM 自主评估风险等级 (LOW/MEDIUM/HIGH)
  - 必要时调用 EXPLAIN 辅助分析
  ↓
Step 3: 执行 SQL (SQLExecutionTool.executeSQL)
  - 支持自动修正（最多重试 2 次）
  - 失败时调用 NL2SQLTool.autoFixSQL
  ↓
返回查询结果
```

**核心特性**:
- ✅ **SQL 后处理**: 自动检测并警告 ON 条件中的 IN 子查询
- ✅ **风险评估**: LLM 自主判断 + EXPLAIN 辅助，分为 LOW/MEDIUM/HIGH/UNCERTAIN 四级
- ✅ **自动修正**: 执行失败时自动调用 LLM 修复 SQL，最多重试 2 次
- ✅ **风险阻断**: HIGH 风险直接阻断执行，保护数据库性能

**使用场景**:
- 简单数据查询："查询最近 10 条订单"
- 统计分析："统计上月各地区销售额"
- 筛选过滤："找出消费超过 1000 元的用户"

**返回结果**:
```java
QueryResult {
    success: boolean,
    data: List<Map<String, Object>>,
    rowCount: int,
    executionTime: double,
    sql: String,
    error: String (可选),
    needsClarification: boolean (可选)
}
```

---

### 2.2 ReportWithInsightsSkill（报表与洞察技能）

**文件位置**: `nlp2sql-core/src/main/java/com/nl2sql/core/agent/skills/ReportWithInsightsSkill.java`

**功能描述**:  
在标准查询基础上，增加 AI 智能总结和图表推荐，适用于深度分析场景。

**执行流程**:
```
用户问题
  ↓
Step 1: 调用 StandardQuerySkill 执行查询
  ↓
Step 2: 如果数据量 > 5 行，生成 AI 总结 (AISummaryTool)
  ↓
Step 3: 推荐合适的图表类型 (ChartRecommendationTool)
  ↓
返回完整报告（数据 + 总结 + 图表建议）
```

**核心特性**:
- ✅ **智能总结**: 自动提炼关键数据点和趋势
- ✅ **图表推荐**: 根据数据特征推荐 bar/line/pie 等图表类型
- ✅ **一键分析**: 用户无需分别调用多个工具

**使用场景**:
- 趋势分析："分析近 3 个月销售趋势并给出建议"
- 对比分析："对比各产品线的业绩表现"
- 综合报告："生成上月经营分析报告"

**返回结果**:
```java
ReportResult {
    success: boolean,
    data: List<Map<String, Object>>,
    rowCount: int,
    executionTime: double,
    sql: String,
    aiSummary: String (可选),
    chartRecommendations: List<ChartRecommendation> (可选),
    error: String (可选)
}
```

---

## 3. Skills 的 Tool 封装

为了让 Agent 能够调用 Skills，需要将 Skills 包装成 LangChain4j 的 `@Tool`。

### 3.1 StandardQuerySkillTool

**文件位置**: `nlp2sql-core/src/main/java/com/nl2sql/core/agent/tools/StandardQuerySkillTool.java`

```java
@Tool("执行标准查询流程。适用于用户有明确查询需求的场景。" +
      "输入：用户问题、数据源ID、用户ID、用户名。" +
      "输出：查询结果数据、行数、执行时间、SQL语句。")
public String executeStandardQuery(String question, Long datasourceId, Long userId, String username) {
    // 调用 StandardQuerySkill.execute()
    // 将 QueryResult 序列化为 JSON 字符串返回
}
```

### 3.2 ReportWithInsightsSkillTool

**文件位置**: `nlp2sql-core/src/main/java/com/nl2sql/core/agent/tools/ReportWithInsightsSkillTool.java`

```java
@Tool("生成数据分析报告和洞察。适用于用户需要深度分析的场景。" +
      "输入：用户问题、数据源ID、用户ID、用户名。" +
      "输出：包含数据、AI总结、图表推荐的完整报告。")
public String generateReportWithInsights(String question, Long datasourceId, Long userId, String username) {
    // 调用 ReportWithInsightsSkill.execute()
    // 将 ReportResult 序列化为 JSON 字符串返回
}
```

---

## 4. Skills 注册与发现

**配置文件**: `nlp2sql-core/src/main/java/com/nl2sql/core/agent/AgentConfig.java`

```java
@Configuration
public class AgentConfig {
    
    @Autowired(required = false)
    private StandardQuerySkillTool standardQuerySkillTool;
    
    @Autowired(required = false)
    private ReportWithInsightsSkillTool reportWithInsightsSkillTool;
    
    @Bean
    public ReActAgent reActAgent(ChatModel chatModel) {
        ReActAgent agent = new ReActAgent(chatModel);
        
        // 注册核心 Tools
        agent.registerTool("nl2sql", nl2sqlTool);
        agent.registerTool("execute_sql", sqlExecutionTool);
        
        // 注册 Skills（如果存在）
        if (standardQuerySkillTool != null) {
            agent.registerTool("execute_standard_query", standardQuerySkillTool);
            log.info("✅ 已注册 Skill: StandardQuerySkill");
        }
        
        if (reportWithInsightsSkillTool != null) {
            agent.registerTool("generate_report_with_insights", reportWithInsightsSkillTool);
            log.info("✅ 已注册 Skill: ReportWithInsightsSkill");
        }
        
        return agent;
    }
}
```

---

## 5. Agent 如何使用 Skills

### 5.1 SystemMessage 引导

在 `ReActAgent` 的 SystemMessage 中明确告诉 LLM 何时使用 Skills：

```
## 第二步：选择合适的高级技能

根据用户需求选择合适的技能：

#### 场景1：简单查询（大多数情况）
使用 execute_standard_query(question, datasourceId, userId, username)
适用：用户想要查询数据、统计数据、筛选数据等

#### 场景2：复杂分析
使用 generate_report_with_insights(question, datasourceId, userId, username)
适用：用户要求"分析"、"总结"、"报告"、"趋势"等关键词

#### 场景3：精细控制
手动调用底层 Tools：
- retrieve_schema → 查看表结构
- generate_sql → 生成 SQL
- execute_sql → 执行 SQL
适用：用户明确要求分步执行或调试 SQL
```

### 5.2 实际调用示例

**示例 1：简单查询**
```
用户：查询最近 10 条订单

Agent 思考：这是一个简单查询，使用 StandardQuerySkill
Agent 行动：调用 execute_standard_query("查询最近 10 条订单", 1, 123, "user")
Agent 观察：收到查询结果
Agent 回复：直接返回数据给用户
```

**示例 2：复杂分析**
```
用户：分析上月销售趋势并给出建议

Agent 思考：这需要深度分析，使用 ReportWithInsightsSkill
Agent 行动：调用 generate_report_with_insights("分析上月销售趋势", 1, 123, "user")
Agent 观察：收到包含数据、AI总结、图表推荐的完整报告
Agent 回复：返回完整分析报告
```

---

## 6. Skills 的优势

### 6.1 对 Agent 的价值

1. **简化决策**: Agent 只需选择合适的 Skill，无需关心内部细节
2. **提高成功率**: Skills 内置了错误处理、重试、风险评估等机制
3. **统一体验**: 不同场景下返回结果格式一致

### 6.2 对开发者的价值

1. **代码复用**: Skills 可以被其他 Skills 调用（如 ReportWithInsightsSkill 调用 StandardQuerySkill）
2. **易于测试**: 每个 Skill 可以独立单元测试
3. **可维护性**: 业务流程集中在 Skill 中，修改逻辑只需改一处

### 6.3 对用户的价值

1. **一站式解决**: 用户无需多次交互，一次提问即可得到完整答案
2. **智能增强**: 自动获得 AI 总结、图表推荐等增值服务
3. **安全可靠**: 内置风险评估和自动修正，减少错误

---

## 7. 未来扩展方向

### 7.1 新增 Skills 建议

基于业务需求，可以扩展以下 Skills：

1. **DataComparisonSkill（数据对比技能）**
   - 功能：对比两个时间段/地区/产品的数据差异
   - 适用："对比今年和去年同期的销售额"

2. **TrendAnalysisSkill（趋势分析技能）**
   - 功能：识别数据趋势、周期性、异常点
   - 适用："分析近 6 个月的用户增长趋势"

3. **AnomalyDetectionSkill（异常检测技能）**
   - 功能：自动检测数据异常（突增/突降）
   - 适用："找出销售额异常的日期"

4. **CohortAnalysisSkill（同期群分析技能）**
   - 功能：按用户注册时间分组分析留存率
   - 适用："分析各月新用户的 30 日留存率"

### 7.2 架构升级方向

1. **声明式元数据**
   ```java
   @Skill(
       name = "standard_query",
       description = "执行标准数据查询",
       tags = {"query", "data"},
       priority = 1.0
   )
   public class StandardQuerySkill { ... }
   ```

2. **自动发现机制**
   - 通过注解扫描自动注册所有 Skills
   - 无需在 AgentConfig 中硬编码

3. **Skill Router**
   - 基于意图分类自动路由到最佳 Skill
   - 减少 LLM 决策负担

4. **Skill 编排引擎**
   - 支持声明式的 Skill Chain
   - 例如：`StandardQuerySkill → AISummaryTool → ChartRecommendationTool`

---

## 8. 最佳实践

### 8.1 何时使用 Skills？

✅ **应该使用 Skills**:
- 业务流程固定且频繁使用
- 需要统一的错误处理和重试机制
- 多个 Tools 需要按特定顺序调用

❌ **不应该使用 Skills**:
- 一次性或极少使用的场景
- 需要高度灵活控制的场景
- 调试或学习阶段

### 8.2 Skill 设计原则

1. **单一职责**: 每个 Skill 只解决一类问题
2. **可组合性**: Skills 之间可以互相调用
3. **容错性**: 内置完善的错误处理和降级策略
4. **可观测性**: 详细的日志记录，便于排查问题

### 8.3 命名规范

- Skill 类名：`{功能}Skill`（如 `StandardQuerySkill`）
- Tool 封装类名：`{功能}SkillTool`（如 `StandardQuerySkillTool`）
- Tool 名称：`execute_{功能}` 或 `generate_{功能}`（如 `execute_standard_query`）

---

## 9. 常见问题

### Q1: Skills 和 Tools 有什么区别？

**A**: Tools 是原子操作（如查询表结构、生成 SQL），Skills 是业务流程（如完整查询生命周期）。Skills 内部会调用多个 Tools。

### Q2: 为什么不直接用 Tools，而要封装成 Skills？

**A**: 
- Skills 提供了更高层次的抽象，简化了 Agent 的决策
- Skills 内置了错误处理、重试、风险评估等企业级特性
- Skills 更容易复用和维护

### Q3: 如何决定创建新的 Skill？

**A**: 当发现以下情况时，考虑创建新 Skill：
- 某个业务流程被频繁使用
- 多个 Tools 总是按固定顺序调用
- 需要统一的错误处理和日志记录

### Q4: Skills 会影响性能吗？

**A**: 
- Skills 本身只是 Java 方法调用，开销极小
- 主要性能瓶颈在于 LLM 调用和 SQL 执行
- Skills 反而可能提升整体效率（减少不必要的 Tool 调用）

---

## 10. 总结

当前的 Skills 实现是一个**基于 Skills 理念的 Tool 封装模式**，具备以下特点：

✅ **已实现**:
- 2 个可用的 Skills（StandardQuerySkill、ReportWithInsightsSkill）
- Skills 的 Tool 封装和注册机制
- Agent 可以通过 SystemMessage 引导选择 Skills

⚠️ **待完善**:
- 缺少声明式元数据和自动发现机制
- Skills 数量较少，覆盖场景有限
- 缺少 Skill Router 和编排引擎

🎯 **下一步**:
- 根据业务需求扩展更多 Skills
- 实现声明式注解和自动注册
- 添加 Skill 监控和优化机制
