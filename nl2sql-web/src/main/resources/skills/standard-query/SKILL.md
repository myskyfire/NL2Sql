---
name: execute_standard_query
displayName: 标准查询技能
description: 执行完整的数据查询流程，包括参数校验、图表意图检测、表名偏好提取、行业概念注入、表结构检索、SQL生成、三层风险评估、执行（含自动修正）、RAG学习和智能后处理。适用于用户有明确数据查询需求的场景。
category: query
priority: 1
version: "3.0"
script: scripts/StandardQuerySkill.groovy  # @Deprecated - Groovy scripts are deprecated, use workflow instead
requiredParams: [question, datasourceId]
workflow:
  version: 3.0
  description: 标准查询工作流v3（原子Tool编排版，含参数校验、图表意图检测、表名偏好提取、行业概念注入、三层风险评估、自动修正、RAG学习）
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
      on_condition_false: respond_error

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
      on_true: check_sql_only
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
      on_false: check_medium_risk

    - id: check_medium_risk
      action: condition
      condition: "${explain_result.data.riskLevel}==MEDIUM"
      on_true: get_llm_optimization_suggestion
      on_false: check_sql_only

    - id: get_llm_optimization_suggestion
      action: call_tool
      tool: getLLMOptimizationSuggestion
      input:
        sql: "{{sql_result.data.sql}}"
        question: "{{chart_intent_result.data.cleanedQuestion}}"
        explainRisks: "{{explain_result.data.risks}}"
      output_var: llm_suggestion_result
      on_next: check_sql_only

    - id: handle_high_risk
      action: call_tool
      tool: regenerateSQLWithLLM
      input:
        originalSql: "{{sql_result.data.sql}}"
        question: "{{chart_intent_result.data.cleanedQuestion}}"
        explainRisks: "{{explain_result.data.risks}}"
        explainSuggestions: "{{explain_result.data.suggestions}}"
      output_var: regenerate_result
      on_next: check_regenerate_success

    - id: check_regenerate_success
      action: condition
      condition: "${regenerate_result.data.regenerationSuccess}==true"
      on_true: re_analyze_query_plan
      on_false: require_human_approval

    - id: re_analyze_query_plan
      action: call_tool
      tool: analyzeQueryPlan
      input:
        sql: "{{regenerate_result.data.optimizedSql}}"
        datasourceId: "{{datasourceId}}"
      output_var: re_explain_result
      on_next: check_re_explain_risk

    - id: check_re_explain_risk
      action: condition
      condition: "${re_explain_result.data.riskLevel}==HIGH"
      on_true: require_human_approval
      on_false: use_optimized_sql

    - id: use_optimized_sql
      action: set_var
      output_var: final_sql
      value: "{{regenerate_result.data.optimizedSql}}"
      on_next: check_sql_only_with_optimized

    - id: check_sql_only_with_optimized
      action: condition
      condition: "${validation_result.sqlOnly}==true"
      on_true: respond_sql_only_optimized
      on_false: execute_sql

    - id: require_human_approval
      action: respond
      output:
        success: false
        type: "human_approval_required"
        approvalId: "risk_{{sessionId}}"
        riskLevel: "HIGH"
        riskReason: "{{explain_result.data.risks}}"
        sql: "{{sql_result.data.sql}}"
        optimizedSql: "{{regenerate_result.data.optimizedSql}}"
        optimizationSuggestion: "{{explain_result.data.suggestions}}"
        message: "该SQL存在高风险，请审核后再决定是否执行"
      on_next: null

    - id: check_sql_only
      action: condition
      condition: "${validation_result.sqlOnly}==true"
      on_true: respond_sql_only
      on_false: execute_sql

    - id: respond_sql_only
      action: respond
      output:
        success: true
        type: "sql_only"
        sql: "{{sql_result.data.sql}}"
        datasourceId: "{{datasourceId}}"
        riskLevel: "{{quick_check_result.data.riskLevel}}"
        optimizationSuggestion: "{{llm_suggestion_result.data.suggestion}}"
      on_next: null

    - id: respond_sql_only_optimized
      action: respond
      output:
        success: true
        type: "sql_only"
        sql: "{{regenerate_result.data.optimizedSql}}"
        originalSql: "{{sql_result.data.sql}}"
        datasourceId: "{{datasourceId}}"
        riskLevel: "{{re_explain_result.data.riskLevel}}"
        optimizationApplied: true
      on_next: null

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
      on_next: generate_follow_up

    - id: rag_learn_retry
      action: call_tool
      tool: ragLearnFromExecution
      input:
        question: "{{chart_intent_result.data.cleanedQuestion}}"
        sql: "{{fix_result.data.fixedSql}}"
        rowCount: "{{retry_exec_result.data.rowCount}}"
        executionTimeMs: "{{retry_exec_result.data.executionTime}}"
      output_var: rag_result
      on_next: generate_follow_up_retry

    - id: generate_follow_up
      action: call_tool
      tool: generateFollowUpSuggestions
      input:
        sql: "{{final_sql}}"
        rowCount: "{{exec_result.data.rowCount}}"
        dataJson: "{{exec_result.data.data}}"
      output_var: follow_up_result
      on_next: check_chart_needed

    - id: generate_follow_up_retry
      action: call_tool
      tool: generateFollowUpSuggestions
      input:
        sql: "{{fix_result.data.fixedSql}}"
        rowCount: "{{retry_exec_result.data.rowCount}}"
        dataJson: "{{retry_exec_result.data.data}}"
      output_var: follow_up_result
      on_next: check_chart_needed_retry

    - id: check_chart_needed
      action: condition
      condition: "${chart_intent_result.data.chartDetected}==true"
      on_true: generate_chart_config
      on_false: assemble_result

    - id: generate_chart_config
      action: call_tool
      tool: generateChartConfig
      input:
        chartType: "{{chart_intent_result.data.chartType}}"
        dataJson: "{{exec_result.data.data}}"
      output_var: chart_config_result
      on_next: assemble_result

    - id: assemble_result
      action: call_tool
      tool: post_process_response
      input:
        data: "{{exec_result.data.data}}"
        rowCount: "{{exec_result.data.rowCount}}"
        sql: "{{final_sql}}"
        datasourceId: "{{datasourceId}}"
        executionTime: "{{exec_result.data.executionTime}}"
        optimizationSuggestion: "{{llm_suggestion_result.data.suggestion}}"
        chartConfig: "{{chart_config_result.data.echartsConfig}}"
      output_var: final_result
      on_next: respond_success

    - id: check_chart_needed_retry
      action: condition
      condition: "${chart_intent_result.data.chartDetected}==true"
      on_true: generate_chart_config_retry
      on_false: assemble_result_retry

    - id: generate_chart_config_retry
      action: call_tool
      tool: generateChartConfig
      input:
        chartType: "{{chart_intent_result.data.chartType}}"
        dataJson: "{{retry_exec_result.data.data}}"
      output_var: chart_config_result
      on_next: assemble_result_retry

    - id: assemble_result_retry
      action: call_tool
      tool: post_process_response
      input:
        data: "{{retry_exec_result.data.data}}"
        rowCount: "{{retry_exec_result.data.rowCount}}"
        sql: "{{fix_result.data.fixedSql}}"
        datasourceId: "{{datasourceId}}"
        executionTime: "{{retry_exec_result.data.executionTime}}"
        optimizationSuggestion: "{{llm_suggestion_result.data.suggestion}}"
        chartConfig: "{{chart_config_result.data.echartsConfig}}"
      output_var: final_result
      on_next: respond_success_retry

    - id: respond_success
      action: respond
      output:
        success: true
        type: "data"
        data: "{{final_result.data}}"
        rowCount: "{{final_result.rowCount}}"
        executionTime: "{{final_result.executionTime}}"
        sql: "{{final_result.sql}}"
        datasourceId: "{{final_result.datasourceId}}"
        followUpSuggestions: "{{follow_up_result.data.followUpSuggestions}}"
        optimizationSuggestion: "{{final_result.optimizationSuggestion}}"
        chartConfig: "{{chart_config_result.data.echartsConfig}}"
      on_next: null

    - id: respond_success_retry
      action: respond
      output:
        success: true
        type: "data"
        data: "{{final_result.data}}"
        rowCount: "{{final_result.rowCount}}"
        executionTime: "{{final_result.executionTime}}"
        sql: "{{final_result.sql}}"
        originalSql: "{{sql_result.data.sql}}"
        autoFixed: true
        datasourceId: "{{final_result.datasourceId}}"
        followUpSuggestions: "{{follow_up_result.data.followUpSuggestions}}"
        optimizationSuggestion: "{{final_result.optimizationSuggestion}}"
        chartConfig: "{{chart_config_result.data.echartsConfig}}"
      on_next: null

    - id: respond_error
      action: respond
      output:
        success: false
        type: "error"
        error: "{{exec_result.data.error}}"
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

# 标准查询技能（Standard Query Skill）v3.0

## 功能说明

封装完整的查询生命周期，基于原子Tool编排：
1. 参数校验
2. 图表意图检测（仅检测，生成后置）
3. 表名偏好提取
4. 行业概念注入
5. 表结构检索
6. SQL生成
7. 三层风险评估（快速判断→EXPLAIN→LLM辅助）
8. 高风险SQL自动优化与重新生成
9. 人机协同审批
10. sqlOnly模式
11. 执行SQL（含自动修正重试）
12. RAG自动学习
13. 智能后处理（追问建议、图表配置）

## 与v2.0的区别

| 维度 | v2.0 | v3.0 |
|------|------|------|
| 图表处理 | detectAndGenerateChart（检测+生成合一） | detectChartIntent + generateChartConfig（分离） |
| 风险评估 | analyzeSQLRisk（三层合一巨无霸） | quickRiskCheck + analyzeQueryPlan + getLLMOptimizationSuggestion + regenerateSQLWithLLM |
| SQL执行 | executeSQL（执行+重试+修正+RAG合一） | executeRawSQL + autoFixSQL + ragLearnFromExecution |
| 后处理 | post_process_response（追问+图表+组装合一） | generateFollowUpSuggestions + generateChartConfig + post_process_response（仅组装） |
| SQL生成 | generateSQL（含表名提取+行业扩展） | extractTablePreference + injectIndustryConcept + generateSQL（纯生成） |
| RAG学习 | 内嵌在executeSQL中 | 独立步骤ragLearnFromExecution |

## 参数说明

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| question | string | ✅ | 用户的自然语言查询问题 |
| datasourceId | long | ✅ | 数据源ID |
| userId | long | ✅ | 用户ID |
| username | string | ✅ | 用户名 |
