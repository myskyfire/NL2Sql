# Skills 架构设计文档

## 1. 什么是 Skills？

**Skills（技能）** 是封装了完整业务流程的高级抽象单元，由多个原子 **Tools（工具）** 组合编排而成。

### Skills vs Tools 对比

| 维度 | Tools（工具） | Skills（技能） |
|------|--------------|---------------|
| **粒度** | 原子操作 | 业务流程 |
| **示例** | `retrieveSchema`, `generateSQL`, `executeRawSQL` | `execute_standard_query`（SKILL.md 编排） |
| **职责** | 单一功能 | 端到端解决方案 |
| **复用性** | 被多种 Skills 复用 | 可被 SupervisorAgent 或 PlanExecutor 直接调度 |
| **复杂度** | 低 | 高（包含校验、风险评估、重试、RAG学习等） |

---

## 2. 架构概览

当前架构采用 **SupervisorAgent 统一路由 + 三种执行模式** 的 Plan-and-Execute 多智能体架构：

```
用户请求 → AgentChatService → SupervisorAgent
                                    │
                            SkillRouter 路由决策
                                    │
                   ┌────────────────┼────────────────┐
                   ↓                ↓                ↓
             DIRECT           PLAN_AND_EXECUTE     REACT
         (SKILL.md Workflow)   (PlanExecutor)   (DataExplorationAgent)
                   │                │                │
                   ↓                ↓                ↓
              WorkflowEngine    PlannerAgent +     LLM ReAct 循环
              编排30+原子Tool   PlanExecutor       自主决定Tool
                                  ↓
                            PlanValidator(事前校验)
                            + StepReflector(事中校验)
```

### 核心组件

| 组件 | 位置 | 职责 |
|------|------|------|
| **SupervisorAgent** | `core/agent/SupervisorAgent.java` | 统一入口，路由决策 + 降级管理 |
| **SkillRouter** | `core/routing/SkillRouter.java` | 意图分类 + 路由策略决策 |
| **IntentClassifier** | `core/intent/IntentClassifier.java` | 分类查询/总结/图表/探索/复杂等意图 |
| **WorkflowEngine** | `core/agent/engine/WorkflowEngine.java` | 加载并执行 SKILL.md YAML 工作流 |
| **PlannerAgent** | `core/agent/planner/PlannerAgent.java` | LLM 生成 QueryPlan（步骤规划） |
| **PlanExecutor** | `core/agent/PlanExecutor.java` | 执行 QueryPlan，集成校验和反射 |
| **PlanValidator** | `core/agent/planner/PlanValidator.java` | 事前校验 Plan 的表名、步骤正确性 |
| **StepReflector** | `core/agent/planner/StepReflector.java` | 事中校验每一步执行结果，决策继续/重规划/终止 |
| **ToolRegistry** | `core/agent/tools/ToolRegistry.java` | 管理所有原子 Tool 的注册和发现 |
| **DataExplorationAgent** | `core/agent/DataExplorationAgent.java` | ReAct 模式，开放式数据探索 |

---

## 3. 三种执行模式详解

### 3.1 DIRECT — SKILL.md Workflow（标准查询）

**文件位置**: `nl2sql-web/src/main/resources/skills/standard-query/SKILL.md`

这是最核心的查询模式，由 SKILL.md YAML 编排 30+ 个原子 Tool 步骤。**Skill = SKILL.md 定义文件 + WorkflowEngine 执行引擎**，不再有 Java Skill 类。

```
validate_params
    → detect_chart_intent
    → extract_table_preference
    → inject_industry_concept
    → retrieve_schema
    → generate_sql
        ├─ 失败 → respond_error
        └─ 成功 → quick_risk_check
            ├─ isSimple → 直接执行
            └─ isSimple=false → analyze_query_plan
                ├─ HIGH → regenerateSQLWithLLM → re_analyze
                │   ├─ 仍HIGH → human_approval_required
                │   └─ 非HIGH → 使用优化SQL
                ├─ MEDIUM → get_llm_optimization_suggestion（仅建议不修改）
                └─ LOW → 继续执行
    → execute_sql
        ├─ 成功 → rag_learn → generate_follow_up → check_chart_needed → assemble_result
        └─ 失败 → auto_fix_sql → retry_execute
            ├─ 重试成功 → rag_learn → assemble_result
            └─ 重试失败 → respond_error
```

**关键特性**:
- ✅ 确定性编排，步骤顺序完全可预期
- ✅ 三层风险评估（quickRiskCheck → analyzeQueryPlan → LLM优化）
- ✅ HIGH 风险人机审批（human_approval_required）
- ✅ RAG 自动学习（从执行结果学习，优化未来查询）
- ✅ autoFixSQL 自动修正 + 重试
- ✅ sqlOnly 模式（仅返回 SQL 不执行）
- ✅ 图表/总结后置生成

**其他 SKILL.md 技能**:
| Skill | 文件 | 用途 |
|-------|------|------|
| `execute_standard_query` | `skills/standard-query/SKILL.md` | 标准数据查询（最完整） |
| `summarize_result` | `skills/summarize-result/SKILL.md` | AI 总结查询结果 |
| `generate_chart` | `skills/report-with-insights/SKILL.md` | 生成图表配置 |
| `sql-validate-execute` | `skills/sql-validate-execute/SKILL.md` | SQL 验证与安全执行 |
| `data-exploration` | `skills/data-exploration/SKILL.md` | 数据探索（ReAct 模式） |

---

### 3.2 PLAN_AND_EXECUTE — PlanExecutor（规划执行）

**核心文件**: `PlannerAgent.java` + `PlanExecutor.java` + `PlanValidator.java` + `StepReflector.java`

适用于中等复杂度查询，由 LLM 生成 QueryPlan 后动态执行：

```
PlannerAgent.plan() → LLM 生成 QueryPlan（JSON格式）
    ├─ 失败 → 降级到 DIRECT
    └─ 成功 → PlanValidator.validate() 事前校验
        ├─ 校验不通过 → 降级到 DIRECT
        └─ 校验通过 → PlanExecutor.execute()
            ├─ 前置处理：detectChartIntent / extractTablePreference / retrieveSchema
            ├─ 循环执行 sqlSteps（支持多步SQL）:
            │   ├─ generateSQL / quickRiskCheck / analyzeQueryPlan
            │   ├─ executeRawSQL
            │   │   ├─ 失败 → autoFixSQL → retry
            │   │   └─ 成功 → ragLearnFromExecution
            │   └─ StepReflector.reflect() 事中校验
            │       ├─ CONTINUE → 继续下一步
            │       ├─ REPLAN → 重新规划
            │       └─ TERMINATE → 终止执行
            └─ 后置处理：followUp / chartConfig / summary / 组装响应
```

**关键特性**:
- ✅ LLM 动态规划步骤（支持多步 SQL）
- ✅ PlanValidator 事前校验（表名、步骤、复杂度）
- ✅ StepReflector 事中校验（每步执行后决策）
- ✅ 自动降级（失败→DIRECT，SIMPLE→DIRECT）

---

### 3.3 REACT — DataExplorationAgent（数据探索）

**核心文件**: `DataExplorationAgent.java`

适用于开放式数据探索场景（EXPLORE 意图），LLM 自主决定每步调用什么工具：

```
循环 iteration 1..5:
  ├─ LLM 返回 tool_calls → 执行第一个 tool
  │   ├─ 可用 tools: retrieveSchema, generateSQL, quickRiskCheck,
  │   │             executeRawSQL, autoFixSQL, detectChartIntent,
  │   │             generateChartConfig, generateAISummary, assembleResult
  │   └─ 结果 → 加入 messages，继续循环
  ├─ LLM 无 tool_calls → 作为最终答案返回
  ├─ 成功查询 ≥ 3 次 → 强制总结
  └─ 达到 5 轮 → 返回最后结果
```

---

## 4. 三种模式对比

| 维度 | DIRECT (SKILL.md) | PLAN_AND_EXECUTE | REACT |
|------|-------------------|------------------|-------|
| **适用场景** | 明确查询、总结、图表、澄清 | 复杂多步、中置信度 | 开放式探索、EXPLORE 意图 |
| **步骤编排** | 确定性 YAML 编排（30+ 步骤） | LLM 生成 Plan → 动态执行 | LLM 每轮自主决定下一步 |
| **LLM 调用次数** | 1 次（SQL 生成）+ 可选优化 | 2 次（Plan + SQL）+ 可选 | 3-5 次（每轮 1 次） |
| **风险处理** | 三层评估 + LLM 优化 + 人机审批 | 三层评估 + LLM 优化 | 简单风险检查 |
| **自动修正** | ✅ autoFixSQL + 重试 | ✅ autoFixSQL + 重试 | ✅ autoFixSQL + 重试 |
| **RAG 学习** | ✅ 独立步骤 | ✅ 独立步骤 | ❌ |
| **图表/总结** | ✅ 后置生成 | ✅ 后置生成 | ✅ LLM 自主决定 |
| **多步 SQL** | ❌ 单步 | ✅ 循环 sqlSteps | ✅ LLM 自主 |
| **人机审批** | ✅ HIGH 风险时 | ❌ | ❌ |
| **sqlOnly** | ✅ | ❌ | ❌ |
| **Plan 校验** | ❌（固定编排无需校验） | ✅ PlanValidator + StepReflector | ❌ |

---

## 5. 路由决策流程

```
SkillRouter.route(intent, confidence)
  │
  ├─ EXPLORE → REACT
  ├─ confidence ≥ 0.8 且 非 COMPLEX → DIRECT
  │   ├─ QUERY → "execute_standard_query"
  │   ├─ SUMMARY → "summarize_result"
  │   ├─ CHART → "generate_chart"
  │   └─ CLARIFY → "clarify_datasource"
  ├─ COMPLEX 或 confidence ∈ [0.5, 0.8) → PLAN_AND_EXECUTE
  └─ confidence < 0.5 → PLAN_AND_EXECUTE（兜底）
```

降级链路：
```
PLAN_AND_EXECUTE: Plan 失败 / Plan 校验不通过 / complexity=SIMPLE → DIRECT
DIRECT: WorkflowEngine 加载失败 / 执行异常 → SQL Worker
REACT: MAX_ITERATIONS 到达 → 返回最后结果
全局: datasourceId=null → DatasourceClarificationTool
```

---

## 6. 原子 Tools 清单

系统提供 40+ 原子 Tool，按功能分组：

### 6.1 核心查询
| Tool | 功能 |
|------|------|
| `validateParams` | 参数校验 |
| `detectChartIntent` | 检测图表意图 |
| `extractTablePreference` | 提取表名偏好 |
| `injectIndustryConcept` | 注入行业概念 |
| `retrieveSchema` | 检索表结构 |
| `generateSQL` | 生成 SQL |
| `quickRiskCheck` | 快速风险检查 |
| `analyzeQueryPlan` | 深度风险分析 |
| `executeRawSQL` | 执行 SQL |
| `autoFixSQL` | 自动修正 SQL |

### 6.2 风险与优化
| Tool | 功能 |
|------|------|
| `regenerateSQLWithLLM` | LLM 重写 SQL |
| `getLLMOptimizationSuggestion` | 获取优化建议 |
| `requireHumanApproval` | 请求人工审批 |

### 6.3 后处理
| Tool | 功能 |
|------|------|
| `ragLearnFromExecution` | RAG 学习 |
| `generateFollowUpSuggestions` | 生成追问建议 |
| `generateChartConfig` | 生成图表配置 |
| `generateAISummary` | AI 总结 |
| `assembleResult` | 组装最终响应 |

---

## 7. 热部署与管理

### SKILL.md 热加载

SKILL.md 修改后无需重启应用，WorkflowEngine 在加载时会检测文件变更：

```bash
# 重载所有 SKILL.md（POST 请求）
curl -X POST http://localhost:8080/api/admin/skills/reload
```

### 管理端点

| 端点 | 方法 | 说明 |
|------|------|------|
| `/api/admin/skills/reload` | POST | 清除缓存，重新加载所有 SKILL.md |
| `/api/admin/skills/list` | GET | 列出已发现的所有 Skills |

---

## 8. 最佳实践

### Skill 设计原则（SKILL.md）

1. **单一职责**: 一个 SKILL.md 只解决一类问题（查询 / 总结 / 图表）
2. **可编排性**: 步骤通过 `on_next` / `condition` 灵活串联
3. **容错性**: 每个执行步骤后都有错误处理分支
4. **可观测性**: 所有步骤都有详细日志

### 何时创建新的 SKILL.md？

- 需要独立的业务流程（非标准查询场景）
- 需要独立的错误处理和降级策略
- 需要不同的参数校验规则

### 命名规范

- SKILL.md 的 `name` 字段：动词开头，下划线连接（如 `execute_standard_query`）
- Tool 名称：驼峰命名（如 `retrieveSchema`）
- 步骤 id：下划线命名（如 `validate_params`）

---

## 9. 历史演进

| 阶段 | 架构 | 状态 |
|------|------|------|
| v1 | Groovy 脚本执行 Skills（GroovySkillExecutor） | `@Deprecated` |
| v2 | Java Skill 类 + ReActAgent + LangChain4j Tool | `@Deprecated` |
| v3 | SKILL.md YAML 编排 + WorkflowEngine | ✅ 当前标准 |
| v3.1 | + SupervisorAgent 统一路由 + 三种模式 | ✅ 当前标准 |
| v3.2 | + PlanValidator + StepReflector | ✅ 当前标准 |

Groovy 脚本和旧的 Java Skill 类（StandardQuerySkill、ReportWithInsightsSkill）均已标记 `@Deprecated`，功能已全部迁移到 SKILL.md Workflow 中。
