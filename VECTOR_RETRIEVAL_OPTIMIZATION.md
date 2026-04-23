# 向量检索优化方案

## 概述

本文档记录NL2SQL系统向量检索的优化方向和实施方案,用于持续提升表召回准确率。

---

## 已实施方案

### 1. bge-m3模型切换 ✅

**实施时间**: 2026-04-22  
**文件**: `VectorRetriever.java`

**改动**:
- 从All-MiniLM-L6-v2(384维)切换到bge-m3(1024维)
- bge-m3是BAAI多语言模型,中文SOTA表现
- 通过Ollama调用,配置项: `ollama.embedding-model=bge-m3`

**效果**:
- 中文语义理解能力提升
- 向量维度从384提升到1024,表达能力更强

---

### 2. 动态阈值调整 ✅

**实施时间**: 2026-04-22  
**文件**: `VectorRetriever.calculateDynamicThreshold()`

**实现逻辑**:
```java
基础阈值: 0.45

调整规则:
1. 查询长度 ≤ 5字: -0.10 → 0.35 (短查询容忍度高)
2. 查询长度 > 15字: +0.10 → 0.55 (长查询更严格)
3. 最高相似度 > 0.7: -0.05 (匹配明确,放宽召回)
4. 最高相似度 < 0.4: +0.10 (匹配不明确,严格过滤)

最终范围: [0.30, 0.60]
```

**优势**:
- 自适应不同查询场景
- 避免固定阈值导致的误杀或漏召

---

### 3. Schema链接扩展 ✅

**实施时间**: 2026-04-22  
**文件**: `VectorRetriever.expandWithRelatedTables()`

**实现逻辑**:
- 复用现有服务: `TableRelationshipService.getRelationshipsByTable()`
- 对每个召回表,查询其外键关联表(双向)
- 自动扩展: orders → users, order_items等

**降级策略**:
- 如果`TableRelationshipService`不可用,返回原始结果
- 不影响核心向量检索功能

**优势**:
- 解决JOIN查询遗漏关联表问题
- 利用已有的表关系发现机制(真实外键+模式匹配+LLM推断)

---

## 待实施方案

### 4. 混合检索: 向量 + BM25关键词融合 ⭐⭐⭐⭐⭐

**优先级**: P0 (高)  
**预计工作量**: 2-3小时

**原理**:
- 向量检索: 捕捉语义相似度
- BM25关键词: 保证精确匹配(表名、字段名)
- RRF(Reciprocal Rank Fusion)融合排序

**技术方案**:

#### 方案A: Apache Lucene (推荐)
```xml
<!-- pom.xml -->
<dependency>
    <groupId>org.apache.lucene</groupId>
    <artifactId>lucene-core</artifactId>
    <version>9.8.0</version>
</dependency>
```

**实现步骤**:
1. 应用启动时构建Lucene索引(表名+注释+字段)
2. 查询时同时调用向量检索和BM25
3. RRF融合: `finalScore = 1/(rank_vector + k) + 1/(rank_bm25 + k)`
4. 按融合得分排序返回Top-K

**优势**:
- Lucene工业级实现,稳定可靠
- 支持中文分词(配合IK Analyzer)
- 已有成熟API,无需自己实现BM25算法

#### 方案B: 简化版TF-IDF (快速实施)
如果不想引入Lucene,可以用简化版:
```java
// 1. 对表名/注释分词
// 2. 计算查询词与表的Jaccard相似度
// 3. 与向量得分加权融合
double finalScore = 0.7 * vectorScore + 0.3 * keywordScore;
```

**预期效果**:
- 召回准确率提升20-30%
- 特别改善"订单"→orders这类精确匹配场景

---

### 5. 查询意图分类驱动召回 ⭐⭐⭐

**优先级**: P1 (中)  
**预计工作量**: 4-6小时

**原理**:
识别查询类型,针对性调整召回策略:
- **AGGREGATE**(统计): 优先召回含聚合字段的表
- **DETAIL**(明细): 严格阈值,精准召回
- **JOIN**(关联): 放宽阈值,召回更多候选表

**实现步骤**:
1. 新增`QueryIntentClassifier`组件
2. 基于规则或轻量模型分类
3. 根据意图类型调整:
   - 阈值参数
   - Top-K数量
   - 是否启用Schema链接

**示例**:
```java
String intent = classifyIntent(query);
if (intent.equals("JOIN")) {
    threshold = 0.35; // 放宽
    topK = 15;        // 多召回
} else if (intent.equals("AGGREGATE")) {
    threshold = 0.50; // 严格
    topK = 8;         // 少而精
}
```

**预期效果**:
- 不同查询场景针对性优化
- 减少无关表召回

---

### 6. 用户反馈学习 ⭐⭐⭐

**优先级**: P2 (长期优化)  
**预计工作量**: 6-8小时

**现状**:
- 已有`FeedbackLearningService`基础
- 但未与向量检索集成

**实现方案**:
1. 记录低分查询的实际使用表
2. 分析向量得分与实际选择的偏差
3. 自动调整:
   - 特定表的关键词权重
   - 同义词映射关系
4. 定期重新训练向量索引

**数据源**:
- 用户评分(rating < 3)
- SQL执行日志
- 手动修正记录

**预期效果**:
- 持续优化,越用越准
- 适应特定业务场景

---

## 实施建议

### 短期(1-2周)
1. ✅ 已完成: bge-m3 + 动态阈值 + Schema链接
2. 🔄 测试当前效果,收集bad case
3. 📊 如果准确率仍<80%,实施BM25混合检索

### 中期(1-2月)
4. 实施查询意图分类
5. 完善用户反馈学习闭环

### 长期(持续)
6. 监控召回准确率指标
7. 定期review bad case,迭代优化

---

## 关键指标

**监控指标**:
- 表召回准确率(Top-5命中率)
- 平均召回表数量
- 向量检索耗时(P95)
- 缓存命中率(L1/L2/L3)

**目标值**:
- Top-5准确率: ≥85%
- 平均召回表数: 3-5个
- P95耗时: <500ms
- L1缓存命中率: ≥40%

---

## 注意事项

### 通用产品规范
⚠️ **严禁在通用代码中硬编码业务名词**

错误示例:
```java
// ❌ 禁止: 硬编码电商行业同义词
synonymMap.put("orders", "订单 订货 购买 交易");
```

正确做法:
- 通过`industry_concept`表配置业务同义词
- 通过`IndustryConceptExtension`扩展点实现行业特定逻辑
- 向量检索作为通用组件,只负责算法逻辑

### 性能优化
- BM25索引构建放在异步任务,避免阻塞启动
- 缓存策略: Lucene索引可以内存缓存,定期刷新
- 降级策略: BM25失败时降级为纯向量检索

---

## 参考资料

- BGE-m3模型: https://github.com/FlagOpen/FlagEmbedding
- BM25算法: https://en.wikipedia.org/wiki/Okapi_BM25
- Lucene文档: https://lucene.apache.org/core/
- RRF融合: https://plg.uwaterloo.ca/~gvcormac/cormacksigir09-rrf.pdf

---

**最后更新**: 2026-04-22  
**维护人**: NL2SQL团队
