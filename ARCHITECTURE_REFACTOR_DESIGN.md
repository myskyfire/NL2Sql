# NL2SQL 架构改造设计文档：从 ReAct Agent 到 Pipeline Orchestrator

**版本**: v1.0  
**创建日期**: 2026-05-06  
**状态**: 设计中（待评审）  
**作者**: AI Assistant  

---

## 📋 目录

1. [背景与问题](#1-背景与问题)
2. [改造目标](#2-改造目标)
3. [架构定位澄清](#3-架构定位澄清)
4. [改造方案总览](#4-改造方案总览)
5. [P0：架构正名与文档更新](#5-p0架构正名与文档更新)
6. [P1：Groovy 脚本拆分与原子化](#6-p1groovy-脚本拆分与原子化)
7. [P2：Workflow 引擎实现](#7-p2workflow-引擎实现)
8. [P3：动态 Skill 发现与组合](#8-p3动态-skill-发现与组合)
9. [P4：LLM 价值增强（可选）](#9-p4llm-价值增强可选)
10. [实施计划与里程碑](#10-实施计划与里程碑)
11. [风险评估与应对](#11-风险评估与应对)
12. [验收标准](#12-验收标准)

---

## 1. 背景与问题

### 1.1 当前架构矛盾

#### 核心问题
当前系统名义上采用 **ReAct Agent 模式**，但实际运行是 **确定性 Pipeline 模式**，存在以下矛盾：

| 维度 | ReAct 模式假设 | 实际运行情况 |
|------|---------------|-------------|
| **任务类型** | 开放探索型（如网页搜索、代码调试） | 确定性管道（NL2SQL） |
| **决策方式** | LLM 自主推理和规划 | 硬编码的 5 步流程 |
| **工具调用** | LLM 动态决定调用哪些工具 | 路由后直接调用 Groovy Skill |
| **执行路径** | 不确定，依赖 LLM 输出 | 固定：表检索 → SQL生成 → 风险评估 → 执行 → 修正 |

#### 三个关键矛盾点

**矛盾 1：ReAct 循环被"架空"**

```java
// ReActAgent.java:91-118 的路由逻辑
switch (routing.getStrategy()) {
    case DIRECT:  // 高置信度意图（>=0.8）
        return executeDirectSkill(...);  // 直接调用 Skill，跳过 LLM
    
    case LLM_ASSISTED:  // 中等置信度（0.5-0.8）
        return executeWithFilteredTools(...);  // 缩小工具范围后给 LLM
    
    case FALLBACK:  // 低置信度（<0.5）
        return executeFullReAct(...);  // 才走完整 ReAct 循环
}
```

**实际情况**：大部分查询都是 `QUERY` 意图，直接走 `DIRECT` 路由，ReAct 循环几乎不被触发。

---

**矛盾 2：真正的编排逻辑在 Groovy 脚本中**

```groovy
// StandardQuerySkill.groovy 内部硬编码了 5 步流程
def execute(Map params) {
    // 1. 表结构检索
    def schema = retrieveSchema(params.question, params.datasourceId)
    
    // 2. SQL 生成
    def sql = generateSQL(params.question, schema)
    
    // 3. 风险评估
    def risk = assessRisk(sql)
    
    // 4. SQL 执行
    def result = executeSQL(sql, params.datasourceId)
    
    // 5. 自动修正（如果失败）
    if (!result.success) {
        result = autoFixSQL(sql, result.error)
    }
    
    return result
}
```

这些步骤是**确定性的顺序执行**，不是 LLM 自主决策的。AgentConfig.java 中注册的 `execute_standard_query` 工具本质上是一个**黑盒**。

---

**矛盾 3：System Prompt 在抑制 ReAct**

```text
// 当前 System Prompt（ReActAgent.java:377-383）
"你是数据分析助手。
规则：
1. 数据源ID为null时调用clarify_datasource，有ID直接使用
2. 查询调用execute_standard_query(question, datasourceId)
3. [INTENT:AI_SUMMARY]→summarize_result，[INTENT:GENERATE_CHART]→generate_chart
4. 工具返回JSON直接输出，不添加内容
5. 禁止输出思考过程，未知时调用工具"
```

**问题**：System Prompt 在**禁止 LLM 自主探索**，要求它直接调用高级 Skill。这说明设计上就不想让 LLM 做 ReAct 推理。

---

### 1.2 为什么这不是坏事？

Pipeline 模式对 NL2SQL 来说反而更合适：

✅ **执行路径确定、可控**：不会出现 LLM 幻觉导致的异常行为  
✅ **每个步骤可单独测试**：便于调试和优化  
✅ **Token 消耗更低**：不需要多轮 ReAct 循环  
✅ **性能更可预测**：延迟稳定，适合生产环境  

**但问题是**：名义上叫 "ReAct Agent" 会产生误导，也让后续开发方向不清晰。

---

## 2. 改造目标

### 2.1 核心目标

1. **正名**：将架构从 "ReAct Agent" 重新定位为 "Pipeline Orchestrator"
2. **解耦**：将 Groovy 脚本中的硬编码流程拆分为可复用的原子 Tool
3. **灵活**：支持声明式 Workflow 配置，替代硬编码流程
4. **可扩展**：新增 Skill 无需修改核心代码

### 2.2 非目标

❌ **不追求完全的 Agent 自主性**：NL2SQL 需要强约束，不适合开放探索  
❌ **不引入复杂的 ReAct 循环**：保持确定性管道的优势  
❌ **不改变现有的意图路由机制**：IntentClassifier + SkillRouter 仍然有效  

---

## 3. 架构定位澄清

### 3.1 新架构名称

| 旧名称 | 新名称 | 说明 |
|--------|--------|------|
| `ReActAgent` | `NL2SQLPipelineOrchestrator` | 强调这是管道编排器，不是通用 Agent |
| `ReActAgentConfig` | `PipelineOrchestratorConfig` | 配置类同步重命名 |
| "ReAct 模式" | "Pipeline 编排模式" | 文档和注释中统一术语 |

### 3.2 架构分层

```
┌─────────────────────────────────────────────┐
│          NL2SQLPipelineOrchestrator         │  ← 顶层编排器（原 ReActAgent）
│  - 意图分类（IntentClassifier）              │
│  - 路由分发（SkillRouter）                   │
│  - 直接调用 / LLM 辅助 / 降级               │
└──────────────┬──────────────────────────────┘
               │
               ▼
┌─────────────────────────────────────────────┐
│            Skill Layer（技能层）             │  ← 高级能力封装
│  - execute_standard_query（标准查询）        │
│  - report-with-insights（报告生成）          │
│  - clarify_question（问题澄清）              │
└──────────────┬──────────────────────────────┘
               │
               ▼
┌─────────────────────────────────────────────┐
│         Tool Layer（原子工具层）             │  ← 可复用原子能力
│  - RetrieveTableSchemaTool                  │
│  - GenerateSQLTool                          │
│  - AssessSQLRiskTool                        │
│  - ExecuteSafeSQLTool                       │
│  - AutoFixSQLTool                           │
└─────────────────────────────────────────────┘
```

**关键原则**：
- **Skill**：封装完整业务流程，对外暴露单一接口
- **Tool**：原子能力，可被多个 Skill 复用
- **Orchestrator**：负责意图识别和路由，不关心具体执行细节

---

## 4. 改造方案总览

### 4.1 改造阶段划分

| 阶段 | 名称 | 工作量 | 风险 | 优先级 |
|------|------|--------|------|--------|
| P0 | 架构正名与文档更新 | 1天 | 🟩 低 | ⭐⭐⭐⭐⭐ |
| P1 | Groovy 脚本拆分与原子化 | 3-5天 | 🟨 中 | ⭐⭐⭐⭐ |
| P2 | Workflow 引擎实现 | 2-3天 | 🟨 中 | ⭐⭐⭐ |
| P3 | 动态 Skill 发现与组合 | 2天 | 🟨 中 | ⭐⭐⭐ |
| P4 | LLM 价值增强（可选） | 1-2天 | 🟨 中 | ⭐⭐ |

### 4.2 改造前后对比

| 维度 | 改造前 | 改造后 |
|------|--------|--------|
| **架构名称** | ReAct Agent | Pipeline Orchestrator |
| **编排方式** | Groovy 脚本硬编码 | 声明式 YAML + 可选 Groovy |
| **Skill 注册** | 硬编码目录扫描 | 自动发现 SKILL.md |
| **Skill 间调用** | 直接引用 Bean | 通过 SkillContext.callSkill() |
| **Tool 粒度** | 粗粒度（黑盒 Skill） | 细粒度（原子 Tool） |
| **LLM 角色** | 被抑制（禁止思考） | 聚焦核心价值（意图分类 + SQL 生成） |

---

## 5. P0：架构正名与文档更新

### 5.1 需要重命名的类/文件清单

| 当前名称 | 新名称 | 所在文件路径 | 引用次数预估 |
|---------|--------|-------------|------------|
| `ReActAgent` | `NL2SQLPipelineOrchestrator` | `nl2sql-core/src/main/java/com/nl2sql/core/agent/ReActAgent.java` | ~15处 |
| `ReActAgent` (Bean) | `pipelineOrchestrator` | `AgentConfig.java:141` | ~5处 |
| 日志中的 `[ReActAgent]` | `[PipelineOrchestrator]` | 所有 log.info/warn/error | ~20处 |
| 注释中的 "ReAct" | "Pipeline" | 所有 JavaDoc 和行注释 | ~30处 |

**注意**：
- ❌ **不修改** `ReActAgentTest`（如果存在），因为测试类不影响生产代码
- ❌ **不修改** 外部文档（如 README.md），由用户自行处理

### 5.2 System Prompt 优化方案

#### 当前问题
```text
"你是数据分析助手。
规则：
1. 数据源ID为null时调用clarify_datasource，有ID直接使用
2. 查询调用execute_standard_query(question, datasourceId)
3. [INTENT:AI_SUMMARY]→summarize_result，[INTENT:GENERATE_CHART]→generate_chart
4. 工具返回JSON直接输出，不添加内容
5. 禁止输出思考过程，未知时调用工具"
```

**问题点**：
- ❌ "禁止输出思考过程" 抑制了透明度
- ❌ 没有说明这是 Pipeline 模式
- ❌ 缺少对模糊问题的主动澄清能力

#### 优化后的 System Prompt

```text
你是 NL2SQL 查询管道编排器（Pipeline Orchestrator），负责将自然语言问题转换为可执行的 SQL 查询。

## 你的职责
1. **意图分类**：识别用户问题的类型（QUERY/REPORT/CLARIFY/UNKNOWN）
2. **SQL 生成**：根据检索到的表结构，生成准确的 SQL 语句
3. **结果解释**：用自然语言总结查询结果

## 管道流程（你不需要执行，由系统自动处理）
1. 表结构检索 → 2. SQL 生成 → 3. 风险评估 → 4. SQL 执行 → 5. 结果返回

## 重要规则
- ✅ 当用户问题模糊时（如缺少时间范围、维度），主动调用 `clarify_question` Skill 反问
- ✅ 生成 SQL 后，简要说明你的推理逻辑（例如："我选择了 orders 表，因为..."）
- ❌ 不要尝试直接调用 retrieve_schema、execute_sql 等底层工具（这些由管道自动处理）
- ❌ 不要向用户展示完整的 Thought/Action/Observation 循环（但可以简要说明关键决策）

## 示例对话
用户：查询最近7天的订单数量
你：[意图: QUERY, 置信度: 0.95]
   我将查询 orders 表中 order_date 在最近7天内的记录数量。
   [调用 execute_standard_query Skill]
```

**关键变化**：
- ✅ 明确说明这是 **Pipeline Orchestrator**
- ✅ 允许简要说明推理逻辑（提升可解释性）
- ✅ 保留对底层工具的调用限制（保持确定性）
- ✅ 增加 `clarify_question` 的主动调用能力

### 5.3 SKILL.md 文档更新要点

#### standard-query/SKILL.md 需要补充的内容

```markdown
## Workflow 配置状态

⚠️ **当前状态**：Workflow YAML 配置已定义但未启用，执行流程由 Groovy 脚本硬编码控制。

📋 **规划中**：未来将通过 WorkflowEngine 解析 workflow.yaml，实现声明式流程编排。

## 调用链路

```
NL2SQLPipelineOrchestrator
  └─→ execute_standard_query (Groovy Skill)
       ├─→ RetrieveSchemaTool (Spring Bean)
       ├─→ GenerateSQLTool (Spring Bean + LLM)
       ├─→ AssessSQLRiskTool (Spring Bean)
       ├─→ ExecuteWithRetryTool (Spring Bean)
       └─→ AutoFixSQLTool (Spring Bean + LLM, 可选)
```

## 与其他 Skill 的关系

- **report-with-insights**：内部通过 `context.callSkill("execute_standard_query", params)` 调用本 Skill
- **clarify_question**：当本 Skill 检测到参数缺失时，可触发此 Skill 进行反问
```

### 5.4 实施步骤

1. **重命名核心类**（使用 IDE 重构功能）：
   ```bash
   # 在 IDEA 中：Refactor → Rename
   ReActAgent → NL2SQLPipelineOrchestrator
   ```

2. **更新 System Prompt**：
   - 修改 `NL2SQLPipelineOrchestrator.java:buildSystemPrompt()` 方法
   - 替换为优化后的 prompt

3. **更新 SKILL.md**：
   - 在 `standard-query/SKILL.md` 中添加 "Workflow 配置状态" 章节
   - 补充调用链路图

4. **编译验证**：
   ```powershell
   cd "D:\WorkSpace\idea workspace\NL2Sql"
   mvn clean package -DskipTests
   ```

### 5.5 影响点分析

#### 问题根因
当前架构名称与实际实现不符，导致开发方向不清晰。

#### 修复方案
- 重命名 `ReActAgent` → `NL2SQLPipelineOrchestrator`
- 更新 System Prompt，明确说明 Pipeline 模式
- 补充 SKILL.md 文档

#### 可能影响点
- **功能影响**：无功能性变化，仅正名
- **性能影响**：无
- **兼容性影响**：
  - ⚠️ Spring Bean 名称变化：`reactAgent` → `pipelineOrchestrator`
  - 如果有外部系统通过 Bean 名称引用，需要同步修改
- **边界场景**：无

#### 建议测试用例
1. 启动应用，确认日志中显示 `[PipelineOrchestrator] 初始化完成`
2. 发送查询请求，确认正常返回结果
3. 检查 System Prompt 是否包含 "Pipeline Orchestrator" 字样

#### 风险分级
🟩 **低风险**：仅重命名和文档更新，不影响业务逻辑

---

## 6. P1：Groovy 脚本拆分与原子化

### 6.1 当前问题

**StandardQuerySkill.groovy (~1390 行)** 承担了过多职责：
- 表结构检索
- SQL 生成
- 风险评估
- SQL 执行（带重试）
- 自动修正
- 图表生成
- 流式事件推送

**违反单一职责原则**，难以维护和测试。

### 6.2 拆分方案

#### 目标架构

```
StandardQuerySkill.groovy (编排层，~200行)
├── RetrieveTableSchemaTool (已有，优化接口)
├── GenerateSQLTool (已有，优化接口)
├── AssessSQLRiskTool (已有，优化接口)
├── ExecuteWithRetryTool (新建)
├── AutoFixSQLTool (从现有逻辑抽取)
└── ChartGeneratorTool (从现有逻辑抽取)
```

#### 各 Tool 职责定义

| Tool 名称 | 职责 | 输入 | 输出 |
|----------|------|------|------|
| `RetrieveTableSchemaTool` | 根据问题检索相关表结构 | question, datasourceId | schema JSON |
| `GenerateSQLTool` | 基于问题和表结构生成 SQL | question, schema | sql, confidence |
| `AssessSQLRiskTool` | 评估 SQL 风险等级 | sql, datasourceId | riskLevel, risks |
| `ExecuteWithRetryTool` | 执行 SQL（带重试和超时控制） | sql, datasourceId, maxRetries | data, error |
| `AutoFixSQLTool` | 自动修正失败的 SQL | sql, error, schema | fixedSql, success |
| `ChartGeneratorTool` | 根据数据生成图表配置 | data, chartType | echartsConfig |

### 6.3 实施步骤

#### 步骤 1：创建 ExecuteWithRetryTool

```java
@Component
@Slf4j
public class ExecuteWithRetryTool {
    
    @Autowired
    private SQLExecutionTool sqlExecutionTool;
    
    private static final int DEFAULT_MAX_RETRIES = 3;
    private static final long RETRY_DELAY_MS = 1000;
    
    /**
     * 执行 SQL（带重试）
     */
    public ExecutionResult executeWithRetry(String sql, Long datasourceId, 
                                           Long userId, String username,
                                           int maxRetries) {
        Exception lastException = null;
        
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                log.info("[ExecuteWithRetryTool] 第{}次尝试执行 SQL", attempt);
                
                ExecutionResult result = sqlExecutionTool.executeSQL(
                    sql, datasourceId, userId, username
                );
                
                if (result.isSuccess()) {
                    log.info("[ExecuteWithRetryTool] 执行成功");
                    return result;
                }
                
                log.warn("[ExecuteWithRetryTool] 执行失败: {}", result.getError());
                lastException = new RuntimeException(result.getError());
                
            } catch (Exception e) {
                log.error("[ExecuteWithRetryTool] 执行异常", e);
                lastException = e;
            }
            
            // 重试前等待
            if (attempt < maxRetries) {
                try {
                    Thread.sleep(RETRY_DELAY_MS * attempt); // 指数退避
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        
        return ExecutionResult.failure("执行失败，已重试" + maxRetries + "次: " + lastException.getMessage());
    }
}
```

#### 步骤 2：抽取 AutoFixSQLTool

```java
@Component
@Slf4j
public class AutoFixSQLTool {
    
    @Autowired
    private LLMService llmService;
    
    @Autowired
    private MetadataService metadataService;
    
    /**
     * 自动修正 SQL
     */
    public FixResult autoFix(String originalSql, String errorMessage, 
                            String schema, Long datasourceId) {
        log.info("[AutoFixSQLTool] 开始自动修正 SQL");
        
        // 构建修正 prompt
        String prompt = buildFixPrompt(originalSql, errorMessage, schema);
        
        // 调用 LLM 生成修正后的 SQL
        Map<String, Object> response = llmService.generateWithTools(
            List.of(Map.of("role", "user", "content", prompt)),
            0.3, // 低温度，确保确定性
            List.of() // 不使用工具
        );
        
        String fixedSql = extractSQLFromResponse(response);
        
        // 验证修正后的 SQL
        ValidationResult validation = validateSQL(fixedSql, datasourceId);
        
        return new FixResult(fixedSql, validation.isValid(), validation.getError());
    }
    
    private String buildFixPrompt(String originalSql, String error, String schema) {
        return String.format(
            "原始 SQL: %s\n错误信息: %s\n表结构: %s\n请修正 SQL 并只返回修正后的 SQL 语句",
            originalSql, error, schema
        );
    }
}
```

#### 步骤 3：简化 StandardQuerySkill.groovy

```groovy
// StandardQuerySkill.groovy (简化后，~200行)
class StandardQuerySkill implements SkillExecutor {
    
    @Autowired
    RetrieveTableSchemaTool schemaTool
    
    @Autowired
    GenerateSQLTool sqlTool
    
    @Autowired
    AssessSQLRiskTool riskTool
    
    @Autowired
    ExecuteWithRetryTool executeTool
    
    @Autowired
    AutoFixSQLTool fixTool
    
    @Autowired
    ChartGeneratorTool chartTool
    
    SkillResult execute(Map params) {
        String question = params.question
        Long datasourceId = params.datasourceId
        Long userId = params.userId
        String username = params.username
        
        try {
            // 1. 检索表结构
            def schema = schemaTool.execute(question, datasourceId)
            
            // 2. 生成 SQL
            def sqlResult = sqlTool.generate(question, schema, datasourceId)
            String sql = sqlResult.sql
            
            // 3. 风险评估
            def risk = riskTool.assess(sql, datasourceId)
            if (risk.level == 'HIGH') {
                return SkillResult.error("HIGH_RISK_SQL", "SQL 风险过高: ${risk.risks}")
            }
            
            // 4. 执行（带重试）
            def execResult = executeTool.executeWithRetry(
                sql, datasourceId, userId, username, maxRetries: 3
            )
            
            // 5. 如果失败，尝试自动修正
            if (!execResult.success) {
                log.info("执行失败，尝试自动修正")
                def fixResult = fixTool.autoFix(sql, execResult.error, schema, datasourceId)
                
                if (fixResult.success) {
                    log.info("修正成功，重新执行")
                    execResult = executeTool.executeWithRetry(
                        fixResult.fixedSql, datasourceId, userId, username, maxRetries: 1
                    )
                }
            }
            
            // 6. 生成图表（如果需要）
            def chartConfig = null
            if (params.chartType && execResult.success) {
                chartConfig = chartTool.generate(execResult.data, params.chartType)
            }
            
            // 7. 返回结果
            return SkillResult.success([
                data: execResult.data,
                sql: sql,
                chart: chartConfig,
                risk: risk
            ])
            
        } catch (Exception e) {
            log.error("StandardQuerySkill 执行失败", e)
            return SkillResult.error("EXECUTION_ERROR", e.message)
        }
    }
}
```

### 6.4 验收标准

1. ✅ `StandardQuerySkill.groovy` 行数从 ~1390 降至 ~200
2. ✅ 每个原子 Tool 有独立的单元测试
3. ✅ 回归测试：标准查询功能正常
4. ✅ 异常场景覆盖：超时、空结果、权限错误等

### 6.5 影响点分析

#### 问题根因
Groovy 脚本职责过重，违反单一职责原则，难以维护和测试。

#### 修复方案
- 拆分为 6 个原子 Tool
- 编排层简化为 ~200 行
- 每个 Tool 独立可测试

#### 可能影响点
- **功能影响**：✅ 正面，提升可维护性和可测试性
- **性能影响**：⚠️ 轻微增加（多次方法调用），但可忽略
- **兼容性影响**：❌ 无，对外接口不变
- **边界场景**：需确保所有异常场景都被覆盖

#### 建议测试用例
1. 正常查询：验证完整流程
2. SQL 执行失败：验证自动修正
3. 高风险 SQL：验证拦截
4. 超时场景：验证重试机制
5. 空结果：验证正确处理

#### 风险分级
🟨 **中风险**：涉及核心查询逻辑重构，需充分测试

---

## 7. P2：Workflow 引擎实现

### 7.1 设计目标

用**声明式 YAML** 替代 Groovy 脚本中的硬编码流程，提升可读性和可配置性。

### 7.2 Workflow YAML 格式设计

```yaml
# standard-query/workflow.yaml
name: execute_standard_query
version: "1.0"
description: "执行标准查询（表检索 → SQL生成 → 风险评估 → 执行）"

steps:
  - id: retrieve_schema
    tool: RetrieveTableSchemaTool
    params:
      question: "${params.question}"
      datasourceId: "${params.datasourceId}"
    timeout: 10s
  
  - id: generate_sql
    tool: GenerateSQLTool
    params:
      question: "${params.question}"
      schema: "${steps.retrieve_schema.output}"
      datasourceId: "${params.datasourceId}"
    timeout: 15s
  
  - id: assess_risk
    tool: AssessSQLRiskTool
    params:
      sql: "${steps.generate_sql.output.sql}"
      datasourceId: "${params.datasourceId}"
    timeout: 5s
  
  - id: check_risk_level
    condition: "${steps.assess_risk.output.level == 'HIGH'}"
    then:
      - action: return_error
        message: "SQL 风险过高，拒绝执行"
  
  - id: execute_sql
    tool: ExecuteWithRetryTool
    params:
      sql: "${steps.generate_sql.output.sql}"
      datasourceId: "${params.datasourceId}"
      userId: "${params.userId}"
      username: "${params.username}"
      maxRetries: 3
    timeout: 30s
  
  - id: auto_fix_on_failure
    condition: "${!steps.execute_sql.output.success}"
    then:
      - id: fix_sql
        tool: AutoFixSQLTool
        params:
          originalSql: "${steps.generate_sql.output.sql}"
          errorMessage: "${steps.execute_sql.output.error}"
          schema: "${steps.retrieve_schema.output}"
          datasourceId: "${params.datasourceId}"
      
      - id: retry_execution
        tool: ExecuteWithRetryTool
        params:
          sql: "${steps.fix_sql.output.fixedSql}"
          datasourceId: "${params.datasourceId}"
          userId: "${params.userId}"
          username: "${params.username}"
          maxRetries: 1
  
  - id: generate_chart
    condition: "${params.chartType != null}"
    tool: ChartGeneratorTool
    params:
      data: "${steps.execute_sql.output.data}"
      chartType: "${params.chartType}"
  
  - id: return_result
    action: return_success
    data:
      data: "${steps.execute_sql.output.data}"
      sql: "${steps.generate_sql.output.sql}"
      chart: "${steps.generate_chart.output}"
      risk: "${steps.assess_risk.output}"
```

### 7.3 WorkflowEngine 实现

```java
@Component
@Slf4j
public class WorkflowEngine {
    
    @Autowired
    private ApplicationContext applicationContext;
    
    @Autowired
    private Yaml yamlParser;
    
    /**
     * 执行 Workflow
     */
    public WorkflowResult execute(String workflowYaml, Map<String, Object> params) {
        // 1. 解析 YAML
        WorkflowDefinition workflow = parseWorkflow(workflowYaml);
        
        // 2. 初始化上下文
        WorkflowContext context = new WorkflowContext(params);
        
        // 3. 按顺序执行步骤
        for (StepDefinition step : workflow.getSteps()) {
            log.info("[WorkflowEngine] 执行步骤: {}", step.getId());
            
            // 检查条件
            if (step.getCondition() != null) {
                boolean conditionMet = evaluateCondition(step.getCondition(), context);
                if (!conditionMet) {
                    log.info("[WorkflowEngine] 条件不满足，跳过步骤: {}", step.getId());
                    continue;
                }
            }
            
            // 执行步骤
            StepResult result = executeStep(step, context);
            context.addStepResult(step.getId(), result);
            
            // 检查是否需要提前返回
            if (result.isReturn()) {
                return new WorkflowResult(result.isSuccess(), result.getData(), result.getError());
            }
        }
        
        // 4. 返回最终结果
        return context.getFinalResult();
    }
    
    private StepResult executeStep(StepDefinition step, WorkflowContext context) {
        if (step.getTool() != null) {
            // 调用 Tool
            return executeTool(step, context);
        } else if (step.getAction() != null) {
            // 执行动作（return_error / return_success）
            return executeAction(step, context);
        }
        
        throw new IllegalArgumentException("步骤必须指定 tool 或 action");
    }
    
    private StepResult executeTool(StepDefinition step, WorkflowContext context) {
        try {
            // 从 Spring 容器获取 Tool Bean
            Object toolBean = applicationContext.getBean(step.getTool());
            
            // 解析参数（替换变量占位符）
            Map<String, Object> resolvedParams = resolveParams(step.getParams(), context);
            
            // 调用 Tool 方法
            Method method = findExecuteMethod(toolBean.getClass());
            Object result = method.invoke(toolBean, resolvedParams.values().toArray());
            
            return StepResult.success(result);
            
        } catch (Exception e) {
            log.error("[WorkflowEngine] 步骤执行失败: {}", step.getId(), e);
            return StepResult.failure(e.getMessage());
        }
    }
}
```

### 7.4 实施步骤

1. **实现 WorkflowEngine 核心逻辑**（支持线性流程）
2. **实现条件分支**（if-then-else）
3. **实现变量替换**（`${steps.xxx.output}`）
4. **迁移 standard-query 到 YAML**
5. **保留 Groovy 作为 fallback**

### 7.5 验收标准

1. ✅ WorkflowEngine 能正确解析和执行 YAML
2. ✅ 支持条件分支和变量替换
3. ✅ standard-query 的 YAML 版本功能与 Groovy 版本一致
4. ✅ 性能对比：YAML 版本延迟增加 <10%

### 7.6 影响点分析

#### 问题根因
Groovy 脚本硬编码流程，缺乏灵活性和可读性。

#### 修复方案
- 实现 WorkflowEngine 解析 YAML
- 迁移 standard-query 到声明式配置

#### 可能影响点
- **功能影响**：✅ 正面，提升可配置性
- **性能影响**：⚠️ YAML 解析增加少量开销（<10ms）
- **兼容性影响**：❌ 无，对外接口不变
- **边界场景**：需处理 YAML 格式错误、循环依赖等

#### 建议测试用例
1. 正常流程：验证 YAML 执行结果与 Groovy 一致
2. 条件分支：验证 HIGH 风险 SQL 被拦截
3. 异常处理：验证 Tool 执行失败时的错误传播
4. 性能测试：对比 YAML 和 Groovy 版本的延迟

#### 风险分级
🟨 **中风险**：新增引擎组件，需充分测试

---

## 8. P3：动态 Skill 发现与组合

### 8.1 当前问题

```java
// SkillsMetadataLoader.java line 50
String[] skillDirs = {"standard-query", "report-with-insights"}; // 硬编码
```

**问题**：
- 新增 Skill 需修改两处：放 SKILL.md 文件 + 修改这个数组
- 不支持热加载

### 8.2 动态扫描方案

#### 方案设计

```java
@Component
@Slf4j
public class SkillsMetadataLoader {
    
    @Value("${skills.base-path:classpath:skills}")
    private String skillsBasePath;
    
    @PostConstruct
    public void loadSkills() {
        try {
            log.info("[SkillsMetadataLoader] 开始扫描 Skills...");
            
            // 扫描 classpath 下所有 **/SKILL.md 文件
            List<SkillMetadata> discoveredSkills = scanSkillDirectories();
            
            this.skills = Collections.unmodifiableList(discoveredSkills);
            log.info("[SkillsMetadataLoader] 成功加载 {} 个 Skills", skills.size());
            
        } catch (Exception e) {
            log.error("[SkillsMetadataLoader] 加载 Skills 失败", e);
            this.skills = Collections.emptyList();
        }
    }
    
    private List<SkillMetadata> scanSkillDirectories() {
        List<SkillMetadata> skills = new ArrayList<>();
        
        // 使用 Spring ResourcePatternResolver 扫描
        ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        
        try {
            // 扫描 classpath*:skills/**/SKILL.md
            Resource[] resources = resolver.getResources("classpath*:skills/**/SKILL.md");
            
            for (Resource resource : resources) {
                try {
                    SkillMetadata skill = parseSkillMarkdown(resource);
                    if (skill != null) {
                        skills.add(skill);
                        log.info("  ✓ 加载 Skill: {} ({})", skill.getName(), skill.getDisplayName());
                    }
                } catch (Exception e) {
                    log.error("  ✗ 加载 Skill 失败: {}", resource.getFilename(), e);
                }
            }
            
        } catch (IOException e) {
            log.error("[SkillsMetadataLoader] 扫描 Skills 目录失败", e);
        }
        
        return skills;
    }
}
```

### 8.3 SkillContext.callSkill() 设计

#### 接口定义

```java
@Component
public class SkillContext {
    
    private final Map<String, SkillExecutor> skillRegistry;
    private final Map<String, Object> globalContext;
    
    @Autowired
    public SkillContext(List<SkillExecutor> skills) {
        this.skillRegistry = skills.stream()
            .collect(Collectors.toMap(
                s -> s.getMetadata().getName(),
                s -> s
            ));
        this.globalContext = new ConcurrentHashMap<>();
    }
    
    /**
     * 调用另一个 Skill
     */
    public SkillResult callSkill(String skillName, Map<String, Object> params) {
        SkillExecutor skill = skillRegistry.get(skillName);
        if (skill == null) {
            throw new SkillNotFoundException(
                String.format("Skill '%s' not found. Available skills: %s", 
                    skillName, skillRegistry.keySet()));
        }
        
        log.debug("Calling skill: {} with params: {}", skillName, params.keySet());
        
        // 注入全局上下文
        Map<String, Object> enrichedParams = new HashMap<>(params);
        enrichedParams.putAll(globalContext);
        
        try {
            SkillResult result = skill.execute(enrichedParams);
            log.debug("Skill {} executed successfully", skillName);
            return result;
        } catch (Exception e) {
            log.error("Skill {} execution failed", skillName, e);
            return SkillResult.error("SKILL_EXECUTION_FAILED", e.getMessage());
        }
    }
    
    /**
     * 设置全局上下文
     */
    public void setGlobalContext(Map<String, Object> context) {
        this.globalContext.putAll(context);
    }
}
```

#### 在 Groovy Skill 中使用

```groovy
// report-with-insights/ReportWithInsightsSkill.groovy
class ReportWithInsightsSkill implements SkillExecutor {
    
    @Autowired
    SkillContext skillContext
    
    SkillResult execute(Map params) {
        // 1. 调用 standard-query 获取基础数据
        def queryResult = skillContext.callSkill("execute_standard_query", [
            question: params.question,
            datasourceId: params.datasourceId,
            userId: params.userId,
            username: params.username
        ])
        
        if (!queryResult.success) {
            return SkillResult.error("BASE_QUERY_FAILED", queryResult.errorMessage)
        }
        
        // 2. 生成洞察分析
        def insights = generateInsights(queryResult.data)
        
        // 3. 组装报告
        return SkillResult.success([
            data: queryResult.data,
            insights: insights,
            chart: queryResult.chart
        ])
    }
}
```

### 8.4 实施步骤

1. **修改 SkillsMetadataLoader**：从硬编码改为自动扫描
2. **实现 SkillContext**：支持 Skill 间调用
3. **重构 report-with-insights**：使用 `skillContext.callSkill()`
4. **移除硬编码依赖**：不再直接引用 Bean

### 8.5 验收标准

1. ✅ 启动时自动扫描所有 SKILL.md 文件
2. ✅ 新增 SKILL.md 后重启应用能自动识别
3. ✅ report-with-insights 能通过 `callSkill()` 调用 standard-query
4. ✅ 传递的参数完整（包括全局上下文）

### 8.6 影响点分析

#### 问题根因
硬编码目录扫描，新增 Skill 需修改代码。

#### 修复方案
- 自动扫描 classpath 下的 SKILL.md
- 实现 SkillContext 支持 Skill 间调用

#### 可能影响点
- **功能影响**：✅ 正面，提升可扩展性
- **性能影响**：⚠️ 启动时扫描增加 ~100ms
- **兼容性影响**：❌ 无
- **边界场景**：需处理 SKILL.md 格式错误、循环调用

#### 建议测试用例
1. 启动验证：确认加载了所有 Skill
2. 新增 Skill：放置新的 SKILL.md，重启验证
3. Skill 间调用：验证 report-with-insights 能正确调用 standard-query
4. 异常处理：验证 SKILL.md 格式错误时的容错

#### 风险分级
🟨 **中风险**：涉及启动逻辑和 Skill 间调用机制

---

## 9. P4：LLM 价值增强（可选）

### 9.1 优化方向

在保持确定性管道的前提下，让 LLM 做更多有价值的事。

### 9.2 具体优化

#### 优化 1：用 LLM 替代正则意图分类

**当前**：`IntentClassifier` 使用正则匹配，准确率有限  
**优化**：改用轻量级 LLM 调用进行意图分类

```java
@Component
public class LLMIntentClassifier {
    
    @Autowired
    private LLMService llmService;
    
    public IntentClassificationResult classify(String question) {
        String prompt = String.format(
            "请分析以下用户问题的意图，返回 JSON 格式：\n" +
            "{\"intent\": \"QUERY/REPORT/CLARIFY/UNKNOWN\", \"confidence\": 0.0-1.0}\n\n" +
            "用户问题：%s",
            question
        );
        
        Map<String, Object> response = llmService.generate(
            List.of(Map.of("role", "user", "content", prompt)),
            0.1 // 低温度
        );
        
        return parseClassificationResult(response);
    }
}
```

**收益**：
- ✅ 提高意图识别准确率（尤其是复杂问题）
- ✅ 支持更多意图类型（如 CLARIFY）

**成本**：
- ⚠️ 每次查询增加一次 LLM 调用（~500ms）
- 💡 可增加缓存机制，相同问题不重复调用

---

#### 优化 2：增加 clarify_question Skill

**场景**：用户问题模糊时主动反问

```groovy
// clarify-question/SKILL.md
---
name: clarify_question
displayName: 问题澄清
description: 当用户问题模糊时，主动反问以获取更多信息
priority: 1
---

## 适用场景
- 用户未指定时间范围（如"看看销售情况"）
- 用户未指定维度（如"分析数据"）
- 用户问题过于宽泛

## 示例
用户：看看销售情况
Agent：请问您想查看哪个时间段的销售数据？（最近7天/本月/本季度/自定义）
```

**实现**：
```java
@Component
public class ClarifyQuestionSkill {
    
    @Autowired
    private LLMService llmService;
    
    public ClarificationResult clarify(String question) {
        // 检测问题是否模糊
        if (!isAmbiguous(question)) {
            return ClarificationResult.notNeeded();
        }
        
        // 生成澄清问题
        String clarificationPrompt = String.format(
            "用户问题'%s'过于模糊，请生成一个澄清问题，帮助用户明确需求。" +
            "只返回澄清问题，不要其他内容。",
            question
        );
        
        String clarificationQuestion = llmService.generateText(clarificationPrompt);
        
        return ClarificationResult.needed(clarificationQuestion);
    }
}
```

---

#### 优化 3：SQL 生成后增加轻量级自检

**场景**：生成 SQL 后快速验证是否正确反映用户意图

```java
@Component
public class SQLSelfCheckTool {
    
    @Autowired
    private LLMService llmService;
    
    public CheckResult selfCheck(String question, String sql, String schema) {
        String prompt = String.format(
            "请检查以下 SQL 是否正确反映了用户意图：\n" +
            "用户问题：%s\n" +
            "生成的 SQL：%s\n" +
            "表结构：%s\n\n" +
            "请回答：正确 / 需要修正（说明原因）",
            question, sql, schema
        );
        
        String response = llmService.generateText(prompt);
        
        if (response.contains("需要修正")) {
            return CheckResult.needFix(extractReason(response));
        }
        
        return CheckResult.correct();
    }
}
```

**收益**：
- ✅ 提前发现 SQL 生成错误
- ✅ 减少执行失败率

**成本**：
- ⚠️ 每次 SQL 生成增加一次 LLM 调用（~300ms）

### 9.3 实施建议

**优先级**：⭐⭐（可选，根据实际需求决定）

**实施顺序**：
1. 先实现 `clarify_question` Skill（收益最高）
2. 再考虑 LLM 意图分类（需对比测试准确率）
3. 最后考虑 SQL 自检（需权衡延迟增加）

---

## 10. 实施计划与里程碑

### 10.1 总体时间表

| 阶段 | 工作内容 | 预计工期 | 开始日期 | 结束日期 |
|------|---------|---------|---------|---------|
| P0 | 架构正名与文档更新 | 1天 | 2026-05-06 | 2026-05-06 |
| P1 | Groovy 脚本拆分与原子化 | 3-5天 | 2026-05-07 | 2026-05-11 |
| P2 | Workflow 引擎实现 | 2-3天 | 2026-05-12 | 2026-05-14 |
| P3 | 动态 Skill 发现与组合 | 2天 | 2026-05-15 | 2026-05-16 |
| P4 | LLM 价值增强（可选） | 1-2天 | TBD | TBD |

**总计**：8-11 个工作日（不含 P4）

### 10.2 里程碑

| 里程碑 | 验收标准 | 预计完成日期 |
|--------|---------|------------|
| M1：架构正名完成 | 所有类重命名，编译通过 | 2026-05-06 |
| M2：原子 Tool 拆分完成 | StandardQuerySkill 简化至 ~200 行 | 2026-05-11 |
| M3：Workflow Engine MVP | 支持线性流程，standard-query 迁移完成 | 2026-05-14 |
| M4：动态 Skill 发现上线 | 自动扫描 SKILL.md，SkillContext 可用 | 2026-05-16 |

---

## 11. 风险评估与应对

### 11.1 高风险操作

| 风险点 | 影响 | 概率 | 应对措施 |
|-------|------|------|---------|
| Groovy 脚本拆分遗漏异常场景 | 查询失败率上升 | 中 | 1. 梳理所有异常场景清单<br>2. 每个 Tool 编写单元测试<br>3. 灰度发布，监控错误率 |
| Workflow Engine 解析失败 | 启动失败或运行时错误 | 低 | 1. 保留 Groovy 作为 fallback<br>2. YAML 格式校验<br>3. 详细错误日志 |

### 11.2 中风险操作

| 风险点 | 影响 | 概率 | 应对措施 |
|-------|------|------|---------|
| LLM 意图分类准确率下降 | 路由错误增加 | 中 | 1. A/B 测试对比准确率<br>2. 保留正则作为 fallback<br>3. 增加缓存机制 |
| Skill 循环调用 | 栈溢出 | 低 | 1. 在 callSkill() 中增加调用链检测<br>2. 最大深度限制为 5 |

### 11.3 低风险操作

| 风险点 | 影响 | 概率 | 应对措施 |
|-------|------|------|---------|
| 类重命名遗漏引用 | 编译失败 | 低 | 1. 使用 IDE 重构功能<br>2. 全量编译验证 |
| System Prompt 优化不当 | LLM 行为异常 | 低 | 1. 回归测试常见查询场景<br>2. 监控 LLM 输出质量 |

---

## 12. 验收标准

### 12.1 功能验收

| 验收项 | 标准 | 验证方法 |
|--------|------|---------|
| 架构正名 | 所有日志、注释、类名使用 "Pipeline" 术语 | 代码审查 |
| 标准查询功能 | 与改造前功能一致 | 回归测试用例 |
| 报告生成功能 | report-with-insights 正常工作 | 手动测试 |
| 异常处理 | 所有异常场景正确捕获并返回友好错误 | 异常测试用例 |

### 12.2 性能验收

| 指标 | 改造前 | 改造后目标 | 验证方法 |
|------|--------|-----------|---------|
| 平均查询延迟 | ~2s | ≤2.2s（增加 ≤10%） | 压力测试 |
| P95 延迟 | ~3s | ≤3.3s | 压力测试 |
| 启动时间 | ~10s | ≤11s | 启动日志 |

### 12.3 代码质量验收

| 指标 | 标准 | 验证方法 |
|------|------|---------|
| StandardQuerySkill.groovy 行数 | ≤250 行 | 代码统计 |
| 单元测试覆盖率 | ≥80% | Jacoco 报告 |
| 编译警告 | 0 | Maven 编译输出 |

### 12.4 文档验收

| 文档 | 内容要求 | 验证方法 |
|------|---------|---------|
| SKILL.md | 包含 Workflow 状态、调用链路 | 人工审查 |
| System Prompt | 明确说明 Pipeline 模式 | 人工审查 |
| 架构设计文档 | 本文档 | 已完成 ✅ |

---

## 附录 A：相关文件清单

### 需要修改的文件

| 文件路径 | 修改内容 | 优先级 |
|---------|---------|--------|
| `nl2sql-core/src/main/java/com/nl2sql/core/agent/ReActAgent.java` | 重命名为 NL2SQLPipelineOrchestrator | P0 |
| `nl2sql-core/src/main/java/com/nl2sql/core/agent/AgentConfig.java` | 更新 Bean 名称和注释 | P0 |
| `nl2sql-web/src/main/resources/skills/standard-query/SKILL.md` | 补充 Workflow 状态和调用链路 | P0 |
| `nl2sql-core/src/main/java/com/nl2sql/core/agent/skills/SkillsMetadataLoader.java` | 改为动态扫描 | P3 |
| `nl2sql-web/src/main/resources/skills/standard-query/StandardQuerySkill.groovy` | 简化为编排层 | P1 |

### 需要新增的文件

| 文件路径 | 内容 | 优先级 |
|---------|------|--------|
| `nl2sql-core/src/main/java/com/nl2sql/core/agent/tools/ExecuteWithRetryTool.java` | 执行重试工具 | P1 |
| `nl2sql-core/src/main/java/com/nl2sql/core/agent/tools/AutoFixSQLTool.java` | 自动修正工具 | P1 |
| `nl2sql-core/src/main/java/com/nl2sql/core/agent/tools/ChartGeneratorTool.java` | 图表生成工具 | P1 |
| `nl2sql-core/src/main/java/com/nl2sql/core/agent/workflow/WorkflowEngine.java` | Workflow 引擎 | P2 |
| `nl2sql-core/src/main/java/com/nl2sql/core/agent/skills/SkillContext.java` | Skill 上下文 | P3 |
| `nl2sql-web/src/main/resources/skills/standard-query/workflow.yaml` | 声明式流程配置 | P2 |

---

## 附录 B：术语对照表

| 旧术语 | 新术语 | 说明 |
|--------|--------|------|
| ReAct Agent | Pipeline Orchestrator | 顶层编排器 |
| ReAct 循环 | Pipeline 流程 | 确定性执行路径 |
| Tool | Atomic Tool | 原子能力 |
| Skill | High-level Skill | 高级能力封装 |
| Groovy Script | Skill Implementation | Skill 的具体实现 |

---

**文档结束**
