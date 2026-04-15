---
name: execute_standard_query
displayName: 标准查询技能
description: 执行完整的数据查询流程，包括表结构检索、SQL生成、风险评估、执行和自动修正。适用于用户有明确数据查询需求的场景。
category: query
priority: 1
version: "1.0"
script: scripts/StandardQuerySkill.groovy
requiredParams: [question, datasourceId]
---

# 标准查询技能（Standard Query Skill）

## 功能说明

封装完整的查询生命周期，自动处理以下所有步骤：
- 智能检索相关表结构
- 自动生成 SQL 语句
- LLM 自主评估 SQL 风险（LOW/MEDIUM/HIGH/UNCERTAIN）
- 执行 SQL 查询（支持自动修正，最多重试 2 次）
- 高风险 SQL 自动阻断，保护数据库性能

## 适用场景

✅ **应该使用此 Skill**：
- 简单数据查询："查询最近10条订单"
- 统计分析："统计上月各地区销售额"
- 筛选过滤："找出消费超过1000元的用户"
- 排序查询："查看产品销量排行榜"
- 分组聚合："按地区统计用户数量"

❌ **不应该使用此 Skill**：
- 需要深度分析和洞察 → 使用 `generate_report_with_insights`
- 需要分步调试 SQL → 手动调用底层 Tools（retrieve_schema, generate_sql, execute_sql）
- 用户明确要求"分析"、"总结"、"报告"等关键词

## 参数说明

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| question | string | ✅ | 用户的自然语言查询问题 |
| datasourceId | long | ✅ | 数据源ID |
| userId | long | ✅ | 用户ID |
| username | string | ✅ | 用户名 |

## 内部工作流程

```
用户问题
  ↓
Step 1: 检索表结构 (NL2SQLTool.retrieveSchema)
  ↓
Step 2: 生成 SQL (NL2SQLTool.generateSQL)
  ↓
Step 2.5: SQL 优化与风险评估
  - 检测 IN 子查询关联问题
  - LLM 自主评估风险等级
  - 必要时调用 EXPLAIN 辅助分析
  ↓
Step 3: 执行 SQL (SQLExecutionTool.executeSQL)
  - 支持自动修正（最多重试 2 次）
  - 失败时调用 NL2SQLTool.autoFixSQL
  ↓
返回查询结果
```

## 风险评估机制

Skill 内置三层风险评估：

1. **LLM 自主评估**：基于 SQL 复杂度、JOIN 数量、子查询等因素
2. **EXPLAIN 辅助**：当 LLM 不确定时，调用数据库 EXPLAIN 分析
3. **风险分级**：
   - LOW：直接执行
   - MEDIUM：继续执行但标记警告
   - HIGH：阻断执行，返回错误信息
   - UNCERTAIN：调用 EXPLAIN 进一步分析

## 返回结果

成功时返回 JSON 格式：
```json
{
  "status": "success",
  "data": [...],
  "rowCount": 10,
  "executionTime": 125.5,
  "sql": "SELECT ..."
}
```

失败时返回：
```json
{
  "status": "error",
  "error": "错误描述"
}
```

需要澄清时返回：
```json
{
  "status": "clarification_needed",
  "message": "澄清消息"
}
```

## 示例

### 示例 1：简单查询
**用户**：查询最近10条订单  
**Skill 调用**：`execute_standard_query("查询最近10条订单", 1, 123, "user")`

### 示例 2：统计分析
**用户**：统计上月各地区销售额  
**Skill 调用**：`execute_standard_query("统计上月各地区销售额", 1, 123, "user")`

### 示例 3：筛选过滤
**用户**：找出消费超过1000元的用户  
**Skill 调用**：`execute_standard_query("找出消费超过1000元的用户", 1, 123, "user")`

## 注意事项

⚠️ **重要提示**：
- 优先使用此 Skill，它封装了完整的错误处理和重试机制
- 不要在 SystemMessage 中展示 Thought/Action/Observation 等内部思考过程
- 直接将查询结果用友好的中文回复给用户
- 如果 SQL 执行失败，Skill 会自动尝试修正，无需人工干预
