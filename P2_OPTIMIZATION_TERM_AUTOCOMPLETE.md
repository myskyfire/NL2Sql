# P2优化方案：术语库自动补全功能

## 📋 方案概述

在NL2SQL查询输入框增加**术语自动补全**功能，类似百度搜索的智能提示，引导用户输入标准化业务术语。

---

## 🎯 核心价值

### 1. **提升SQL生成准确率**
- ✅ 用户输入标准化术语 → LLM无需猜测"订单/定单/单子"
- ✅ 减少同义词扩展的复杂度
- ✅ 降低LLM幻觉风险，提高字段匹配精度

### 2. **改善用户体验**
- ✅ 自动补全引导用户输入规范术语
- ✅ 减少拼写错误和歧义表达
- ✅ 降低业务人员学习成本

### 3. **技术实现简单**
- ✅ 复用现有`industry_concept`表 + `concept_relation`表
- ✅ 前端Input组件 + 防抖查询 + 下拉列表
- ✅ 后端术语建议API（支持模糊匹配）

---

## 🏗️ 技术方案

### **术语库设计**

利用现有数据库结构，无需新增表：

```sql
-- 查询术语建议（示例）
SELECT DISTINCT cr.synonym AS term
FROM industry_concept ic
JOIN concept_relation cr ON ic.id = cr.concept_id
WHERE ic.industry_code = 'ecommerce'
  AND (cr.synonym LIKE '%订%' OR ic.concept_key LIKE '%订%')
ORDER BY cr.usage_count DESC
LIMIT 10;
```

**数据源**:
- `industry_concept`: 行业概念主表（GMV、DAU等）
- `concept_relation`: 同义词关系表（订单=定单=单子）
- `usage_count`: 术语使用频率（用于排序优化）

---

### **前端交互流程**

```
用户输入: "订" 
    ↓
[防抖300ms] 调用 /api/terms/suggest?prefix=订&industry=ecommerce
    ↓
返回: ["订单", "订单总额", "订单数量", "订单状态"]
    ↓
显示下拉列表，用户选择"订单总额"
    ↓
自动填充到输入框，继续输入后续内容
```

**关键特性**:
- **防抖处理**: 300ms延迟避免频繁请求
- **键盘导航**: ↑↓键选择，Enter确认
- **鼠标点击**: 直接选择术语
- **高亮匹配**: 已输入的字符高亮显示

---

### **后端API设计**

```java
@RestController
@RequestMapping("/api/terms")
public class TermSuggestionController {
    
    @Autowired
    private IndustryConceptService conceptService;
    
    /**
     * 术语智能建议
     * @param prefix 输入前缀
     * @param industry 行业代码（可选）
     * @param datasourceId 数据源ID（可选，用于过滤该数据源的术语）
     */
    @GetMapping("/suggest")
    public Result<List<String>> suggestTerms(
        @RequestParam String prefix,
        @RequestParam(required = false) String industry,
        @RequestParam(required = false) Long datasourceId
    ) {
        List<String> suggestions = conceptService.getSuggestions(
            prefix, industry, datasourceId
        );
        return Result.success(suggestions);
    }
}
```

**Service层逻辑**:
```java
@Service
public class IndustryConceptService {
    
    public List<String> getSuggestions(String prefix, String industry, Long datasourceId) {
        // 1. 从industry_concept + concept_relation检索
        // 2. 支持拼音首字母匹配（可选增强）
        // 3. 按使用频率排序（usage_count DESC）
        // 4. 限制返回10条
        return conceptMapper.findSuggestions(prefix, industry, datasourceId, 10);
    }
}
```

---

## 🔧 与现有架构整合

### **已有基础**
- ✅ `industry_concept`表存储术语
- ✅ `concept_relation`表存储同义词
- ✅ `SynonymService`已实现术语替换逻辑

### **新增内容**
1. **术语建议API** (`TermSuggestionController`)
2. **前端自动补全组件** (`TermAutocomplete.vue/js`)
3. **术语使用统计** (更新`usage_count`字段)

---

## ⚠️ 设计原则

### 1. **辅助而非强制**
- ✅ 术语补全作为**可选功能**，不是强制约束
- ✅ 用户仍可自由输入自然语言
- ❌ 不要禁止非术语输入

### 2. **智能混合策略**
```
用户输入: "查询最近7天北京的订单总额"
    ↓
[前端识别] "订单总额" 命中术语库 → 高亮显示
    ↓
[后端处理] 保持原样传给LLM（术语已在上下文中）
```

### 3. **性能优化**
- 前端缓存热门术语（LocalStorage，TTL 1小时）
- 后端Redis缓存术语列表（TTL 1小时）
- 防抖300ms避免频繁请求

---

## 🚀 增强功能（可选）

### **分级提示策略**

| 术语类型 | 提示方式 | 示例 |
|---------|---------|------|
| **强术语** | 必须选择 | GMV、DAU、ARPU |
| **弱术语** | 可选补全 | 订单、用户、商品 |
| **自由输入** | 无提示 | "最近7天"、"北京" |

### **上下文感知推荐**
```javascript
// 根据已输入内容动态调整建议
if (input.includes("订单")) {
  suggest(["总额", "数量", "状态", "时间"]);  // 订单相关属性
} else if (input.includes("用户")) {
  suggest(["数量", "地域", "年龄", "性别"]);  // 用户相关属性
}
```

---

## 📊 预期效果

### **量化指标**
- SQL生成准确率提升: **5-10%**
- 用户输入时间减少: **20-30%**
- 术语相关错误率降低: **50%+**

### **用户体验**
- 业务人员无需记忆复杂术语
- 减少"为什么识别不了这个词"的困惑
- 提升系统专业感和可信度

---

## 📝 实施计划

### **Phase 1: 基础功能** (2-3天)
- [ ] 后端术语建议API开发
- [ ] 前端自动补全组件开发
- [ ] 集成到查询输入框

### **Phase 2: 性能优化** (1天)
- [ ] Redis缓存术语列表
- [ ] 前端LocalStorage缓存
- [ ] 防抖和节流优化

### **Phase 3: 增强功能** (可选，2-3天)
- [ ] 拼音首字母匹配
- [ ] 上下文感知推荐
- [ ] 术语使用统计收集

---

## 🎯 优先级评估

| 维度 | 评分 | 说明 |
|------|------|------|
| **业务价值** | ⭐⭐⭐⭐ | 显著提升准确率和用户体验 |
| **技术难度** | ⭐⭐ | 现有架构基础上小幅扩展 |
| **开发成本** | ⭐⭐ | 3-5人天即可完成 |
| **维护成本** | ⭐ | 复用现有术语库，几乎零维护 |

**综合评级**: **P2**（高价值、低成本、快速见效）

---

## 💡 总结

术语库自动补全是**投入产出比极高**的优化方案：
- ✅ 利用现有数据，无需重构
- ✅ 技术成熟，风险可控
- ✅ 用户感知明显，满意度提升
- ✅ 为后续AI训练提供高质量标注数据

**建议立即纳入P2优化计划**。
