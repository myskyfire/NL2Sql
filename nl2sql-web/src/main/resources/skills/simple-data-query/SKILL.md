---
name: simple_data_query
description: 简单的数据查询Skill - 演示如何调用Tool获取数据
requiredParams: [datasourceId, sql]
script: SimpleDataQuerySkill.groovy
---

# Simple Data Query Skill

## 用途
这是一个**示例 Skill**，演示如何正确使用 Tool 调用机制。

## 核心原则
✅ **Skill 只负责流程编排，不直接操作数据库**
✅ **通过 callTool() 调用原子能力**
✅ **通过 callSkill() 调用其他 Skill**

## 执行流程
1. 调用 `execute_sql` Tool 获取数据
2. 返回查询结果
