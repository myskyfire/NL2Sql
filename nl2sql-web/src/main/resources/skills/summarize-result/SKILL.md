---
workflow:
  name: summarize-result
  description: 对SQL查询结果进行智能分析和总结，提取关键洞察
  steps:
    - id: execute_query
      type: call_workflow
      skill: execute_standard_query
      input:
        question: "{{question}}"
        datasourceId: "{{datasourceId}}"
        userId: "{{userId}}"
        username: "{{username}}"
      output_var: query_result
    - id: check_success
      action: condition
      condition: "${query_result.success}==true"
      on_true: summarize
      on_false: respond_error
    - id: summarize
      action: call_tool
      tool: summarize_result
      on_next: respond
      input:
        userQuery: "{{question}}"
        sql: "{{query_result.sql}}"
        dataJson: "{{query_result.data}}"
      output_var: summary_result
    - id: respond
      action: respond
      output:
        type: summary
        data: "{{summary_result}}"
    - id: respond_error
      action: respond
      output:
        success: false
        error: "{{query_result.error}}"
---

# Summarize Result Skill

## 描述
对SQL查询结果进行智能分析和总结，提取关键洞察。

## 参数
- `question`: 用户原始问题
- `sql`: 执行的SQL语句
- `dataJson`: 查询结果数据（JSON字符串）

## 执行流程

### Workflow 步骤

1. **retrieve_schema**: 调用 `retrieveSchema` Tool 检索相关表结构
2. **generate_sql**: 调用 `generateSQL` Tool 生成 SQL
3. **execute_sql**: 调用 `executeSQL` Tool 执行查询（条件：sql_result.success==true）
4. **summarize**: 调用 `summarize_result` Tool 对结果进行 AI 总结（条件：exec_result.success==true）
5. **respond**: 返回总结结果

## 示例

**输入**:
```json
{
  "question": "查询最近10天订单统计",
  "sql": "SELECT DATE(order_date) as date, COUNT(*) as count FROM orders GROUP BY DATE(order_date)",
  "dataJson": "[{\"date\":\"2026-05-01\",\"count\":150},{\"date\":\"2026-05-02\",\"count\":180}]"
}
```

**输出**:
```json
{
  "success": true,
  "type": "summary",
  "data": "最近10天订单量呈现波动上升趋势，5月2日达到峰值180单...",
  "metadata": {
    "executionTime": 1234
  }
}
```
