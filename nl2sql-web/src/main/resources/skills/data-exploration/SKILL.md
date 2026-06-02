---
name: data_exploration
displayName: 数据探索技能
description: |
  开放式数据探索，使用ReAct（Thought-Action-Observation）循环模式。
  适用于用户不确定具体查询目标、需要发现数据规律/异常/模式的场景。
  与DIRECT（固定Workflow）和PLAN_AND_EXECUTE（结构化Plan）不同，
  ReAct模式每一步都由LLM根据上一步观察结果决定下一步行动，
  直到发现有价值的洞察或达到最大迭代次数。
category: exploration
priority: 4
version: "1.0"
executionMode: REACT
requiredParams: [question, datasourceId]
availableTools:
  - retrieveSchema
  - generateSQL
  - quickRiskCheck
  - executeRawSQL
  - autoFixSQL
  - detectChartIntent
  - generateChartConfig
  - summarize_result
  - assembleResult
reactConfig:
  maxIterations: 5
  temperature: 0.3
  forceSummaryAfterSuccessfulQueries: 3
  truncationLength: 3000
  systemPrompt: |
    You are a data exploration assistant. You help users discover patterns,
    anomalies, and insights in their data.

    ## Workflow
    1. First understand what data is available by retrieving schema
    2. Generate SQL queries to explore the data
    3. Observe the results and decide what to investigate next
    4. Continue exploring until you find meaningful insights
    5. Summarize your findings for the user

    ## Rules
    - Always start by understanding the schema before writing SQL
    - If a query fails, try to fix it and retry
    - When you find something interesting, dig deeper
    - After 3-4 iterations, summarize your findings
    - Output final results as JSON when done
    - Do NOT output your thinking process, only final results
