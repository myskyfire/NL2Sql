---
name: generate_report_with_insights
displayName: 报表与洞察技能
description: 在标准查询基础上，增加AI智能总结和图表推荐，生成完整的分析报告。适用于用户需要深度分析、趋势洞察或综合报告的场景。
category: analysis
priority: 2
version: "2.0"
script: scripts/ReportWithInsightsSkill.groovy  # @Deprecated - Groovy scripts are deprecated, use workflow instead
requiredParams: [question, datasourceId]
workflow:
  version: 2.0
  description: 报表与洞察工作流v2（原子Tool编排版，在标准查询基础上增加AI总结和图表推荐）
  steps:
    - id: validate_params
      action: call_tool
      tool: validateParams
      input:
        question: "{{question}}"
        datasourceId: "{{datasourceId}}"
        userId: "{{userId}}"
        username: "{{username}}"
      output_var: validation_result
      on_next: detect_chart_intent

    - id: detect_chart_intent
      action: call_tool
      tool: detectChartIntent
      condition: "${validation_result.validated}==true"
      input:
        question: "{{question}}"
      output_var: chart_intent_result
      on_next: extract_table_preference
      on_condition_false: respond_clarification

    - id: extract_table_preference
      action: call_tool
      tool: extractTablePreference
      input:
        question: "{{chart_intent_result.data.cleanedQuestion}}"
      output_var: table_pref_result
      on_next: inject_industry_concept

    - id: inject_industry_concept
      action: call_tool
      tool: injectIndustryConcept
      input:
        question: "{{chart_intent_result.data.cleanedQuestion}}"
        datasourceId: "{{datasourceId}}"
      output_var: concept_result
      on_next: retrieve_schema

    - id: retrieve_schema
      action: call_tool
      tool: retrieveSchema
      input:
        question: "{{concept_result.data.enhancedQuestion}}"
        datasourceId: "{{datasourceId}}"
      output_var: schema_result
      on_next: generate_sql

    - id: generate_sql
      action: call_tool
      tool: generateSQL
      input:
        question: "{{concept_result.data.enhancedQuestion}}"
        datasourceId: "{{datasourceId}}"
        schemaInfo: "{{schema_result}}"
        tableHint: "{{table_pref_result.data.tablePreference}}"
      output_var: sql_result
      on_next: check_sql_valid

    - id: check_sql_valid
      action: condition
      condition: "${sql_result.success}==true"
      on_true: init_final_sql
      on_false: respond_error

    - id: init_final_sql
      action: set_var
      output_var: final_sql
      value: "{{sql_result.data.sql}}"
      on_next: quick_risk_check

    - id: quick_risk_check
      action: call_tool
      tool: quickRiskCheck
      input:
        sql: "{{sql_result.data.sql}}"
      output_var: quick_check_result
      on_next: check_simple_query

    - id: check_simple_query
      action: condition
      condition: "${quick_check_result.data.isSimple}==true"
      on_true: execute_sql
      on_false: analyze_query_plan

    - id: analyze_query_plan
      action: call_tool
      tool: analyzeQueryPlan
      input:
        sql: "{{sql_result.data.sql}}"
        datasourceId: "{{datasourceId}}"
      output_var: explain_result
      on_next: check_risk_level

    - id: check_risk_level
      action: condition
      condition: "${explain_result.data.riskLevel}==HIGH"
      on_true: handle_high_risk
      on_false: execute_sql

    - id: handle_high_risk
      action: call_tool
      tool: regenerateSQLWithLLM
      input:
        originalSql: "{{sql_result.data.sql}}"
        question: "{{chart_intent_result.data.cleanedQuestion}}"
        explainRisks: "{{explain_result.data.risks}}"
        explainSuggestions: "{{explain_result.data.suggestions}}"
      output_var: regenerate_result
      on_next: check_regenerate

    - id: check_regenerate
      action: condition
      condition: "${regenerate_result.data.regenerationSuccess}==true"
      on_true: use_optimized_sql
      on_false: respond_high_risk

    - id: use_optimized_sql
      action: set_var
      output_var: final_sql
      value: "{{regenerate_result.data.optimizedSql}}"
      on_next: execute_sql

    - id: execute_sql
      action: call_tool
      tool: executeRawSQL
      input:
        sql: "{{final_sql}}"
        datasourceId: "{{datasourceId}}"
        userId: "{{userId}}"
        username: "{{username}}"
      output_var: exec_result
      on_next: check_exec_success

    - id: check_exec_success
      action: condition
      condition: "${exec_result.data.success}==true"
      on_true: rag_learn
      on_false: attempt_auto_fix

    - id: attempt_auto_fix
      action: call_tool
      tool: autoFixSQL
      input:
        failedSql: "{{final_sql}}"
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
      output_var: retry_exec_result
      on_next: check_retry_success

    - id: check_retry_success
      action: condition
      condition: "${retry_exec_result.data.success}==true"
      on_true: rag_learn_retry
      on_false: respond_error

    - id: rag_learn
      action: call_tool
      tool: ragLearnFromExecution
      input:
        question: "{{chart_intent_result.data.cleanedQuestion}}"
        sql: "{{final_sql}}"
        rowCount: "{{exec_result.data.rowCount}}"
        executionTimeMs: "{{exec_result.data.executionTime}}"
      output_var: rag_result
      on_next: check_data_for_summary

    - id: rag_learn_retry
      action: call_tool
      tool: ragLearnFromExecution
      input:
        question: "{{chart_intent_result.data.cleanedQuestion}}"
        sql: "{{fix_result.data.fixedSql}}"
        rowCount: "{{retry_exec_result.data.rowCount}}"
        executionTimeMs: "{{retry_exec_result.data.executionTime}}"
      output_var: rag_result
      on_next: check_data_for_summary_retry

    - id: check_data_for_summary
      action: condition
      condition: "${exec_result.data.rowCount}>5"
      on_true: generate_ai_summary
      on_false: generate_follow_up

    - id: check_data_for_summary_retry
      action: condition
      condition: "${retry_exec_result.data.rowCount}>5"
      on_true: generate_ai_summary_retry
      on_false: generate_follow_up_retry

    - id: generate_ai_summary
      action: call_tool
      tool: summarize_result
      input:
        question: "{{chart_intent_result.data.cleanedQuestion}}"
        sql: "{{final_sql}}"
        dataJson: "{{exec_result.data.data}}"
        rowCount: "{{exec_result.data.rowCount}}"
      output_var: summary_result
      on_next: generate_chart_recommendation

    - id: generate_ai_summary_retry
      action: call_tool
      tool: summarize_result
      input:
        question: "{{chart_intent_result.data.cleanedQuestion}}"
        sql: "{{fix_result.data.fixedSql}}"
        dataJson: "{{retry_exec_result.data.data}}"
        rowCount: "{{retry_exec_result.data.rowCount}}"
      output_var: summary_result
      on_next: generate_chart_recommendation_retry

    - id: generate_chart_recommendation
      action: call_tool
      tool: generateChartConfig
      input:
        chartType: "{{chart_intent_result.data.chartType}}"
        dataJson: "{{exec_result.data.data}}"
      output_var: chart_config_result
      on_next: generate_follow_up

    - id: generate_chart_recommendation_retry
      action: call_tool
      tool: generateChartConfig
      input:
        chartType: "{{chart_intent_result.data.chartType}}"
        dataJson: "{{retry_exec_result.data.data}}"
      output_var: chart_config_result
      on_next: generate_follow_up_retry

    - id: generate_follow_up
      action: call_tool
      tool: generateFollowUpSuggestions
      input:
        sql: "{{final_sql}}"
        rowCount: "{{exec_result.data.rowCount}}"
        dataJson: "{{exec_result.data.data}}"
      output_var: follow_up_result
      on_next: assemble_result

    - id: generate_follow_up_retry
      action: call_tool
      tool: generateFollowUpSuggestions
      input:
        sql: "{{fix_result.data.fixedSql}}"
        rowCount: "{{retry_exec_result.data.rowCount}}"
        dataJson: "{{retry_exec_result.data.data}}"
      output_var: follow_up_result
      on_next: assemble_result_retry

    - id: assemble_result
      action: call_tool
      tool: post_process_response
      input:
        data: "{{exec_result.data.data}}"
        rowCount: "{{exec_result.data.rowCount}}"
        sql: "{{final_sql}}"
        datasourceId: "{{datasourceId}}"
        executionTime: "{{exec_result.data.executionTime}}"
        chartConfig: "{{chart_config_result.data.echartsConfig}}"
      output_var: final_result
      on_next: respond_success

    - id: assemble_result_retry
      action: call_tool
      tool: post_process_response
      input:
        data: "{{retry_exec_result.data.data}}"
        rowCount: "{{retry_exec_result.data.rowCount}}"
        sql: "{{fix_result.data.fixedSql}}"
        datasourceId: "{{datasourceId}}"
        executionTime: "{{retry_exec_result.data.executionTime}}"
        chartConfig: "{{chart_config_result.data.echartsConfig}}"
      output_var: final_result
      on_next: respond_success_retry

    - id: respond_success
      action: respond
      output:
        success: true
        type: "report"
        data: "{{final_result.data}}"
        rowCount: "{{final_result.rowCount}}"
        executionTime: "{{final_result.executionTime}}"
        sql: "{{final_result.sql}}"
        datasourceId: "{{final_result.datasourceId}}"
        aiSummary: "{{summary_result.data.summary}}"
        chartRecommendations: "{{chart_config_result.data.echartsConfig}}"
        followUpSuggestions: "{{follow_up_result.data.followUpSuggestions}}"
      on_next: null

    - id: respond_success_retry
      action: respond
      output:
        success: true
        type: "report"
        data: "{{final_result.data}}"
        rowCount: "{{final_result.rowCount}}"
        executionTime: "{{final_result.executionTime}}"
        sql: "{{final_result.sql}}"
        originalSql: "{{sql_result.data.sql}}"
        autoFixed: true
        datasourceId: "{{final_result.datasourceId}}"
        aiSummary: "{{summary_result.data.summary}}"
        chartRecommendations: "{{chart_config_result.data.echartsConfig}}"
        followUpSuggestions: "{{follow_up_result.data.followUpSuggestions}}"
      on_next: null

    - id: respond_high_risk
      action: respond
      output:
        success: false
        type: "high_risk"
        error: "SQL风险评估为高风险，已阻断执行"
        sql: "{{sql_result.data.sql}}"
        riskLevel: "HIGH"
        riskReason: "{{explain_result.data.risks}}"
      on_next: null

    - id: respond_error
      action: respond
      output:
        success: false
        type: "error"
        error: "{{retry_exec_result.data.error}}"
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

# 报表与洞察技能（Report with Insights Skill）v2.0

## 功能说明

高级分析技能，基于原子Tool编排，在标准查询基础上增加：
- AI智能总结：数据量>5行时自动生成
- 图表推荐与配置：基于数据特征推荐可视化
- 追问建议：引导用户深入探索

## 参数说明

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| question | string | ✅ | 用户的分析需求 |
| datasourceId | long | ✅ | 数据源ID |
| userId | long | ✅ | 用户ID |
| username | string | ✅ | 用户名 |
