---
name: sql_validate_execute
description: SQL验证+执行。先校验语法和安全性，再执行查询。
version: 1.0.0
author: NL2SQL Team
requiredParams: [sql, datasourceId]
script: SQLValidateAndExecuteSkill.groovy
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
    
    - id: execute
      action: call_tool
      tool: execute_safe_sql
      condition: "{{validation_result.valid == true}}"
      input:
        sql: "{{sql}}"
        datasourceId: "{{datasourceId}}"
      output_var: execution_result
    
    - id: respond
      action: respond
      output:
        status: "success"
        validation: "{{validation_result}}"
        execution: "{{execution_result}}"
        sql: "{{sql}}"
        datasourceId: "{{datasourceId}}"
---

# SQL验证与执行

## 适用场景
- 已知SQL语句，需确保安全性
- 防止SQL注入

## 参数
| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| sql | String | ✅ | SQL语句 |
| datasourceId | Long | ✅ | 数据源ID |

## 返回
### 成功
```json
{
  "status": "success",
  "validation": {"valid": true, "riskLevel": "LOW"},
  "execution": {"rowCount": 10, "data": [...]}
}
```

### 验证失败
```json
{
  "status": "validation_failed",
  "error": "只允许执行SELECT查询"
}
```
