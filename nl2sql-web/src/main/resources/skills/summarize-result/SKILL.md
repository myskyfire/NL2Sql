---
workflow:
  name: summarize-result
  description: 对SQL查询结果进行智能分析和总结，提取关键洞察
  steps:
    - id: summarize
      action: call_tool
      tool: summarize_result
      input:
        userQuery: "{{question}}"
        sql: "{{sql}}"
        dataJson: "{{dataJson}}"
      output_var: summary_result
    - id: respond
      action: respond
      output:
        type: summary
        data: "{{summary_result}}"
---

# Summarize Result Skill

## 描述
对SQL查询结果进行智能分析和总结，提取关键洞察。

## 参数
- `question`: 用户原始问题
- `sql`: 执行的SQL语句
- `dataJson`: 查询结果数据（JSON字符串）

## 执行流程

### Step 1: 调用 summarize_result Tool

```groovy
def result = context.callTool("summarize_result", [
    userQuery: question,
    sql: sql,
    dataJson: dataJson
])
```

### Step 2: 返回总结结果

直接返回Tool的执行结果。

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
