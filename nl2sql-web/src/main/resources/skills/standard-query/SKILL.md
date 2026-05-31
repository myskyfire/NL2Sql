---
name: execute_standard_query
displayName: 标准查询技能
description: 执行完整的数据查询流程，包括表结构检索、SQL生成、风险评估、执行和自动修正。适用于用户有明确数据查询需求的场景。
category: query
priority: 1
version: "2.0"
script: scripts/StandardQuerySkill.groovy  # @Deprecated - Groovy scripts are deprecated, use workflow instead
requiredParams: [question, datasourceId]
workflow:
  version: 2.0
  description: 标准查询工作流（完整版，含参数校验、图表检测、LLM风险评估、人机协同、sqlOnly分支）
  steps:
    - id: validate_params
      action: call_tool
      tool: validateParams
      input:
        question: "{{question}}"
        datasourceId: "{{datasourceId}}"
        userId: "{{userId}}"
        username: "{{username}}"
        sqlOnly: "{{sqlOnly}}"
      output_var: validation_result
      on_next: detect_chart

    - id: detect_chart
      action: call_tool
      tool: detectAndGenerateChart
      condition: "${validation_result.validated}==true"
      input:
        question: "{{question}}"
      output_var: chart_result
      on_next: retrieve_schema
      on_condition_false: respond_clarification

    - id: retrieve_schema
      action: call_tool
      tool: retrieveSchema
      input:
        question: "{{chart_result.cleanedQuestion}}"
        datasourceId: "{{datasourceId}}"
      output_var: schema_result
      on_next: generate_sql

    - id: generate_sql
      action: call_tool
      tool: generateSQL
      input:
        question: "{{chart_result.cleanedQuestion}}"
        datasourceId: "{{datasourceId}}"
        schemaInfo: "{{schema_result}}"
        tableHint: ""
      output_var: sql_result
      on_next: check_sql_valid

    - id: check_sql_valid
      action: call_tool
      tool: analyzeSQLRisk
      condition: "${sql_result.success}==true"
      input:
        sql: "{{sql_result.data.sql}}"
        datasourceId: "{{datasourceId}}"
        question: "{{chart_result.cleanedQuestion}}"
      output_var: risk_result
      on_next: check_human_approval
      on_condition_false: respond_error

    - id: check_human_approval
      action: respond
      condition: "${risk_result.needsHumanApproval}==true"
      output:
        success: false
        type: "human_approval_required"
        approvalId: "{{sessionId}}"
        riskLevel: "{{risk_result.riskLevel}}"
        riskReason: "{{risk_result.reason}}"
        sql: "{{sql_result.data.sql}}"
        optimizedSql: "{{risk_result.optimizedSql}}"
        optimizationSuggestion: "{{risk_result.optimizationSuggestion}}"
        message: "该SQL存在高风险，请审核后再决定是否执行"
      on_next: null
      on_condition_false: check_sql_only

    - id: check_sql_only
      action: respond
      condition: "${validation_result.sqlOnly}==true"
      output:
        success: true
        type: "sql_only"
        sql: "{{sql_result.data.sql}}"
        datasourceId: "{{datasourceId}}"
        optimizationSuggestion: "{{risk_result.optimizationSuggestion}}"
      on_next: null
      on_condition_false: execute_sql

    - id: execute_sql
      action: call_tool
      tool: executeSQL
      input:
        sql: "{{sql_result.data.sql}}"
        datasourceId: "{{datasourceId}}"
        userId: "{{userId}}"
        username: "{{username}}"
      output_var: exec_result
      on_next: post_process

    - id: post_process
      action: call_tool
      tool: post_process_response
      condition: "${exec_result.success}==true"
      input:
        data: "{{exec_result.data}}"
        rowCount: "{{exec_result.rowCount}}"
        executionTime: "{{exec_result.executionTime}}"
        sql: "{{sql_result.data.sql}}"
        datasourceId: "{{datasourceId}}"
        optimizationSuggestion: "{{risk_result.optimizationSuggestion}}"
        chartConfig: "{{chart_result.echartsConfig}}"
      output_var: final_result
      on_next: respond_success
      on_condition_false: respond_error

    - id: respond_success
      action: respond
      output:
        success: "{{final_result.success}}"
        type: "{{final_result.type}}"
        data: "{{final_result.data}}"
        rowCount: "{{final_result.rowCount}}"
        executionTime: "{{final_result.executionTime}}"
        sql: "{{final_result.sql}}"
        datasourceId: "{{final_result.datasourceId}}"
        followUpSuggestions: "{{final_result.followUpSuggestions}}"
        optimizationSuggestion: "{{final_result.optimizationSuggestion}}"
        chartConfig: "{{final_result.chartConfig}}"
      on_next: null

    - id: respond_error
      action: respond
      output:
        success: false
        type: "error"
        error: "{{exec_result.error}}"
      on_next: null

    - id: respond_clarification
      action: respond
      output:
        success: true
        type: "clarification"
        needsClarification: true
        clarificationMessage: "{{validation_result.clarificationMessage}}"
      on_next: null
---

# 标准查询技能（Standard Query Skill）

## 功能说明

封装完整的查询生命周期，自动处理以下所有步骤：
- 参数校验（datasourceId、question 必填校验，缺失时返回澄清请求）
- 图表意图检测（自动识别柱状图/折线图/饼图/面积图需求，生成ECharts配置）
- 智能检索相关表结构（并设置ThreadLocal表名偏好）
- 自动生成 SQL 语句（支持预检索schema和表名偏好）
- LLM 自主评估 SQL 风险（三层评估：快速判断→EXPLAIN→LLM辅助）
- 高风险SQL自动优化与重新生成
- 人机协同审批（高风险SQL需人工确认）
- sqlOnly 模式（仅生成SQL不执行）
- 执行 SQL 查询（支持自动修正，最多重试 2 次）
- 智能后处理（追问建议、优化建议、图表配置）
- 高风险 SQL 自动阻断，保护数据库性能

## 适用场景

✅ **应该使用此 Skill**：
- 简单数据查询：“查询最近10条记录”
- 统计分析：“统计上月各地区某指标”
- 筛选过滤：“找出满足特定条件的数据”
- 排序查询：“查看排行榜”
- 分组聚合：“按维度统计数据数量”

❌ **不应该使用此 Skill**：
- 需要深度分析和洞察 → 使用 `generate_report_with_insights`
- 需要分步调试 SQL → 手动调用底层 Tools（retrieve_table_schema, generate_sql, execute_sql）
- 用户明确要求“分析”、“总结”、“报告”等关键词

## 参数说明

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| question | string | ✅ | 用户的自然语言查询问题 |
| datasourceId | long | ✅ | 数据源ID |
| userId | long | ✅ | 用户ID |
| username | string | ✅ | 用户名 |

## 内部工作流程

### 当前实现（Groovy 脚本）

当前通过 `scripts/StandardQuerySkill.groovy` 执行，包含复杂的 LLM 交互和循环重试逻辑。

### Workflow 配置（已启用）

Workflow 定义已在 YAML front matter 中配置，由 WorkflowEngine 直接执行。执行顺序：

1. `retrieve_schema` → 调用 `retrieveSchema` Tool 检索表结构
2. `generate_sql` → 调用 `execute` Tool (GenerateSQLTool) 生成 SQL
3. `check_sql_valid` → 调用 `analyzeSQLRisk` Tool 评估风险
4. `execute_sql` → 调用 `executeSQL` Tool 执行查询（风险非 HIGH 时）
5. `respond_success` / `respond_error` → 返回结果

⚠️ **降级机制**：如果 workflow 执行失败，系统会自动降级到 Worker 执行模式。

### Groovy 脚本详细流程

```
用户问题
  ↓
Step 1: 检索表结构 (NL2SQLService.retrieveSchema)
  ↓
Step 2: 生成 SQL (NL2SQLService.generateSQL)
  ↓
Step 2.5: SQL 优化与风险评估
  - 检测 IN 子查询关联问题
  - LLM 自主评估风险等级
  - 必要时调用 EXPLAIN 辅助分析
  ↓
Step 3: 执行 SQL (SQLExecutionTool.executeSQL)
  - 支持自动修正（最多重试 2 次）
  - 失败时调用 NL2SQLService.autoFixSQL
  ↓
返回查询结果
```

## 风险评估机制

Skill 内置三层风险评估：

1. **LLM 自主评估**：基于 SQL 复杂度、JOIN 数量、子查询等因素
2. **EXPLAIN 辅助**：当 LLM 不确定时，调用数据库 EXPLAIN 分析
3. **风险分级**：
   - LOW：直接执行
   - MEDIUM：继续执行但标记警告
   - HIGH：阻断执行，返回错误信息
   - UNCERTAIN：调用 EXPLAIN 进一步分析

## 返回结果

✅ **统一响应格式**：所有返回均包含 `success` 和 `type` 字段

### 成功响应（type: "data"）
```json
{
  "success": true,
  "type": "data",
  "data": [],
  "rowCount": 10,
  "executionTime": 125.5,
  "sql": "SELECT ...",
  "datasourceId": 1,
  "followUpSuggestions": [
    {"text": "🤖 AI 总结", "action": "generate_summary"},
    {"text": "📊 生成图表", "action": "generate_chart"}
  ],
  "optimizationSuggestion": "优化建议（可选）"
}
```

### 需要澄清（type: "clarification"）
```json
{
  "success": true,
  "type": "clarification",
  "needsClarification": true,
  "clarificationMessage": "请明确查询意图..."
}
```

### 错误响应（type: "error"）
```json
{
  "success": false,
  "type": "error",
  "error": "错误描述"
}
```

### 高风险阻断（type: "error"）
```json
{
  "success": false,
  "type": "error",
  "error": "⚠️ SQL风险评估为高风险，已阻断执行\n原因: ...",
  "sql": "SELECT ...",
  "optimizationSuggestion": "优化建议（可选）"
}
```

## 示例

### 示例 1：简单查询
**用户**：查询最近10条记录  
**Skill 调用**：`execute_standard_query("查询最近10条记录", 1, 123, "user")`

### 示例 2：统计分析
**用户**：统计上月各地区某指标  
**Skill 调用**：`execute_standard_query("统计上月各地区某指标", 1, 123, "user")`

### 示例 3：筛选过滤
**用户**：找出满足特定条件的数据  
**Skill 调用**：`execute_standard_query("找出满足特定条件的数据", 1, 123, "user")`

## 注意事项

⚠️ **重要提示**：
- 优先使用此 Skill，它封装了完整的错误处理和重试机制
- 不要在 SystemMessage 中展示 Thought/Action/Observation 等内部思考过程
- 直接将查询结果用友好的中文回复给用户
- 如果 SQL 执行失败，Skill 会自动尝试修正，无需人工干预
