---
name: sql_performance_analysis
description: SQL性能分析：执行计划+索引检查+成本估算。多Tool协同诊断。
version: 1.0.0
author: NL2SQL Team
requiredParams: [sql, datasourceId]
script: SQLPerformanceAnalysisSkill.groovy  # @Deprecated - Groovy scripts are deprecated, use workflow instead
workflow:
  version: 1.0
  steps:
    - id: validate
      action: call_tool
      tool: validate_sql
      input:
        sql: "{{sql}}"
        datasourceId: "{{datasourceId}}"
      output_var: validation_result
    
    - id: explain
      action: call_tool
      tool: analyze_query_plan
      condition: "{{validation_result.valid == true}}"
      input:
        sql: "{{sql}}"
        datasourceId: "{{datasourceId}}"
      output_var: explain_result
    
    - id: check_index
      action: call_tool
      tool: check_index
      condition: "{{validation_result.valid == true}}"
      input:
        sql: "{{sql}}"
        datasourceId: "{{datasourceId}}"
      output_var: index_result
    
    - id: estimate_cost
      action: call_tool
      tool: estimate_cost
      condition: "{{validation_result.valid == true}}"
      input:
        sql: "{{sql}}"
        datasourceId: "{{datasourceId}}"
      output_var: cost_result
    
    - id: respond
      action: respond
      output:
        status: "success"
        sql: "{{sql}}"
        datasourceId: "{{datasourceId}}"
        analysis:
          validation: "{{validation_result}}"
          explainPlan: "{{explain_result}}"
          indexCheck: "{{index_result}}"
          costEstimate: "{{cost_result}}"
---

# SQL性能分析

## 适用场景
- SQL性能调优前诊断
- 自动化SQL审查

## 参数
| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| sql | String | ✅ | SQL语句 |
| datasourceId | Long | ✅ | 数据源ID |

## 返回
```json
{
  "status": "success",
  "analysis": {
    "validation": {"valid": true, "riskLevel": "LOW"},
    "explainPlan": {"risks": ["全表扫描"]},
    "indexCheck": {"missingIndexes": [...]},
    "costEstimate": {"estimatedCost": 1250}
  },
  "suggestions": [
    "⚠️ 检测到全表扫描，建议添加索引"
  ]
}
```
