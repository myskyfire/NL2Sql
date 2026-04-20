# 小模型→大模型迁移防御方案

> **背景**：当前Agent运行在本地小模型（qwen2.5-coder:7b）上，未来将切换到大参数模型。  
> **风险**：大模型可能因为"过度思考"或"语义漂移"导致输出异常（如返回NULL、幻觉列名等）。  
> **目标**：通过代码层防御和回归测试，确保切换后100%兼容。

---

## 📋 实施方案总览

根据DeepSeek建议，实施了三大防御策略：

### ✅ 方案一：语法防火墙（已完成）
**位置**：`nl2sql-common/src/main/java/com/nl2sql/common/util/MarkdownUtils.java`

**核心改进**：
```java
// 1. NULL值检测
if (sql.toUpperCase().trim().equals("NULL") || 
    sql.toUpperCase().trim().equals("NONE") ||
    sql.toUpperCase().trim().equals("N/A")) {
    return "SELECT 'no_result_found' AS info"; // 安全默认值
}

// 2. 空值保护
if (sql == null || sql.trim().isEmpty()) {
    return "SELECT 'no_result_found' AS info";
}

// 3. 最终验证
if (sql.trim().isEmpty()) {
    return "SELECT 'no_result_found' AS info";
}
```

**效果**：
- ✅ 防止大模型输出NULL/None等无效值
- ✅ 所有异常输入都转换为无害的默认查询
- ✅ 避免后续执行阶段抛出NullPointerException

---

### ✅ 方案二：Schema白名单校验（已完成）
**位置**：`nl2sql-core/src/main/java/com/nl2sql/core/agent/validation/SQLValidationService.java`

**新增方法**：
```java
/**
 * 校验 SQL 中的列名是否在 Schema 白名单内
 * 目的：防止大模型幻觉生成不存在的列名
 */
public List<String> validateColumnWhitelist(String sql, Set<String> allowedColumns)
```

**工作原理**：
1. 使用JSqlParser解析SQL，提取所有使用的列名
2. 与Schema白名单对比（支持表名.列名格式）
3. 发现非法列名时记录警告，但不阻断执行

**使用示例**：
```java
// 定义允许的列名
Set<String> allowedColumns = Set.of(
    "orders.id",
    "orders.user_id",
    "users.real_name"
);

// 校验SQL
List<String> issues = validationService.validateColumnWhitelist(sql, allowedColumns);

if (!issues.isEmpty()) {
    log.warn("发现幻觉列名: {}", issues);
    // 可选：触发重新生成或人工审核
}
```

**效果**：
- ✅ 捕获大模型生成的幻觉列名
- ✅ 支持带表名前缀的精确匹配
- ✅ 不影响正常流程，仅记录警告

---

### ✅ 方案三：回归测试集（已完成）
**位置**：
- 测试用例：`nl2sql-core/src/test/resources/nl2sql-test-cases.json`
- 测试类：`nl2sql-core/src/test/java/com/nl2sql/core/agent/validation/NL2SQLRegressionTest.java`

**测试覆盖**：
| 测试ID | 测试场景 | 验证点 |
|--------|---------|--------|
| 1 | 简单查询 | SELECT + ORDER BY + LIMIT |
| 2 | 聚合查询 | COUNT + GROUP BY |
| 3 | 复杂聚合 | SUM + ORDER BY + LIMIT |
| 4 | 多表关联 | JOIN语法正确性 |
| 5 | 分组统计 | GROUP BY维度分析 |
| 6 | 条件过滤 | HAVING子句使用 |
| 7 | 时间函数 | CURDATE()日期处理 |
| 8 | 空值查询 | LEFT JOIN + IS NULL |
| 9 | 多聚合函数 | AVG + COUNT组合 |
| 10 | Top-N查询 | 排序+限制 |

**测试类型**：
1. **语法防火墙测试** - 验证NULL/空值处理
2. **Schema白名单测试** - 验证幻觉列名捕获
3. **关键字检查测试** - 验证SQL结构完整性
4. **综合验证测试** - 验证聚合/Joint检查
5. **Markdown清洗测试** - 验证代码块提取

**运行测试**：
```bash
# 切换到JDK 21环境
$env:JAVA_HOME="D:\Program Files\Java\jdk-21.0.6"
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"

# 运行回归测试
mvn test -Dtest=NL2SQLRegressionTest
```

**预期结果**：
- 小模型阶段：通过率 ≥ 80%
- 大模型阶段：通过率 = 100%
- 如果某条用例小模型通过但大模型失败 → 需要微调Prompt或Schema注释

---

## 🔧 集成到现有流程

### 1. 在NL2SQLTool中集成白名单校验

**修改位置**：`nl2sql-core/src/main/java/com/nl2sql/core/agent/tools/NL2SQLTool.java`

**建议在LLM生成SQL后立即调用**：
```java
// LLM生成SQL
String rawSql = llmService.generateSQL(prompt);

// 步骤1：语法防火墙清洗
String cleanedSql = MarkdownUtils.cleanSQL(rawSql);

// 步骤2：Schema白名单校验（新增）
Set<String> allowedColumns = getSchemaColumns(datasourceId); // 从元数据获取
List<String> issues = validationService.validateColumnWhitelist(cleanedSql, allowedColumns);

if (!issues.isEmpty()) {
    log.warn("检测到幻觉列名，触发重新生成: {}", issues);
    // 可选：自动重试或返回错误提示
}

// 步骤3：继续后续流程...
```

### 2. 在Groovy Skill中使用

**修改位置**：`nl2sql-web/src/main/resources/skills/standard-query/scripts/StandardQuerySkill.groovy`

**示例**：
```groovy
// 生成SQL
def sql = llmService.generateSQL(prompt)

// 清洗
sql = com.nl2sql.common.util.MarkdownUtils.cleanSQL(sql)

// 校验（可选，性能敏感场景可跳过）
def allowedColumns = getTableColumns(datasourceId)
def issues = validationService.validateColumnWhitelist(sql, allowedColumns as Set)

if (!issues.isEmpty()) {
    log.warn("⚠️ 发现潜在问题: {}", issues)
    // 不阻断，仅记录日志
}
```

---

## 📊 监控指标

### 关键指标
| 指标 | 目标值 | 监控方式 |
|------|-------|---------|
| NULL/空值拦截率 | 100% | 日志统计 `SQL清洗` 关键词 |
| 幻觉列名捕获数 | >0表示有效 | 日志统计 `非法列名` 关键词 |
| 回归测试通过率 | 100% | CI/CD自动化测试 |
| SQL生成成功率 | ≥95% | 业务指标监控 |

### 日志示例
```log
[SQL清洗] 检测到LLM输出NULL/None，转换为安全默认查询: NULL
[SQLValidation] 发现 1 个非法列名: ❌ 列名 'fake_column' 不在 Schema 白名单中，可能是大模型幻觉
```

---

## ⚠️ 注意事项

### 1. 性能影响
- **语法防火墙**：无性能影响（纯字符串操作）
- **Schema白名单**：每次校验需解析SQL（~10ms），建议：
  - 开发/测试环境：开启
  - 生产环境：根据QPS决定是否开启

### 2. 误报处理
如果合法列名被误判为"幻觉"，需要：
1. 检查Schema白名单是否完整
2. 更新元数据同步逻辑
3. 调整匹配规则（当前支持`table.column`和`column`两种格式）

### 3. 测试用例维护
- 新增业务场景时，同步添加测试用例
- 定期（每月）运行回归测试，确保兼容性
- 大模型切换前，必须100%通过测试

---

## 🎯 下一步行动

### 短期（1周内）
- [ ] 在NL2SQLTool中集成白名单校验
- [ ] 补充更多测试用例（至少20个）
- [ ] 配置CI/CD自动运行回归测试

### 中期（1个月内）
- [ ] 收集生产环境日志，分析幻觉列名模式
- [ ] 优化白名单匹配算法（支持模糊匹配）
- [ ] 建立Prompt优化反馈闭环

### 长期（3个月内）
- [ ] 实现自动化Prompt调优（基于测试结果）
- [ ] 建立大模型评估基准（Benchmark）
- [ ] 探索多模型A/B测试框架

---

## 📚 参考资料

- DeepSeek建议原文：见用户消息
- JSqlParser文档：https://jsqlparser.github.io/JSqlParser/
- OpenClaw Workflow标准：项目内`SKILL_REFACTOR_REFLECTION.md`
