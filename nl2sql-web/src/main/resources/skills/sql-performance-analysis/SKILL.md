---
name: sql_performance_analysis
description: SQL性能分析技能。综合分析执行计划、索引使用、查询成本，给出优化建议。演示多Tool协同工作。
version: 1.0.0
author: NL2SQL Team
requiredParams: [sql, datasourceId]
script: SQLPerformanceAnalysisSkill.groovy
---

# SQL性能分析 Skill

## 概述

这个Skill演示了如何协调多个Tool进行综合性能分析：
1. `validate_sql` - 验证SQL语法和安全性
2. `analyze_query_plan` - 分析EXPLAIN执行计划
3. `check_index` - 检查索引使用情况
4. `estimate_cost` - 估算查询成本
5. 综合分析生成优化建议

## 使用场景

- SQL性能调优前的全面诊断
- 学习多Tool协同工作的最佳实践
- 自动化SQL审查流程

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
  "sql": "SELECT * FROM orders WHERE user_id = 100",
  "datasourceId": 1,
  "analysis": {
    "validation": {
      "valid": true,
      "riskLevel": "LOW"
    },
    "explainPlan": {
      "riskLevel": "MEDIUM",
      "risks": ["全表扫描（type=ALL）"],
      "suggestions": ["考虑添加索引或使用更精确的WHERE条件"]
    },
    "indexCheck": {
      "missingIndexes": [
        {"table": "orders", "column": "user_id"}
      ]
    },
    "costEstimate": {
      "estimatedCost": 1250,
      "estimatedRows": 50000
    },
    "suggestions": [
      "⚠️ 检测到全表扫描，建议添加合适的索引",
      "💡 建议添加索引: orders.user_id",
      "⚠️ 查询成本较高 (1250)，考虑优化查询逻辑"
    ]
  },
  "summary": {
    "overallRisk": "MEDIUM",
    "suggestionCount": 3,
    "recommendations": [...]
  }
}
```

## 架构特点

✅ **多Tool协同**：4个Tool串联调用  
✅ **综合分析**：整合多个维度的分析结果  
✅ **智能建议**：基于分析结果生成优化建议  
✅ **风险评估**：计算整体风险等级  

## Tool调用链

```
validate_sql
    ↓
analyze_query_plan
    ↓
check_index
    ↓
estimate_cost
    ↓
综合分析 → 生成建议
```

## 与其他Skill对比

| Skill | Tool数量 | 复杂度 | 核心价值 |
|-------|----------|--------|----------|
| simple-data-query | 1 | ⭐ | 快速查询 |
| sql-validate-execute | 2 | ⭐⭐ | 安全查询 |
| **sql-performance-analysis** | 4 | ⭐⭐⭐ | 性能诊断 |
| standard-query | 多个Bean | ⭐⭐⭐⭐⭐ | 完整NL2SQL（待迁移） |
