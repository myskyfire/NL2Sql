# 行业插件化架构 - 二开指南

## 📋 架构设计原则

**目标**：让行业定制（如财务、医疗）通过**配置 + 扩展点**实现，无需修改主程序代码。

**三层扩展机制**：
1. **Level 1 - 配置层**（最简单）：通过数据库表配置术语映射
2. **Level 2 - 扩展点层**（推荐）：实现`IndustryConceptExtension`接口
3. **Level 3 - 插件层**（最灵活）：独立JAR包，SPI加载

---

## 🚀 快速开始：3步实现财务行业定制

### Step 1: 创建扩展类

```java
package com.yourcompany.finance;

import com.nl2sql.core.llm.extension.IndustryConceptExtension;
import org.springframework.stereotype.Component;

@Component  // ← 关键：让Spring自动扫描
public class FinanceConceptExtension implements IndustryConceptExtension {
    
    @Override
    public String enhancePromptBeforeGeneration(String originalPrompt, String question, Long datasourceId) {
        // 注入财务口径说明
        if (question.contains("收入")) {
            return originalPrompt + "\n⚠️ 财务提示：收入=主营业务收入贷方-红字冲销";
        }
        return null; // 不干预
    }
    
    @Override
    public String correctSqlAfterGeneration(String generatedSql, String question, Long datasourceId) {
        // 自动剔除红字
        if (question.contains("收入") && !generatedSql.contains("status")) {
            return generatedSql.replace("WHERE", "WHERE status != 'RED' AND ");
        }
        return null;
    }
    
    @Override
    public String getMetricTemplate(String metricName, Long datasourceId) {
        if ("应收账款余额".equals(metricName)) {
            return "SELECT SUM(debit_balance) FROM account_balances WHERE subject_code LIKE '1122%'";
        }
        return null;
    }
}
```

### Step 2: 配置行业术语（可选）

```sql
-- 在 industry_concept 表中添加财务术语
INSERT INTO industry_concept (industry_code, concept_type, concept_key, concept_aliases) VALUES
('finance', 'metric', 'revenue', '["收入","销售额","营业收入","主营业务收入"]'),
('finance', 'metric', 'net_profit', '["净利润","税后利润","纯利润"]'),
('finance', 'metric', 'ar_balance', '["应收账款","应收余额","客户欠款"]');
```

### Step 3: 启动应用

```bash
# Spring会自动扫描并加载你的扩展类
mvn spring-boot:run
```

**无需修改任何主程序代码！** ✅

---

## 📊 扩展点能力总览

| 扩展点方法 | 调用时机 | 适用场景 | 难度 |
|-----------|---------|---------|------|
| `extractTerms()` | 问题解析阶段 | 提取行业术语 | ⭐ |
| `suggestSynonyms()` | 同义词扩展阶段 | 推荐同义词 | ⭐ |
| `enhancePromptBeforeGeneration()` | LLM生成前 | 注入行业提示词 | ⭐⭐ |
| `correctSqlAfterGeneration()` | LLM生成后 | 修正SQL（如加过滤条件） | ⭐⭐ |
| `validateBeforeExecution()` | SQL执行前 | 安全检查/权限控制 | ⭐⭐⭐ |
| `getMetricTemplate()` | 指标查询时 | 返回预定义计算模板 | ⭐⭐ |
| `validateSemanticConsistency()` | SQL验证阶段 | 语义一致性检查 | ⭐⭐⭐ |
| `learnFromSuccess()` | 用户高评分后 | 自定义学习策略 | ⭐⭐ |

---

## 💡 典型场景示例

### 场景1：财务 - 自动剔除红字

```java
@Override
public String correctSqlAfterGeneration(String sql, String question, Long datasourceId) {
    if (question.contains("收入") && sql.toUpperCase().contains("FROM VOUCHERS")) {
        if (!sql.toUpperCase().contains("STATUS")) {
            return sql.replaceAll("(?i)WHERE", "WHERE status != 'RED' AND ");
        }
    }
    return null;
}
```

### 场景2：合规 - 限制数据范围

```java
@Override
public Map<String, Object> validateBeforeExecution(String sql, String question, Long datasourceId, String userId) {
    // 只能查本部门数据
    String deptId = getUserDepartment(userId);
    if (sql.toUpperCase().contains("FROM EXPENSE_CLAIMS") && !sql.contains("department_id")) {
        sql += " AND department_id = " + deptId;
    }
    
    return Map.of("success", true, "message", "允许执行");
}
```

### 场景3：电商 - 复购率计算模板

```java
@Override
public String getMetricTemplate(String metricName, Long datasourceId) {
    if ("复购率".equals(metricName)) {
        return """
            SELECT 
                COUNT(DISTINCT user_id HAVING COUNT(order_id) > 1) * 100.0 / 
                COUNT(DISTINCT user_id) AS repurchase_rate
            FROM orders
            WHERE DATE(order_date) >= DATE_SUB(CURDATE(), INTERVAL 90 DAY)
            """;
    }
    return null;
}
```

---

## 🔧 高级用法：独立插件JAR

如果不想把扩展类放在主项目中，可以打成独立JAR：

### 1. 创建插件项目

```xml
<!-- pom.xml -->
<dependency>
    <groupId>com.nl2sql</groupId>
    <artifactId>nl2sql-core</artifactId>
    <version>1.0.0</version>
    <scope>provided</scope>  <!-- 编译时依赖，运行时由主程序提供 -->
</dependency>
```

### 2. 打包为JAR

```bash
mvn clean package
```

### 3. 放到主程序的`lib/`目录

```
nl2sql-web/
├── lib/
│   └── finance-plugin-1.0.0.jar  ← 你的插件
├── nl2sql-web-1.0.0.jar
└── application.yml
```

### 4. 修改启动脚本

```bash
java -cp "nl2sql-web-1.0.0.jar:lib/*" com.nl2sql.web.NL2SQLApplication
```

---

## ❓ FAQ

### Q1: 多个扩展类会冲突吗？
**A**: 不会。Spring只会加载一个`IndustryConceptExtension`实现。如果有多个，使用`@Primary`指定优先级。

### Q2: 如何调试扩展类？
**A**: 在方法开头加日志：
```java
log.info("[财务扩展] enhancePromptBeforeGeneration called: question={}", question);
```

### Q3: 扩展类报错会影响主程序吗？
**A**: 不会。所有扩展点调用都有try-catch保护，异常会被记录但不阻断流程。

### Q4: 能否动态加载/卸载插件？
**A**: 当前不支持热插拔。需要重启应用才能生效新的扩展类。

---

## 📚 参考实现

查看示例代码：
- `nl2sql-core/src/main/java/com/nl2sql/core/llm/extension/example/FinanceConceptExtension.java`

完整API文档：
- `nl2sql-core/src/main/java/com/nl2sql/core/llm/extension/IndustryConceptExtension.java`
