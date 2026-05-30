---
name: hybrid_example_query
displayName: 混合模式示例
description: 演示Workflow与Groovy混合使用：主流程用YAML编排，复杂逻辑调用Groovy脚本。
category: example
priority: 3
version: "1.0"
requiredParams: [question, datasourceId]
workflow:
  steps:
    - id: retrieve_schema
      action: call_tool
      tool: retrieve_table_schema
      input:
        question: "{{question}}"
        datasourceId: "{{datasourceId}}"
      output_var: schemaResult
    
    - id: smart_select_tables
      action: call_groovy
      script: SmartTableSelector.groovy
      input:
        question: "{{question}}"
        schema: "{{schemaResult}}"
        datasourceId: "{{datasourceId}}"
      output_var: selectedTables
    
    - id: generate_sql
      action: call_tool
      tool: generate_sql_from_schema
      input:
        question: "{{question}}"
        schema: "{{selectedTables.schema}}"
        datasourceId: "{{datasourceId}}"
      output_var: generatedSql
    
    - id: validate_and_correct
      action: call_groovy
      script: SQLCorrector.groovy
      input:
        sql: "{{generatedSql.sql}}"
        question: "{{question}}"
        datasourceId: "{{datasourceId}}"
        max_retries: 2
      output_var: correctedSql
    
    - id: respond
      action: respond
      output:
        success: true
        sql: "{{correctedSql.sql}}"
        wasModified: "{{correctedSql.wasModified}}"
        originalSql: "{{generatedSql.sql}}"
---

# 混合模式示例（Hybrid Example）

## 功能说明

演示如何将 **Workflow 声明式编排** 与 **Groovy 脚本灵活逻辑** 结合：

1. **Workflow 负责**：主流程编排、变量传递、条件分支
2. **Groovy 负责**：复杂算法、LLM 调用、循环修正

## 适用场景

- 学习混合模式的最佳实践
- 需要部分智能决策的查询流程
- 希望流程清晰可见但保留灵活性

## 不适用场景

- 纯简单查询 → 使用原子 Tool
- 高度复杂的业务逻辑 → 使用纯 Groovy Skill

## 参数

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| question | string | ✅ | 自然语言问题 |
| datasourceId | long | ✅ | 数据源ID |

## 返回

```json
{
  "success": true,
  "sql": "SELECT ...",
  "wasModified": false,
  "originalSql": "SELECT ..."
}
```

## 架构优势

✅ **流程清晰**：YAML 一目了然看到执行步骤  
✅ **灵活扩展**：Groovy 处理复杂逻辑（智能选表、自动修正）  
✅ **易于维护**：修改流程只需改 YAML，无需重新编译  
✅ **可复用**：Groovy 脚本可在多个 Workflow 中共享
