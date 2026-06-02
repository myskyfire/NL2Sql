---
name: sql_validate_execute
description: SQL验证+执行。先快速风险判断，必要时EXPLAIN分析，再执行查询，支持自动修正重试。
version: 2.0.0
author: NL2SQL Team
requiredParams: [sql, datasourceId]
script: SQLValidateAndExecuteSkill.groovy  # @Deprecated - Groovy scripts are deprecated, use workflow instead
workflow:
  version: 2.0
  steps:
    - id: quick_risk_check
      action: call_tool
      tool: quickRiskCheck
      input:
        sql: "{{sql}}"
      output_var: quick_check_result
      on_next: check_simple

    - id: check_simple
      action: condition
      condition: "${quick_check_result.data.isSimple}==true"
      on_true: execute_sql
      on_false: analyze_query_plan

    - id: analyze_query_plan
      action: call_tool
      tool: analyzeQueryPlan
      input:
        sql: "{{sql}}"
        datasourceId: "{{datasourceId}}"
      output_var: explain_result
      on_next: check_risk

    - id: check_risk
      action: condition
      condition: "${explain_result.data.riskLevel}==HIGH"
      on_true: respond_high_risk
      on_false: execute_sql

    - id: execute_sql
      action: call_tool
      tool: executeRawSQL
      input:
        sql: "{{sql}}"
        datasourceId: "{{datasourceId}}"
        userId: "{{userId}}"
        username: "{{username}}"
      output_var: exec_result
      on_next: check_exec

    - id: check_exec
      action: condition
      condition: "${exec_result.data.success}==true"
      on_true: respond_success
      on_false: attempt_fix

    - id: attempt_fix
      action: call_tool
      tool: autoFixSQL
      input:
        failedSql: "{{sql}}"
        errorMessage: "{{exec_result.data.error}}"
      output_var: fix_result
      on_next: retry_execute

    - id: retry_execute
      action: call_tool
      tool: executeRawSQL
      input:
        sql: "{{fix_result.data.fixedSql}}"
        datasourceId: "{{datasourceId}}"
        userId: "{{userId}}"
        username: "{{username}}"
      output_var: retry_result
      on_next: check_retry

    - id: check_retry
      action: condition
      condition: "${retry_result.data.success}==true"
      on_true: respond_retry_success
      on_false: respond_error

    - id: respond_success
      action: respond
      output:
        success: true
        type: "data"
        data: "{{exec_result.data.data}}"
        rowCount: "{{exec_result.data.rowCount}}"
        executionTime: "{{exec_result.data.executionTime}}"
        sql: "{{sql}}"
        datasourceId: "{{datasourceId}}"
        riskLevel: "{{quick_check_result.data.riskLevel}}"
      on_next: null

    - id: respond_retry_success
      action: respond
      output:
        success: true
        type: "data"
        data: "{{retry_result.data.data}}"
        rowCount: "{{retry_result.data.rowCount}}"
        executionTime: "{{retry_result.data.executionTime}}"
        sql: "{{fix_result.data.fixedSql}}"
        originalSql: "{{sql}}"
        datasourceId: "{{datasourceId}}"
        autoFixed: true
      on_next: null

    - id: respond_high_risk
      action: respond
      output:
        success: false
        type: "high_risk"
        error: "SQL风险评估为高风险，已阻断执行"
        sql: "{{sql}}"
        riskLevel: "HIGH"
        riskReason: "{{explain_result.data.risks}}"
        suggestions: "{{explain_result.data.suggestions}}"
      on_next: null

    - id: respond_error
      action: respond
      output:
        success: false
        type: "error"
        error: "{{retry_result.data.error}}"
        sql: "{{sql}}"
        attemptedFix: true
      on_next: null
---

# SQL验证与执行 v2.0

## 适用场景
- 已知SQL语句，需确保安全性
- 防止SQL注入和高风险查询

## 参数
| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| sql | String | ✅ | SQL语句 |
| datasourceId | Long | ✅ | 数据源ID |
| userId | Long | ❌ | 用户ID |
| username | String | ❌ | 用户名 |
