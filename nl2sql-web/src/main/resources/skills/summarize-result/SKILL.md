---
name: summarize-result
displayName: 结果总结技能
description: 对SQL查询结果进行智能分析和总结，提取关键洞察。基于标准查询流程后追加AI总结。
category: analysis
priority: 3
version: "2.0"
script: scripts/SummarizeResultSkill.groovy  # @Deprecated - Groovy scripts are deprecated, use workflow instead
requiredParams: [question, datasourceId]
workflow:
  version: 2.0
  description: 结果总结工作流v2（原子Tool编排版，调用标准查询后追加AI总结）
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
      input:
        question: "{{question}}"
        sql: "{{query_result.sql}}"
        dataJson: "{{query_result.data}}"
        rowCount: "{{query_result.rowCount}}"
      output_var: summary_result
      on_next: generate_follow_up

    - id: generate_follow_up
      action: call_tool
      tool: generateFollowUpSuggestions
      input:
        sql: "{{query_result.sql}}"
        rowCount: "{{query_result.rowCount}}"
        dataJson: "{{query_result.data}}"
      output_var: follow_up_result
      on_next: respond

    - id: respond
      action: respond
      output:
        success: true
        type: "summary"
        data: "{{query_result.data}}"
        rowCount: "{{query_result.rowCount}}"
        sql: "{{query_result.sql}}"
        aiSummary: "{{summary_result.data.summary}}"
        followUpSuggestions: "{{follow_up_result.data.followUpSuggestions}}"
      on_next: null

    - id: respond_error
      action: respond
      output:
        success: false
        type: "error"
        error: "{{query_result.error}}"
      on_next: null
---

# 结果总结技能 v2.0

## 描述
对SQL查询结果进行智能分析和总结，提取关键洞察。基于标准查询流程后追加AI总结。

## 参数
| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| question | string | ✅ | 用户原始问题 |
| datasourceId | long | ✅ | 数据源ID |
| userId | long | ✅ | 用户ID |
| username | string | ✅ | 用户名 |
