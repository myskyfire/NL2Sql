---
name: generate_report_with_insights
displayName: 报表与洞察技能
description: 在标准查询基础上，增加AI智能总结和图表推荐，生成完整的分析报告。适用于用户需要深度分析、趋势洞察或综合报告的场景。
category: analysis
priority: 2
version: "1.0"
script: scripts/ReportWithInsightsSkill.groovy
requiredParams: [question, datasourceId]
---

# 报表与洞察技能（Report with Insights Skill）

## 功能说明

高级分析技能，在标准查询基础上自动增加：
- AI 智能总结：提炼关键数据点和趋势
- 图表推荐：根据数据特征推荐合适的可视化类型（bar/line/pie 等）
- 一站式生成完整分析报告

## 适用场景

✅ **应该使用此 Skill**：
- 趋势分析："分析近3个月销售趋势并给出建议"
- 对比分析："对比各产品线的业绩表现"
- 综合报告："生成上月经营分析报告"
- 深度洞察："分析用户增长趋势和留存率"
- 用户明确要求"分析"、"总结"、"报告"、"趋势"等关键词

❌ **不应该使用此 Skill**：
- 简单查询场景 → 使用 `execute_standard_query`，避免不必要的 AI 调用开销
- 用户只需要原始数据，不需要分析和总结
- 实时性要求极高的场景（因为多了 AI 总结步骤）

## 参数说明

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| question | string | ✅ | 用户的分析需求 |
| datasourceId | long | ✅ | 数据源ID |
| userId | long | ✅ | 用户ID |
| username | string | ✅ | 用户名 |

## 内部工作流程

```
用户问题
  ↓
Step 1: 调用 StandardQuerySkill 执行查询
  ↓
Step 2: 如果数据量 > 5 行，生成 AI 总结 (AISummaryTool)
  - 提炼关键数据点
  - 识别趋势和异常
  - 生成自然语言总结
  ↓
Step 3: 推荐合适的图表类型 (ChartRecommendationTool)
  - 分析数据特征（时间序列、分类对比、占比等）
  - 推荐 bar/line/pie/scatter 等图表类型
  ↓
返回完整报告（数据 + AI总结 + 图表建议）
```

## 返回结果

成功时返回 JSON 格式：
```json
{
  "status": "success",
  "data": [],
  "rowCount": 50,
  "executionTime": 250.8,
  "sql": "SELECT ...",
  "aiSummary": "上月销售额环比增长15%，其中华东地区贡献最大...",
  "chartRecommendations": [
    {
      "type": "line",
      "reason": "时间序列数据，适合展示趋势"
    },
    {
      "type": "bar",
      "reason": "分类对比，适合展示各地区差异"
    }
  ]
}
```

失败时返回：
```json
{
  "status": "error",
  "error": "错误描述"
}
```

## 示例

### 示例 1：趋势分析
**用户**：分析上月销售趋势并给出建议  
**Skill 调用**：`generate_report_with_insights("分析上月销售趋势并给出建议", 1, 123, "user")`

### 示例 2：对比分析
**用户**：对比各产品线的业绩表现  
**Skill 调用**：`generate_report_with_insights("对比各产品线的业绩表现", 1, 123, "user")`

### 示例 3：综合报告
**用户**：生成上月经营分析报告  
**Skill 调用**：`generate_report_with_insights("生成上月经营分析报告", 1, 123, "user")`

## 与 StandardQuerySkill 的区别

| 维度 | StandardQuerySkill | ReportWithInsightsSkill |
|------|-------------------|------------------------|
| **核心功能** | 数据查询 | 查询 + AI总结 + 图表推荐 |
| **适用场景** | 简单查询、统计 | 深度分析、趋势洞察 |
| **执行速度** | 快 | 较慢（多了AI调用） |
| **返回内容** | 原始数据 | 数据 + 总结 + 图表建议 |
| **Token 消耗** | 低 | 高 |

## 注意事项

⚠️ **重要提示**：
- 仅在用户明确要求分析、总结、报告时使用，避免不必要的 AI 开销
- AI 总结会在数据量 > 5 行时自动生成，小数据集可能跳过
- 图表推荐基于数据特征自动判断，不保证 100% 准确
- 直接将完整报告用友好的中文回复给用户
- 不要展示内部的 Thought/Action/Observation 过程
