# NL2SQL 查询全流程文档

## 一、总览架构

```
用户请求 → AgentChatService → SupervisorAgent → SkillRouter(路由) → 三种执行模式
                                                       ↓
                                              ┌────────┼────────┐
                                              ↓        ↓        ↓
                                           DIRECT   PLAN_AND_EXECUTE   REACT
                                         (SKILL.md   (PlanExecutor)   (DataExploration
                                          Workflow)                    Agent)
```

---

## 二、入口层：AgentChatService.processChat()

`nl2sql-web/.../service/AgentChatService.java` 是整个查询的入口，处理流程如下：

```
1. 设置 UserContext (ThreadLocal)
2. fixEncoding() — 修复中文编码问题
3. buildFullMessage() — 拼接 Context（含上次SQL、对话历史等）
4. 检测清除命令 → 返回清除确认
5. detectAndExecuteFollowUp() — 追问识别（详见分支A）
6. executeAgent() → 调用 SupervisorAgent.execute()
7. processResponse() — 统一响应处理
8. applyDefaultRating() — SQL查询成功时应用默认评分
9. enrichResponse() — 添加元数据（selectedTables、监控数据等）
10. publishMonitoringEvent() — 异步发布监控事件
11. saveConversationHistory() — 保存对话历史
```

### 分支A：追问识别（detectAndExecuteFollowUp）

```
前端标识 hasData=true && contextId!=null ?
  ├─ No → 走正常 Agent 流程
  └─ Yes → detectFollowUpIntent() 二次验证
       ├─ L1: 显式信号检测
       │   ├─ 新话题信号（统计/查询/查看...） → null（走正常流程）
       │   ├─ 时间范围信号（最近/上周...） → null
       │   ├─ 全局信号（每个/所有...） → null
       │   ├─ 总结类信号 → "summarize_result"
       │   ├─ 图表类信号 → "generate_chart"
       │   ├─ 下载类信号 → "download_excel"
       │   └─ 指代模式（按/按照/根据...） → 继续L2
       └─ L2: 滑动窗口衰减 + 实体重叠度
            ├─ 追问确认 → executeFollowUpTool()
            │   ├─ "summarize_result" → executeSummarizeTool()
            │   ├─ "generate_chart" → executeChartTool()
            │   └─ "download_excel" → 返回下载标识
            └─ 非追问 → null（走正常流程）
```

---

## 三、路由层：SupervisorAgent.execute()

`nl2sql-core/.../agent/SupervisorAgent.java` 是核心调度器：

```
SupervisorAgent.execute(userMessage, datasourceId, history)
  │
  ├─ datasourceId == null ?
  │   └─ Yes → DatasourceClarificationTool.clarifyDatasource()
  │       ├─ autoExecuted=true → 递归调用 execute(msg, recommendedDsId, history)
  │       └─ autoExecuted=false → 返回澄清结果
  │
  └─ datasourceId != null → SkillRouter.route()
      │
      ├─ Strategy=DIRECT → executeDirect()（详见模式一）
      ├─ Strategy=PLAN_AND_EXECUTE → executeWithPlan()（详见模式二）
      └─ Strategy=REACT → executeWithReAct()（详见模式三）
```

---

## 四、意图路由层：SkillRouter + IntentClassifier

`nl2sql-core/.../routing/SkillRouter.java` + `nl2sql-core/.../intent/IntentClassifier.java`

### 意图分类（IntentClassifier.classify）

```
1. 检测显式标记 [INTENT:xxx]
   ├─ [INTENT:AI_SUMMARY/SUMMARY] → SUMMARY (confidence=1.0)
   ├─ [INTENT:GENERATE_CHART/CHART] → CHART (confidence=1.0)
   ├─ [INTENT:CLARIFY/DATASOURCE] → CLARIFY (confidence=1.0)
   ├─ [INTENT:COMPLEX] → COMPLEX (confidence=1.0)
   ├─ [INTENT:EXPLORE] → EXPLORE (confidence=1.0)
   └─ [INTENT:QUERY] → QUERY (confidence=1.0)

2. 规则匹配（正则）
   ├─ EXPLORE_PATTERN 命中 → EXPLORE (confidence=0.9)
   ├─ COMPLEX_PATTERN 命中 → COMPLEX (confidence=0.85)
   ├─ SUMMARY_PATTERN 命中 → SUMMARY (confidence=0.85)
   ├─ CHART_PATTERN 命中 → CHART (confidence=0.85)
   ├─ CLARIFY_PATTERN 命中 → CLARIFY (confidence=0.85)
   └─ 命中但 confidence < 0.8 → 继续步骤3

3. 默认 → QUERY (confidence=0.6)
```

### 路由策略决策（decideRoutingStrategy）

```
IntentType + Confidence → RoutingStrategy

Rule 1: EXPLORE → REACT（无论置信度）
Rule 2: confidence ≥ 0.8 且 非 UNKNOWN/COMPLEX → DIRECT
        ├─ QUERY → "execute_standard_query"
        ├─ SUMMARY → "summarize_result"
        ├─ CHART → "generate_chart"
        └─ CLARIFY → "clarify_datasource"
Rule 3: COMPLEX 或 confidence ∈ [0.5, 0.8) → PLAN_AND_EXECUTE
Rule 4: confidence < 0.5 → PLAN_AND_EXECUTE（fallback）
```

---

## 五、模式一：DIRECT（SKILL.md Workflow）

`nl2sql-web/.../resources/skills/standard-query/SKILL.md` + `nl2sql-core/.../engine/WorkflowEngine.java`

这是最核心、最完整的流程，由 SKILL.md 中定义的 30+ 步骤编排：

```
executeDirect(skillName, datasourceId, userId, username, userMessage)
  │
  ├─ skillName == "clarify_datasource" → DatasourceClarificationTool
  ├─ skillName == "summarize_result" → 从缓存取数据 → summarize_result Tool
  ├─ skillName == "generate_chart" → 从缓存取数据 → detectAndGenerateChart Tool
  └─ skillName == "execute_standard_query" → WorkflowEngine.executeFromSkillWorkflow()
      │
      ├─ Workflow 加载成功 → 执行 SKILL.md Workflow（详见下方完整分支）
      └─ Workflow 加载失败 → 降级到 SQL Worker
```

### SKILL.md Workflow 完整分支图

```
[validate_params] → validateParams
  │
  ├─ validated=false → [respond_clarification] → 返回 {type: clarification}
  │
  └─ validated=true → [detect_chart_intent] → detectChartIntent
      │
      ├─ chartDetected=true → cleanedQuestion + chartType
      └─ chartDetected=false → cleanedQuestion
      │
      ↓
  [extract_table_preference] → extractTablePreference
      │
      ↓
  [inject_industry_concept] → injectIndustryConcept
      │
      ↓
  [retrieve_schema] → retrieveSchema
      │
      ↓
  [generate_sql] → generateSQL
      │
      ├─ success=false → [respond_error] → 返回 {type: error}
      │
      └─ success=true → [init_final_sql] → final_sql = sql_result.data.sql
          │
          ↓
      [quick_risk_check] → quickRiskCheck
          │
          ├─ isSimple=true ──────────────────────────────────┐
          │                                                   │
          └─ isSimple=false → [analyze_query_plan]           │
              │                                               │
              ├─ riskLevel=HIGH → [handle_high_risk]         │
              │   → regenerateSQLWithLLM                     │
              │       │                                       │
              │       ├─ regenerationSuccess=true             │
              │       │   → re_analyze_query_plan             │
              │       │       │                               │
              │       │       ├─ 仍HIGH → [require_human_approval]
              │       │       │   → 返回 {type: human_approval_required}
              │       │       │                               │
              │       │       └─ 非HIGH → [use_optimized_sql] │
              │       │           → final_sql = optimizedSql │
              │       │           → 检查sqlOnly               │
              │       │               ├─ true → [respond_sql_only_optimized]
              │       │               └─ false → [execute_sql] ──┐
              │       │                                            │
              │       └─ regenerationSuccess=false                 │
              │           → [require_human_approval]              │
              │               → 返回 {type: human_approval_required}
              │
              ├─ riskLevel=MEDIUM → [get_llm_optimization_suggestion]
              │   → 仅获取建议，不修改SQL
              │
              └─ riskLevel=LOW → 继续执行
                                                            │
          ←─────────────────────────────────────────────────┘
          │
          ↓
      [check_sql_only]
          │
          ├─ sqlOnly=true → [respond_sql_only]
          │   → 返回 {type: sql_only, sql, riskLevel, optimizationSuggestion}
          │
          └─ sqlOnly=false → [execute_sql] → executeRawSQL
              │
              ├─ exec_success=true → [rag_learn] → ragLearnFromExecution
              │   │
              │   ↓
              │   [generate_follow_up] → generateFollowUpSuggestions
              │   │
              │   ↓
              │   [check_chart_needed]
              │   ├─ chartDetected=true → [generate_chart_config] → generateChartConfig
              │   └─ chartDetected=false → 跳过
              │   │
              │   ↓
              │   [assemble_result] → post_process_response
              │   │
              │   ↓
              │   [respond_success] → 返回 {type: data, data, sql, rowCount, chartConfig, ...}
              │
              └─ exec_success=false → [attempt_auto_fix] → autoFixSQL
                  │
                  ├─ fixedSql 非空 → [retry_execute] → executeRawSQL(fixedSql)
                  │   │
                  │   ├─ retry_success=true → [rag_learn_retry] → ragLearnFromExecution
                  │   │   │
                  │   │   ↓
                  │   │   [generate_follow_up_retry]
                  │   │   │
                  │   │   ↓
                  │   │   [check_chart_needed_retry]
                  │   │   │
                  │   │   ↓
                  │   │   [assemble_result_retry]
                  │   │   │
                  │   │   ↓
                  │   │   [respond_success_retry]
                  │   │   → 返回 {type: data, autoFixed: true, originalSql, ...}
                  │   │
                  │   └─ retry_success=false → [respond_error]
                  │       → 返回 {type: error, error: exec_result.data.error}
                  │
                  └─ fixedSql 为空 → [respond_error]
                      → 返回 {type: error}
```

---

## 六、模式二：PLAN_AND_EXECUTE（PlanExecutor）

`nl2sql-core/.../planner/PlannerAgent.java` + `nl2sql-core/.../agent/PlanExecutor.java`

```
executeWithPlan(userMessage, datasourceId, userId, username, routing)
  │
  ↓
PlannerAgent.plan() → LLM 生成 QueryPlan
  │
  ├─ plan 生成失败 → 降级到 DIRECT(execute_standard_query)
  │
  ├─ complexity=SIMPLE → 降级到 DIRECT(execute_standard_query)
  │
  └─ complexity=MODERATE/COMPLEX → PlanExecutor.execute()
      │
      ↓
  1. validateParams → 校验参数
  2. detectChartIntent → 检测图表意图
     └─ plan.chart.needed=true 但 chartDetected=false → 覆盖为 chartDetected=true
  3. extractTablePreference → 提取表名偏好
     └─ plan.tables 非空但 tableHint=null → 使用 plan.tables[0].tableName
  4. injectIndustryConcept → 注入行业概念
  5. retrieveSchema → 检索表结构
  6. 循环执行 sqlSteps（支持多步SQL）:
     │
     ├─ generateSQL → 生成SQL
     │   ├─ 失败 → 第一步失败则返回错误，后续步骤跳过
     │   └─ 成功 → 继续
     │
     ├─ quickRiskCheck → 快速风险检查
     │   ├─ isSimple=true → 跳过深度分析
     │   └─ isSimple=false → analyzeQueryPlan
     │       ├─ riskLevel=HIGH → regenerateSQLWithLLM
     │       │   ├─ regenerationSuccess=true → re_analyze
     │       │   │   ├─ 仍HIGH → 使用原SQL
     │       │   │   └─ 非HIGH → 使用优化SQL
     │       │   └─ regenerationSuccess=false → 使用原SQL
     │       └─ riskLevel=MEDIUM → getLLMOptimizationSuggestion（仅建议）
     │
     ├─ executeRawSQL → 执行SQL
     │   ├─ 失败 → autoFixSQL → retry_executeRawSQL
     │   │   ├─ 重试成功 → autoFixed=true
     │   │   └─ 重试失败 → 继续下一步
     │   └─ 成功 → ragLearnFromExecution
     │
     └─ 记录当前步骤结果
  │
  7. 所有步骤完成后:
     ├─ generateFollowUpSuggestions → 追问建议
     ├─ chartDetected=true → generateChartConfig
     ├─ plan.needSummary=true → summarize_result
     └─ 组装最终响应 {success, type, data, sql, chartConfig, aiSummary, ...}
```

---

## 七、模式三：REACT（DataExplorationAgent）

`nl2sql-core/.../agent/DataExplorationAgent.java`

```
executeWithReAct(userMessage, datasourceId)
  │
  ↓
DataExplorationAgent.explore() — ReAct 循环（最多5轮）
  │
  ↓
初始化: system prompt + user message (含 datasourceId)
  │
  ↓
循环 iteration 1..5:
  │
  ├─ llmService.generateWithTools() → LLM 返回
  │   │
  │   ├─ 无 tool_calls → 返回 content（最终答案）
  │   │
  │   └─ 有 tool_calls → 执行第一个 tool
  │       │
  │       ├─ 可用 Tools:
  │       │   retrieveSchema, generateSQL, quickRiskCheck,
  │       │   executeRawSQL, autoFixSQL, detectChartIntent,
  │       │   generateChartConfig, generateAISummary, assembleResult
  │       │
  │       ├─ 执行结果 → observation
  │       │   ├─ isFinalResult=true → 直接返回
  │       │   └─ isFinalResult=false → 加入 messages，继续循环
  │       │
  │       └─ executeRawSQL 成功 → successfulQueries++
  │
  ├─ successfulQueries >= 3 → 强制总结
  │
  └─ 达到 MAX_ITERATIONS=5 → 返回 lastObservation
```

---

## 八、三种模式对比

| 维度 | DIRECT (SKILL.md) | PLAN_AND_EXECUTE | REACT |
|------|-------------------|------------------|-------|
| **适用场景** | 明确查询、总结、图表、澄清 | 复杂多步查询、中置信度 | 开放式探索、EXPLORE意图 |
| **步骤编排** | 确定性YAML编排（30+步骤） | LLM生成Plan→动态执行 | LLM每轮决定下一步 |
| **LLM调用次数** | 1次（SQL生成）+ 可选1次（风险优化） | 2次（Plan+SQL）+ 可选 | 3-5次（每轮1次） |
| **风险处理** | 三层评估+自动优化+人机审批 | 三层评估+自动优化 | 简单风险检查 |
| **自动修正** | ✅ autoFixSQL + 重试 | ✅ autoFixSQL + 重试 | ✅ autoFixSQL + 重试 |
| **RAG学习** | ✅ 独立步骤 | ✅ 独立步骤 | ❌ 无 |
| **图表/总结** | ✅ 后置生成 | ✅ 后置生成 | ✅ LLM自主决定 |
| **多步SQL** | ❌ 单步 | ✅ 循环sqlSteps | ✅ LLM自主决定 |
| **人机审批** | ✅ HIGH风险时 | ❌ 无 | ❌ 无 |
| **sqlOnly模式** | ✅ 仅返回SQL | ❌ 无 | ❌ 无 |

---

## 九、关键降级链路

```
PLAN_AND_EXECUTE:
  PlannerAgent.plan() 失败 → 降级到 DIRECT
  Plan complexity=SIMPLE → 降级到 DIRECT

DIRECT:
  WorkflowEngine 加载 SKILL.md 失败 → 降级到 SQL Worker
  WorkflowEngine 执行异常 → 降级到 SQL Worker

REACT:
  LLM 无 tool_calls → 直接返回文本
  达到 MAX_ITERATIONS → 返回 lastObservation
  Tool 调用失败 → 记录错误，继续循环

全局:
  datasourceId=null → DatasourceClarificationTool
  连续失败 → 清除数据源缓存
```

---

## 十、响应类型汇总

| type | 含义 | 触发条件 |
|------|------|----------|
| `data` | 查询成功，返回数据 | SQL执行成功 |
| `sql_only` | 仅返回SQL不执行 | sqlOnly=true |
| `error` | 查询失败 | SQL生成/执行失败且自动修正也失败 |
| `clarification` | 需要用户澄清 | 参数校验不通过 |
| `human_approval_required` | 需要人工审批 | HIGH风险SQL且自动优化后仍HIGH |
