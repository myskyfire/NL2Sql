# NL2SQL 项目开发问题记录

## 2026-04-10

### 问题1：表关联关系管理 - LLM表选择遗漏中间表

**现象：**
用户查询"最近10天的订单按商品分类维度做下统计"，LLM在表选择阶段只选择了`["orders", "order_items", "product_categories"]`，漏掉了关键的中间表`products`。

**根本原因：**
1. 向量检索基于语义相似度，可能遗漏逻辑必需但语义不相关的中间表
2. `buildTableCheckPrompt`没有传入关联关系信息，LLM不知道`order_items`和`product_categories`之间需要通过`products`表中转
3. LLM在"禁止臆造字段"约束下，不敢使用未选中的表的字段

**解决方案：**
1. 在NL2SQLTool的表选择迭代循环中（第70-77行），获取关联关系并传入Prompt
2. 修改`buildTableCheckPrompt`签名，添加`relationshipInfo`参数
3. 在Prompt中明确说明："如果需要通过中间表关联，必须包含所有中间表"
4. 基于关联关系自动扩展表列表（第217-231行），从`table_relationships`中提取所有涉及的表名

**代码位置：**
- `NL2SQL-core/src/main/java/com/nl2sql/core/agent/tools/NL2SQLTool.java`
  - 第70-77行：表选择迭代中获取关联关系
  - 第217-231行：基于关联关系扩展表列表
  - 第336-355行：增强`buildTableCheckPrompt`方法

**经验教训：**
- 小参数模型（如qwen2.5-coder:7b）在多重约束下会做出妥协，优先遵守更明确的约束
- 向量检索有局限性，需要结合结构化元数据（关联关系）进行补充
- Prompt工程需要在每个阶段都提供完整的信息（表结构+关联关系）

---

### 问题2：SQL生成使用IN子查询而非直接JOIN

**现象：**
LLM生成的SQL：
```sql
JOIN product_categories pc ON oi.product_id IN (SELECT id FROM products WHERE category_id = pc.id)
```

**根本原因：**
1. 表选择阶段漏掉`products`表
2. LLM发现需要用`products.category_id`，但该表未被选中
3. 在"禁止臆造字段"约束下，LLM选择用子查询绕过限制

**解决方案：**
1. 完善数据库中的表关联关系配置（从3条增加到7条）
2. 在NL2SQLTool的SQL生成Prompt中明确禁止使用子查询（第264-270行）
3. 在StandardQuerySkill中添加`optimizeSQL`检测方法（仅警告，暂未实际修复）

**代码位置：**
- `NL2SQL-core/src/main/java/com/nl2sql/core/agent/tools/NL2SQLTool.java` 第264-270行
- `NL2SQL-core/src/main/java/com/nl2sql/core/agent/skills/StandardQuerySkill.java` 第93-109行
- Database: `nl2sql_meta_db.table_relationships` 新增4条关联关系

**经验教训：**
- 约束冲突时，LLM会选择优先级更高的约束
- 需要提供完整的上下文信息，避免LLM"走捷径"

---

### 问题3：时间格式化精度问题

**现象：**
用户查询"统计最近10天每天的订单金额"，返回的时间列显示为`2026-04-01 00:00:00`，期望只显示`2026-04-01`。

**错误尝试：**
最初在SQLExecutor中检测时分秒是否为0，是则只返回日期部分。但用户指出这样写死不好，因为可能存在精确到秒的订单时间。

**正确方案：**
修改NL2SQLTool的Prompt，要求LLM使用`DATE_FORMAT()`函数而不是`DATE()`函数：
```sql
-- 错误
SELECT DATE(created_at) AS order_date

-- 正确
SELECT DATE_FORMAT(created_at, '%Y-%m-%d') AS order_date
```

**代码位置：**
- `NL2SQL-core/src/main/java/com/nl2sql/core/agent/tools/NL2SQLTool.java` 第244-250行

**经验教训：**
- 不要在后端硬编码业务逻辑判断
- 应该通过Prompt引导LLM生成正确的SQL

---

### 问题4：表格列名中英文混合显示

**现象：**
查询结果表格的表头显示：`category_name | total_quantity | total_orders | 订单总金额`，中英文混杂。

**分析：**
- `category_name`等是数据库原始字段名，不在元数据映射中
- `订单总金额`是LLM生成的中文别名
- SQLExecutor的`buildColumnNameMap`只映射了数据库字段的中文注释

**解决方案演进：**

**方案1（废弃）：** 前端硬编码常见字段映射表
- 问题：违反"禁止硬编码业务逻辑"原则

**方案2（废弃）：** 前端调用后端翻译接口
- 问题：产生无意义的网络请求

**方案3（最终）：** 后端SQLExecutor返回前自动翻译
- 在`executeQuery`方法中，返回数据前检测未映射的列名
- 调用LLM批量翻译英文列名为中文
- 重命名所有行的列key

**代码位置：**
- `NL2SQL-core/src/main/java/com/nl2sql/core/executor/SQLExecutor.java`
  - 第26行：注入NL2SQLTool
  - 第167-171行：调用翻译方法
  - 第340-406行：`translateColumnNames`方法实现

**经验教训：**
- 后端处理优于前端，避免无意义请求
- LLM翻译比规则映射更通用、准确
- 每次查询都调用LLM会影响性能，后续可考虑缓存翻译结果

---

### 问题5：Java正则表达式语法错误

**现象：**
编译错误：`非法逃逸符`

**原因：**
在Java字符串中使用JavaScript风格的正则：`.replace(/_/g, ' ')`

**解决：**
改用Java标准API：
```java
// 错误
colName.replace(/_/g, ' ')

// 正确
colName.replace('_', ' ')
colName.replaceAll("([a-z])([A-Z])", "$1 $2")
```

**代码位置：**
- `NL2SQL-web/src/main/java/com/nl2sql/web/controller/TranslationController.java` 第120-125行

---

### 问题6：Spring依赖注入失败

**现象：**
编译错误：`找不到符号 ModelRouter`

**原因：**
web模块无法直接引用core模块的内部类

**解决：**
改用已暴露的Bean：
```java
// 错误
@Autowired
private ModelRouter modelRouter;

// 正确
@Autowired
private NL2SQLTool nl2sqlTool;
```

**代码位置：**
- `NL2SQL-web/src/main/java/com/nl2sql/web/controller/TranslationController.java`

---

## 待优化项

1. **列名翻译缓存**：当前每次查询都调用LLM翻译，应增加缓存机制
2. **SQL修复能力**：检测到IN子查询时，应自动修复而非仅警告
3. **关联关系自动推断**：`auto-detect`接口暂未实现LLM推断逻辑
4. **性能监控**：缺少对LLM调用耗时、翻译成功率的监控

---

### 问题7：SQL执行前风险评估机制（EXPLAIN优先 + LLM辅助优化）

**时间**：2026-04-10  
**最后更新**：2026-04-18  
**现象**：复杂SQL（多表JOIN、子查询）可能引发性能问题，但系统直接执行，无风险预警。

**解决方案演进**：

**方案1（废弃）：** 创建独立的`analyze_sql_risk` Tool供LLM调用
- 问题：违背Agent设计原则，绕过了StandardQuerySkill的封装

**方案2（最终）：** 在StandardQuerySkill内部集成EXPLAIN优先 + LLM辅助优化机制

#### **完整流程**（Groovy脚本实现）

**Step 0: 快速判断** → 简单查询直接放行（不调用 EXPLAIN）
- 单表查询、主键等值查询、无 JOIN/子查询/聚合函数
- 直接判定为 LOW 风险，跳过后续步骤

**Step 1: EXPLAIN 分析** → 获取客观风险等级
- 执行 `EXPLAIN SQL` 获取执行计划
- 分析表扫描类型、索引使用情况、行数估算等
- 风险等级：LOW / MEDIUM / HIGH

**Step 2: 根据风险等级处理**

1. **LOW 风险** → 直接执行
   - EXPLAIN 显示使用索引、扫描行数少
   - 无需额外处理

2. **MEDIUM 风险** → 调用 LLM 获取优化建议（仅供参考，仍执行原 SQL）
   - 将 EXPLAIN 结果传给 LLM
   - LLM 分析性能瓶颈，给出优化建议
   - 前端展示：橙色渐变背景显示优化建议
   - SQL 继续执行

3. **HIGH 风险** → 调用 LLM 重新生成 SQL → 重新 EXPLAIN
   - 告知 LLM 原始 SQL 和风险原因
   - LLM 生成优化后的新 SQL
   - **对新 SQL 再次执行 EXPLAIN**
   - **如果优化后仍是 HIGH**：
     - ✅ **只返回优化后的 SQL 给前端**
     - ✅ **标注高风险，需要人工介入**
     - ✅ **绝对不执行 SQL**
     - 前端展示：错误信息 + 优化建议 + 优化后的 SQL
   - **如果优化后风险降低**：
     - 使用优化后的 SQL 继续执行
     - 前端展示：优化建议（如果有）

#### **关键设计**

- ✅ **EXPLAIN 优先**：先通过客观的执行计划分析，避免 LLM 主观臆断
- ✅ **LLM 辅助优化**：中风险给建议，高风险重生成
- ✅ **高风险阻断**：优化后仍是 HIGH，绝对不执行，必须人工审核
- ✅ **前端友好展示**：新增 `optimizationSuggestion` 字段，清晰显示风险原因和优化建议
- ✅ **无循环风险**：最多 2 次 EXPLAIN + 2 次 LLM 调用
- ✅ **保持架构一致**：评估在 Skill 内部完成，不暴露底层工具

#### **代码位置**

- `nl2sql-web/src/main/resources/skills/standard-query/scripts/StandardQuerySkill.groovy`
  - 第 40 行：声明 `mediumRiskResult` 变量
  - 第 114-143 行：Step 2.5 风险评估流程（主流程）
  - 第 120-130 行：HIGH 风险处理（构建优化建议并阻断）
  - 第 131-136 行：MEDIUM 风险处理（保存结果）
  - 第 169-195 行：成功执行时添加优化建议到返回结果
  - 第 207-268 行：`buildOptimizationSuggestionForFrontend` 构建前端友好的建议文本
  - 第 220-328 行：`assessSQLRisk` 方法实现（EXPLAIN + LLM 优化）
  - 第 258-305 行：HIGH 风险处理（LLM 重生成 + 重新 EXPLAIN）
  - 第 277-294 行：优化后仍是 HIGH 的处理（阻断 + 返回优化后的 SQL）
  - 第 703-732 行：`createSuccessResultWithChart` 添加 optimizationSuggestion 参数
  - 第 734-763 行：`createSuccessResult` 添加 optimizationSuggestion 参数
  - 第 940-953 行：`createRiskBlockedResult` 添加 optimizationSuggestion 参数

- `nl2sql-web/src/main/resources/static/agent-chat.html`
  - 第 583-591 行：成功响应中显示 optimizationSuggestion
  - 第 507-525 行：错误响应中显示 optimizationSuggestion（高风险阻断）

#### **前端展示效果**

**中风险场景**（SQL 继续执行）：
```
📝 执行的 SQL: SELECT ...

💡 优化建议:
⚠️ 中风险SQL，继续执行但请注意以下优化建议：

🔍 性能瓶颈：
表 orders 未使用索引，进行了全表扫描

💡 优化建议：
建议在 orders.user_id 字段上添加索引

📈 预期效果：
查询速度可提升 80%

✅ 共 100 条记录，耗时 1.23秒
```

**高风险场景**（SQL 被阻断）：
```
❌ ⚠️ SQL风险评估为高风险，已阻断执行
原因: LLM 优化后仍为高风险，需要人工介入审核。原始风险：...

💡 优化建议:
⚠️ 高风险SQL，已阻断执行

📋 风险原因：
LLM 优化后仍为高风险，需要人工介入审核。原始风险：...

💡 LLM 已尝试优化，生成新 SQL：
SELECT ... (优化后的SQL)

👉 请人工审核上述优化后的 SQL，确认安全后再执行。

🔧 LLM 优化后的 SQL:
SELECT ... (优化后的SQL)
```

#### **经验教训**

- ✅ Agent架构中，辅助决策应在Skill内部完成，不应暴露为独立Tool
- ✅ **EXPLAIN 客观分析 + LLM 智能优化**是最佳组合
- ✅ **高风险必须阻断**，绝对不能执行，即使 LLM 优化后仍是 HIGH
- ✅ **前端友好展示**：清晰的优化建议和风险原因，帮助用户理解
- ✅ **必须控制循环次数**，防止无限递归（最多 2 次 EXPLAIN + 2 次 LLM）
- ✅ **返回优化后的 SQL**：高风险阻断时，返回 LLM 优化后的 SQL 供人工审核
