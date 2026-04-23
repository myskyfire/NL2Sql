# 5分反馈机制优化路线图

## 📊 已完成优化（P0+P1）

### ✅ P0: SQL模板缓存
**实施时间**: 2026-04-23  
**修改文件**: 
- `QueryCacheService.java` - 扩展CachedResult增加rating字段
- `SQLFeedbackService.java` - injectSQLTemplateToCache()
- `NL2SQLService.java` - 优先查5分SQL模板

**核心逻辑**:
```
用户评分=5 → 提取SQL + 归一化查询 → 写入Redis(24h TTL)
下次相似查询 → 直接返回SQL模板 → 跳过LLM生成
```

**预期收益**:
- 相似查询从3秒 → 50ms（60倍提升）
- 减少80%的LLM调用
- 保证SQL质量一致性

---

### ✅ P1: 列名翻译缓存
**实施时间**: 2026-04-23  
**修改文件**: 
- `SQLFeedbackService.java` - injectColumnMappingToCache() + extractColumnAliasMapping()

**核心逻辑**:
```
从5分SQL提取: SELECT total_amount AS '订单金额'
→ 缓存 {"total_amount": "订单金额"}
下次遇到相同列名，直接从MetadataCache读取
```

**预期收益**:
- 减少80%的LLM翻译调用
- 保证术语一致性

---

### ✅ P1: 表关联关系缓存
**实施时间**: 2026-04-23  
**修改文件**: 
- `SQLFeedbackService.java` - injectTableRelationshipsToCache() + extractJoinPaths()

**核心逻辑**:
```
从5分SQL提取: JOIN orders ON users.id = orders.user_id
→ 缓存 "users.id -> orders.user_id"
下次相同表组合，直接返回关联路径
```

**预期收益**:
- 跳过LLM关联分析（节省1-2秒）
- 保证关联逻辑正确性

---

## 🎯 P2优化规划（下周实施）

### 1. 同义词库自动学习
**优先级**: P2  
**实施难度**: ⭐⭐⭐ 复杂  
**预期收益**: ⭐⭐⭐

**问题现状**:
- industry_concept表需要手动维护
- 新业务术语无法及时识别

**实施方案**:
```java
// 在SQLFeedbackService.injectTableSelectionToCache()中增强
if (rating >= 4) {
    Map<String, String> synonymPairs = extractSynonyms(question, sql, schema);
    // → {"销售额": "total_amount", "客户": "user_name"}
    
    for (Map.Entry<String, String> entry : synonymPairs.entrySet()) {
        industryConceptDictionary.addOrUpdateConcept(
            entry.getKey(),      // 用户用语
            entry.getValue(),    // 数据库字段
            datasourceId,
            "auto_learned",      // 来源标记
            rating / 5.0         // 置信度
        );
    }
}
```

**关键方法**:
```java
private Map<String, String> extractSynonyms(String question, String sql, SchemaInfo schema) {
    // 1. 从question中提取名词短语（使用HanLP分词）
    List<String> userTerms = extractNounPhrases(question);
    
    // 2. 从sql的AS别名中提取字段中文名
    Map<String, String> columnAliases = extractColumnAliasMapping(sql);
    
    // 3. 匹配用户用语与字段注释
    Map<String, String> synonyms = new HashMap<>();
    for (String term : userTerms) {
        for (Map.Entry<String, String> entry : columnAliases.entrySet()) {
            if (term.contains(entry.getValue()) || entry.getValue().contains(term)) {
                synonyms.put(term, entry.getKey());
            }
        }
    }
    
    return synonyms;
}
```

**依赖组件**:
- HanLP中文分词库
- IndustryConceptDictionary服务

**测试用例**:
1. 用户问"查销售额" → 生成SQL含`total_amount AS '订单金额'` → 评分5星
2. 验证industry_concept表新增记录: {"销售额": "订单金额"}
3. 下次问"销售额" → Prompt注入"销售额=订单金额" → LLM正确识别

**风险点**:
- ⚠️ 误匹配：需设置相似度阈值>0.8
- ⚠️ 冲突处理：同一用户用语对应多个字段时，取最高置信度

---

### 2. 意图分类训练数据收集
**优先级**: P2  
**实施难度**: ⭐⭐⭐ 复杂  
**预期收益**: ⭐⭐⭐

**问题现状**:
- IntentClassifier基于规则，准确率低（~70%）
- 无法识别复杂意图（如"对比上月数据"）

**实施方案**:
```java
// Step 1: 收集5分反馈作为正样本
@EventListener
public void onHighRatingFeedback(SQLFeedbackEvent event) {
    if (event.getRating() == 5) {
        String intent = detectIntent(event.getQuestion());
        trainingDataService.addSample(
            event.getQuestion(), 
            intent, 
            true  // 高质量样本
        );
    }
}

// Step 2: 定期重新训练分类器
@Scheduled(cron = "0 0 2 * * ?") // 每天凌晨2点
public void retrainIntentClassifier() {
    List<TrainingSample> samples = trainingDataService.getHighQualitySamples();
    
    if (samples.size() > 100) { // 至少100个样本才重新训练
        intentClassifier.retrain(samples);
        log.info("意图分类器重新训练完成，样本数={}", samples.size());
    }
}

// Step 3: 意图检测逻辑
private String detectIntent(String question) {
    if (question.contains("对比") || question.contains("比较")) {
        return "COMPARISON";
    } else if (question.contains("趋势") || question.contains("变化")) {
        return "TREND_ANALYSIS";
    } else if (question.contains("排名") || question.contains("最")) {
        return "RANKING";
    } else {
        return "QUERY";
    }
}
```

**数据结构**:
```sql
CREATE TABLE intent_training_samples (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    question TEXT NOT NULL,
    intent VARCHAR(50) NOT NULL,
    is_high_quality BOOLEAN DEFAULT FALSE,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_intent (intent),
    INDEX idx_quality (is_high_quality)
);
```

**测试用例**:
1. 收集100个"对比上月销售额" → 标注为COMPARISON
2. 触发retrainIntentClassifier()
3. 新查询"对比本周和上周" → 正确识别为COMPARISON

**风险点**:
- ⚠️ 冷启动：初期样本少，准确率低
- ⚠️ 类别不平衡：QUERY类型过多，其他类型样本少

---

## 🔮 P3优化规划（未来探索）

### 3. 查询复杂度评估模型
**优先级**: P3  
**实施难度**: ⭐⭐⭐⭐ 复杂  
**预期收益**: ⭐⭐

**问题现状**:
- SQLRiskAnalyzer基于规则，不够精准
- 无法预测实际执行时间

**实施方案**:
```java
// Step 1: 记录5分查询的实际执行时间和复杂度特征
@EventListener
public void onQueryExecuted(QueryExecutionEvent event) {
    if (event.getUserRating() == 5) {
        ComplexityFeature features = extractComplexityFeatures(event.getSql());
        complexityModel.train(features, event.getExecutionTime());
    }
}

// Step 2: 提取复杂度特征
private ComplexityFeature extractComplexityFeatures(String sql) {
    ComplexityFeature feature = new ComplexityFeature();
    
    // 表数量
    feature.setTableCount(countTables(sql));
    
    // JOIN数量
    feature.setJoinCount(countJoins(sql));
    
    // 是否有子查询
    feature.setHasSubquery(sql.toUpperCase().contains("SELECT") && 
                           sql.toUpperCase().indexOf("SELECT") != sql.toUpperCase().lastIndexOf("SELECT"));
    
    // 是否有聚合函数
    feature.setHasAggregation(sql.toUpperCase().matches(".*\\b(SUM|COUNT|AVG|MAX|MIN)\\b.*"));
    
    // WHERE条件数量
    feature.setWhereConditionCount(countWhereConditions(sql));
    
    return feature;
}

// Step 3: 预测新查询的执行时间
public long predictExecutionTime(String sql) {
    ComplexityFeature features = extractComplexityFeatures(sql);
    return complexityModel.predict(features);
}

// Step 4: 智能预警
if (predictedTime > 5000) {
    log.warn("预测执行时间过长({}ms)，建议添加LIMIT或优化索引", predictedTime);
    return suggestOptimization(sql);
}
```

**机器学习模型选择**:
- 简单场景：线性回归（Linear Regression）
- 复杂场景：随机森林（Random Forest）
- 库选择：Smile ML（Java原生）或 DJL（Deep Java Library）

**测试用例**:
1. 收集1000个5分查询的执行时间
2. 训练模型，验证准确率>80%
3. 新查询"统计所有订单" → 预测10秒 → 自动添加LIMIT 1000

**风险点**:
- ⚠️ 数据量要求：至少1000个样本才能训练可靠模型
- ⚠️ 硬件差异：不同服务器执行时间差异大，需归一化

---

## 📈 效果监控指标

### 关键指标
| 指标 | 当前值 | 目标值 | 监控方式 |
|------|--------|--------|----------|
| SQL模板缓存命中率 | 0% | 30%+ | Redis监控 |
| 列名翻译缓存命中率 | 0% | 80%+ | 日志统计 |
| 表关联缓存命中率 | 0% | 50%+ | 日志统计 |
| 平均响应时间 | 3-5秒 | <1秒 | APM监控 |
| LLM调用次数/天 | ~1000 | <200 | 日志统计 |
| 用户满意度(平均分) | 3.5 | 4.2+ | rag_feedback表 |

### 监控看板
```sql
-- 每日缓存命中统计
SELECT 
    DATE(created_at) as date,
    COUNT(CASE WHEN user_rating = 5 THEN 1 END) as high_rating_count,
    AVG(execution_time_ms) as avg_response_time
FROM nl2sql_query_log
WHERE created_at >= DATE_SUB(NOW(), INTERVAL 7 DAY)
GROUP BY DATE(created_at)
ORDER BY date;

-- 缓存命中率
SELECT 
    COUNT(DISTINCT normalized_query) as unique_queries,
    COUNT(CASE WHEN cached_at IS NOT NULL THEN 1 END) as cached_count,
    ROUND(COUNT(CASE WHEN cached_at IS NOT NULL THEN 1 END) * 100.0 / COUNT(*), 2) as hit_rate
FROM query_cache_stats;
```

---

## 🚀 实施时间表

| 阶段 | 时间 | 任务 | 负责人 |
|------|------|------|--------|
| **P0完成** | 2026-04-23 | SQL模板缓存 | AI助手 |
| **P1完成** | 2026-04-23 | 列名映射 + 表关联缓存 | AI助手 |
| **P2-1** | 2026-04-24~26 | 同义词库自动学习 | 待定 |
| **P2-2** | 2026-04-27~30 | 意图分类训练 | 待定 |
| **P3** | 2026-05月 | 复杂度评估模型 | 待定 |
| **效果评估** | 2026-05-07 | 数据分析 + 调优 | 待定 |

---

## 💡 注意事项

### 1. 缓存失效策略
- **TTL设置**: SQL模板24小时，列名映射7天，表关联3天
- **主动失效**: 表结构变更时清除相关缓存
- **低频清理**: 每周清理命中率<10%的缓存项

### 2. 数据一致性
- **Schema变更检测**: 监听DDL事件，自动失效缓存
- **版本控制**: 缓存key包含schema版本号
- **灰度发布**: 新缓存策略先对小部分用户生效

### 3. 性能优化
- **异步写入**: 反馈注入缓存不阻塞主流程
- **批量更新**: 累积100条反馈后批量写入
- **压缩存储**: Redis使用Snappy压缩JSON

### 4. 安全考虑
- **SQL注入防护**: 缓存的SQL仍需经过SQLValidationService校验
- **权限控制**: 不同用户只能访问自己有权限的表
- **审计日志**: 记录所有缓存命中/未命中事件

---

## 📝 后续优化方向

1. **跨用户共享**: 将个人5分反馈升级为全局知识库
2. **A/B测试**: 对比有缓存vs无缓存的用户满意度
3. **自动化运维**: 缓存命中率低于阈值时自动告警
4. **智能预热**: 根据历史数据预测高频查询，提前加载缓存
