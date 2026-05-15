---
name: execute_standard_query
displayName: 标准查询技能
description: 执行完整的数据查询流程，包括表结构检索、SQL生成、风险评估、执行和自动修正。适用于用户有明确数据查询需求的场景。
category: query
priority: 1
version: "1.0"
script: scripts/StandardQuerySkill.groovy
requiredParams: [question, datasourceId]
---

# 标准查询技能（Standard Query Skill）

## 功能说明

封装完整的查询生命周期，自动处理以下所有步骤：
- 智能检索相关表结构
- 自动生成 SQL 语句
- LLM 自主评估 SQL 风险（LOW/MEDIUM/HIGH/UNCERTAIN）
- 执行 SQL 查询（支持自动修正，最多重试 2 次）
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

### Workflow 配置（文档参考）

以下是声明式 workflow 配置示例（**当前未启用**，仅作架构演进参考）：

```yaml
workflow:
  version: 1.0
  description: 标准查询工作流（简化版，不含风险评估）
  steps:
    - id: retrieve_schema
      action: call_tool
      tool: retrieve_table_schema
      input:
        question: "{{question}}"
        datasourceId: "{{datasourceId}}"
      output_var: schema_result
    
    - id: generate_sql
      action: call_tool
      tool: generate_sql
      input:
        question: "{{question}}"
        datasourceId: "{{datasourceId}}"
      output_var: sql_result
    
    - id: execute_sql
      action: call_tool
      tool: execute_sql
      condition: "{{sql_result.success == true}}"
      input:
        sql: "{{sql_result.sql}}"
        datasourceId: "{{datasourceId}}"
        userId: "{{userId}}"
        username: "{{username}}"
      output_var: exec_result
    
    - id: respond_success
      action: respond
      condition: "{{exec_result.success == true}}"
      output:
        status: "success"
        data: "{{exec_result.data}}"
        rowCount: "{{exec_result.rowCount}}"
        executionTime: "{{exec_result.executionTime}}"
        sql: "{{sql_result.sql}}"
    
    - id: respond_error
      action: respond
      condition: "{{exec_result.success == false}}"
      output:
        status: "error"
        error: "{{exec_result.error}}"
```

⚠️ **重要说明**：
- 当前 workflow 配置**不完整**，缺少风险评估、自动修正等复杂逻辑
- 实际执行仍通过 `script` 字段指定的 Groovy 脚本
- Workflow 配置仅作为架构演进参考，未来可逐步迁移简单场景

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
