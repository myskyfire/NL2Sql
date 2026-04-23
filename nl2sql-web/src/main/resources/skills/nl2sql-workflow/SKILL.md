---
name: nl2sql_workflow
displayName: NL2SQL工作流
description: 完整的NL2SQL流程编排，包括表检索、智能选表、SQL生成、验证修正等步骤
category: query
priority: 1
version: "1.0"
workflow:
  steps:
    - id: retrieve_schema
      action: call_tool
      tool: schema_retriever
      input:
        query: "{{question}}"
        datasourceId: "{{datasourceId}}"
      output_var: schemaResult
    
    - id: select_tables
      action: call_tool
      tool: table_selector
      input:
        query: "{{question}}"
        schemaInfo: "{{schemaResult}}"
        datasourceId: "{{datasourceId}}"
      output_var: selectedTables
    
    - id: generate_sql
      action: call_tool
      tool: sql_generator
      input:
        query: "{{question}}"
        tables: "{{selectedTables}}"
        datasourceId: "{{datasourceId}}"
        schemaInfo: "{{schemaResult}}"
      output_var: generatedSql
    
    - id: validate_sql
      action: call_tool
      tool: sql_validator
      input:
        sql: "{{generatedSql}}"
        question: "{{question}}"
        datasourceId: "{{datasourceId}}"
        schemaInfo: "{{schemaResult}}"
      output_var: validatedSql
    
    - id: respond
      action: respond
      output:
        success: true
        sql: "{{validatedSql.correctedSql}}"
        wasModified: "{{validatedSql.wasModified}}"
        originalSql: "{{validatedSql.originalSql}}"
---

# NL2SQL工作流（声明式编排）

## 功能说明

通过声明式workflow编排完整的NL2SQL流程：
1. **表结构检索** - 基于向量检索相关表
2. **智能选表** - LLM分析选择最相关的表
3. **SQL生成** - 基于选定的表生成SQL
4. **SQL验证** - 白名单校验与自动修正

## 参数说明

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| question | string | ✅ | 用户自然语言问题 |
| datasourceId | long | ✅ | 数据源ID |

## 返回结果

```json
{
  "success": true,
  "sql": "SELECT ...",
  "wasModified": false,
  "originalSql": "SELECT ..."
}
```
