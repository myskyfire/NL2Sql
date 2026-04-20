---
name: sql_validate_execute
description: SQL验证与执行技能。先验证SQL语法和安全性，再执行查询。演示完整的Tool调用链。
version: 1.0.0
author: NL2SQL Team
---

# SQL验证与执行 Skill

## 概述

这个Skill演示了如何正确使用新架构中的Tool调用链：
1. 先调用 `validate_sql` Tool 验证SQL
2. 验证通过后，调用 `execute_sql` Tool 执行查询
3. 返回包含验证和执行结果的完整信息

## 使用场景

- 需要确保SQL安全性的场景
- 演示多Tool串联调用的最佳实践
- 学习新架构的示例代码

## 输入参数

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| sql | String | ✅ | SQL查询语句 |
| datasourceId | Long | ✅ | 数据源ID |

## 输出结果

### 成功响应
```json
{
  "status": "success",
  "validation": {
    "valid": true,
    "riskLevel": "LOW",
    "warnings": []
  },
  "execution": {
    "rowCount": 10,
    "columns": ["id", "name"],
    "data": [...],
    "executionTime": 45
  },
  "sql": "SELECT * FROM users",
  "datasourceId": 1
}
```

### 验证失败响应
```json
{
  "status": "validation_failed",
  "message": "SQL验证失败",
  "error": "只允许执行SELECT查询",
  "riskLevel": "HIGH",
  "suggestions": ["请使用SELECT语句"],
  "datasourceId": 1
}
```

## 架构特点

✅ **纯编排逻辑**：不直接操作数据库  
✅ **Tool调用链**：validate_sql → execute_sql  
✅ **错误处理**：区分验证失败和执行失败  
✅ **完整日志**：每个步骤都有清晰的日志输出  

## 与其他Skill对比

| Skill | 复杂度 | Tool调用 | 适用场景 |
|-------|--------|----------|----------|
| simple-data-query | 简单 | 1个 (execute_sql) | 快速查询 |
| **sql-validate-execute** | 中等 | 2个 (validate_sql + execute_sql) | 安全查询 |
| standard-query | 复杂 | 多个Bean（待迁移） | 完整NL2SQL流程 |
| report-with-insights | 中等 | callSkill (standard_query) | 报表生成 |
