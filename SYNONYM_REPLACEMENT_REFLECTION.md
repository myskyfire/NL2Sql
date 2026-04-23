# NL2SQL同义词替换陷阱：从硬编码到LLM原生理解的架构演进

## 问题背景

在NL2SQL系统中，用户输入自然语言查询"查询2月2号当天的订单金额"时，系统错误地将其改写为"查询2月2号今天的订单金额"，导致生成的SQL出现逻辑冲突：

```sql
-- 错误的SQL
WHERE DATE(created_at) = '2026-02-02' AND created_at >= CURDATE()
-- 具体日期 + 系统当前时间 = 永远查不到数据
```

## 根本原因分析

### 1. 硬编码同义词映射的语义盲区

最初的`SynonymService`采用了简单的键值对映射：

```java
synonymMap.put("今天", Arrays.asList("今日", "当天", "today"));
synonymMap.put("金额", Arrays.asList("钱", "费用", "价格"));
synonymMap.put("用户", Arrays.asList("客户", "会员"));
```

**问题本质**：这种映射完全忽略了上下文语义。

- "当天"在"2月2号当天"中指代具体日期
- "当天"在"查询当天的订单"中指代今天
- 硬编码替换无法区分这两种场景

### 2. 阻断规则引擎的无限膨胀

尝试通过添加条件判断来解决：

```java
if (containsSpecificDate(query) && isTimeRelatedTerm(standardTerm)) {
    return true; // 阻断替换
}
```

**新问题**：
- 规则1: 具体日期 + 时间词 → 阻断
- 规则2: "价格"→"金额"在商品查询中阻断
- 规则3: "城市"/"省份"→"地区"粒度冲突阻断
- 规则4: "客户"→"用户"B2B语义差异阻断
- 规则5: "产品"→"商品"行业差异阻断
- ...规则会无限增长

**维护成本**：每发现一个新场景就要加一条规则，最终变成难以维护的规则迷宫。

### 3. 数据库配置化的局限性

考虑将同义词存入`industry_concept`表：

```sql
INSERT INTO industry_concept (concept_key, concept_aliases) 
VALUES ('今天', '["今日", "当天", "today"]');
```

**核心矛盾**：像"xx号当天"这种**上下文模式**无法通过数据库配置表达。问题不在数据源，而在**替换逻辑本身**。

## 业界最佳实践调研

### 阿里云PolarDB NL2SQL方案

根据[阿里云官方文档](https://help.aliyun.com/zh/polardb/polardb-for-mysql/llm-based-nl2sql)，生产级NL2SQL系统的核心原则：

> **NL2SQL的前提是需要模型能够理解表的含义，包括列名代表的意思。因此，在使用LLM-based NL2SQL前，需要为常用的数据表以及表中的列添加注释。**

关键发现：
1. **不做任何预处理替换**：直接将用户原始问题传给LLM
2. **Schema注释即语义**：在列注释中标注业务含义
3. **LLM自行推理**：现代大模型完全能通过上下文理解同义词

### Azure AI Search方案

微软的[最佳实践](https://techcommunity.microsoft.com/blog/azure-ai-services-blog/best-practices-for-using-azure-ai-search-for-natural-language-to-sql-generation-/4281347)同样强调：

> Store Synonyms and Related Terms to Enhance Retrieval - but let the LLM handle semantic understanding

使用向量检索增强召回，但**语义理解交给LLM**。

## 最终解决方案

### 方案核心：零预处理 + Prompt约束

#### 1. 完全禁用同义词替换

```java
public String expandSynonyms(String query, Long datasourceId) {
    // ✅ 不再做任何同义词替换，LLM会通过Schema注释和Prompt约束自行理解语义
    return query;
}
```

**删除内容**：
- ❌ 所有硬编码映射（约35行）
- ❌ 阻断规则引擎（约65行）
- ❌ 上下文检测辅助方法（约20行）
- ❌ 行业扩展点调用逻辑（约50行）

**保留内容**：
- ✅ 类框架（供未来可能的扩展）
- ✅ 从`industry_concept`表加载的逻辑（暂未使用）

#### 2. 在System Prompt中增加语义约束

在[NL2SQLService.java](file:///D:/WorkSpace/idea%20workspace/NL2Sql/nl2sql-core/src/main/java/com/nl2sql/core/service/NL2SQLService.java#L613-L619)的SQL生成Prompt中注入：

```
🚫 **严禁同义词替换（极其重要）**：
- 永远不要对用户原句做字面同义词替换改写，不要把词语强行换成近义词
- 若问句中已经出现具体日期、具体数字、具体名称、具体对象等明确实体：
  所有代词：当天、当日、该月、这家、此项、该商品、其上、对应等
  一律就近绑定前面已出现的具体实体
- 严禁私自泛化替换成全局默认值：今天、当前本月、全部、本店、系统当前时间
- 生成 SQL 禁止同时出现固定指定值 + 系统动态当前值，避免逻辑冲突
```

#### 3. LLM通过Schema元数据理解语义

```sql
-- 数据库列注释示例
COMMENT ON COLUMN orders.total_amount IS '订单金额';
COMMENT ON COLUMN orders.created_at IS '创建时间';
```

当用户问"查询2月2号当天的订单金额"时：
1. RAG检索到`orders`表结构
2. LLM看到`total_amount: 订单金额`的注释
3. LLM理解"当天"应绑定到"2月2号"
4. 生成正确SQL：`WHERE DATE(created_at) = '2026-02-02'`

## 方案对比

| 维度 | 硬编码替换 | 阻断规则引擎 | 数据库配置化 | **Prompt约束+LLM理解** |
|------|-----------|-------------|-------------|---------------------|
| **维护成本** | ❌ 高（需持续更新映射） | ❌ 极高（规则无限增长） | ⚠️ 中（需建表+维护） | ✅ **低（一次性完善注释）** |
| **灵活性** | ❌ 低（无法处理上下文） | ⚠️ 中（依赖规则完整性） | ⚠️ 中（配置表达能力有限） | ✅ **高（LLM自适应）** |
| **准确性** | ❌ 易出错（语义冲突） | ⚠️ 中（规则覆盖不全） | ⚠️ 中（配置可能遗漏） | ✅ **高（LLM智能推理）** |
| **额外调用** | ✅ 无 | ✅ 无 | ✅ 无 | ✅ **无** |
| **扩展性** | ❌ 差 | ❌ 差 | ⚠️ 一般 | ✅ **优秀** |

## 技术启示

### 1. 警惕"过度工程化"陷阱

我们曾试图通过以下方式解决一个简单问题：
- 硬编码映射 → 发现语义冲突
- 添加阻断规则 → 规则无限膨胀
- 数据库配置化 → 无法表达上下文模式
- 扩展点机制 → 增加复杂度但未解决根因

**反思**：有时候最简单的方案（让LLM自己理解）反而是最优解。

### 2. 充分利用LLM的原生能力

现代大模型（Qwen/GPT/Claude）已经具备强大的语义理解能力：
- 能理解"定单"="订单"（拼写纠错）
- 能理解"钱/费用/价格"在不同上下文的含义
- 能根据上下文判断"当天"指代什么

**关键**：不要试图用传统编程思维去"帮助"LLM，而是给它清晰的指令和足够的上下文。

### 3. Schema设计即Prompt工程

在NL2SQL场景中，**数据库注释的质量直接决定LLM的理解能力**：

```sql
-- ❌ 差的注释
COMMENT ON COLUMN orders.status IS '状态';

-- ✅ 好的注释
COMMENT ON COLUMN orders.status IS '订单状态：pending=待支付, paid=已支付, shipped=已发货, completed=已完成';
```

好的注释能让LLM：
- 准确理解字段含义
- 正确处理枚举值映射
- 避免臆造不存在的字段

### 4. 通用产品的边界意识

作为通用NL2SQL产品：
- ❌ 禁止硬编码行业特定逻辑（如"电商的GMV计算方式"）
- ❌ 禁止硬编码业务术语映射（如"客户=用户"）
- ✅ 通过扩展点机制支持行业定制
- ✅ 通过Prompt约束保证通用性

## 实施效果

### 解决的问题
- ✅ "2月2号当天"不再被错误替换为"2月2号今天"
- ✅ "这家店的销售额"能正确绑定到上下文提到的店铺
- ✅ "价格超过100的商品"不会错误映射到订单金额字段

### 潜在风险及缓解
- ⚠️ **向量检索召回率可能下降**
  - 原因：不再将"定单"扩展为"订单"进行检索
  - 缓解：现代embedding模型本身能理解同义词，影响有限
  - 监控：观察检索命中率变化

- ⚠️ **依赖LLM的理解能力**
  - 原因：不再做预处理，完全信任LLM
  - 缓解：通过完善的Schema注释和清晰的Prompt约束
  - 验证：通过A/B测试对比准确率

## 测试建议

### 核心测试用例

```sql
-- 测试1: 具体日期 + 代词
用户问："查询2月2号当天的订单金额"
期望：WHERE DATE(created_at) = '2026-02-02'
禁止：WHERE created_at >= CURDATE()

-- 测试2: 拼写纠错
用户问："查下昨天的定单"
期望：能正确识别orders表
验证：SELECT * FROM orders WHERE DATE(created_at) = CURDATE() - INTERVAL 1 DAY

-- 测试3: 上下文代词绑定
用户问："杭州店的销售额是多少"
      "这家店的订单数呢"
期望：第二次查询仍针对杭州店
验证：WHERE store_name = '杭州店'

-- 测试4: 语义歧义处理
用户问："查询价格超过100的商品"
期望：查询products表的price字段
禁止：查询orders表的amount字段
```

### 回归测试范围
- L1-L9所有难度级别的训练用例
- 历史高频查询场景
- 多轮对话上下文理解

## 总结

这次重构的核心思想是：**从"替LLM做决策"转向"给LLM清晰的指令"**。

硬编码的同义词替换看似在"帮助"LLM理解，实则在**剥夺LLM的上下文推理能力**。当我们删除了170行复杂的替换逻辑，只在Prompt中增加了7行约束，反而获得了更准确、更灵活、更易维护的解决方案。

这提醒我们：在LLM时代，**最好的代码可能是没有代码**——与其编写复杂的规则引擎，不如精心设计Prompt和Schema，让LLM发挥其真正的优势。

---

**参考资料**：
- [阿里云PolarDB NL2SQL官方文档](https://help.aliyun.com/zh/polardb/polardb-for-mysql/llm-based-nl2sql)
- [Azure AI Search NL2SQL最佳实践](https://techcommunity.microsoft.com/blog/azure-ai-services-blog/best-practices-for-using-azure-ai-search-for-natural-language-to-sql-generation-/4281347)
- [Spider & BIRD评测数据集](https://yale-lily.github.io/spider)
