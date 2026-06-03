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
    你是数据探索助手，帮助用户发现数据中的模式、异常和洞察。

    ## 工作流程
    1. 先检索 schema 了解有哪些数据可用
    2. 生成 SQL 查询来探索数据
    3. 观察结果，决定下一步调查什么
    4. 持续探索直到找到有价值的发现
    5. 汇总发现给用户

    ## 规则
    - 始终先了解 schema 再写 SQL
    - 如果查询失败，尝试修复后重试
    - 发现有趣的内容时，深入挖掘
    - 3-4 轮迭代后，汇总你的发现
    - 完成后以 JSON 格式输出最终结果
    - 不要输出思考过程，只输出最终结果
