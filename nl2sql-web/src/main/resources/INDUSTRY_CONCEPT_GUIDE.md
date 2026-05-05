# 行业概念配置 - 完整使用指南

## 📖 什么是行业概念?

**行业概念**是NL2SQL系统的"业务词典",用于告诉LLM不同行业的专业术语、指标含义和表的使用规则。

### 为什么需要它?

**问题场景**:
```
用户问: "统计各地区GMV"
LLM困惑: 
  - GMV是什么? → 销售额? 订单数? 利润?
  - "地区"从哪张表取? → users.province? orders.shipping_address?
  - 需要哪些表? → orders? users? user_addresses?
```

**有了行业概念后**:
```
系统自动注入Prompt:
  ✅ GMV = revenue(销售额),来自orders.actual_amount
  ✅ 地区 = region维度,来自users.province或orders.shipping_address
  ✅ 必须选择orders(有actual_amount) + users(有province)
```

---

## 🏗️ 核心架构

### 数据库表结构

```
industry_template (行业模板)
  ├─ industry_code: ecommerce/finance/medical
  └─ industry_name: 电子商务/金融/医疗

industry_concept (行业概念)
  ├─ concept_type: entity/metric/dimension/table_role/usage_rule
  ├─ concept_key: revenue/order_count/user
  ├─ concept_aliases: ["GMV","销售额","成交金额"]
  └─ description: 详细说明

concept_relation (同义词关系)
  ├─ source_concept_key: revenue
  └─ target_concept_key: gmv

datasource_industry_mapping (数据源-行业关联)
  ├─ datasource_id: 1
  └─ industry_code: ecommerce
```

---

## 🎯 5种概念类型详解

### 1️⃣ **entity** - 业务实体

**作用**: 告诉LLM系统中有哪些核心业务对象

**示例**:
```sql
INSERT INTO industry_concept VALUES
('ecommerce', 'entity', 'user', '["用户","客户","买家","会员"]', 
 '系统注册用户，存储在users表', 'manual', 'approved');
```

**效果**:
- 用户问"查询客户信息" → LLM知道找`users`表
- 用户问"统计买家数量" → LLM理解"买家"=user实体

---

### 2️⃣ **metric** - 关键指标

**作用**: 定义数值型指标的含义和来源字段

**示例**:
```sql
INSERT INTO industry_concept VALUES
('ecommerce', 'metric', 'revenue', '["销售额","收入","GMV","成交金额"]', 
 '订单实际支付金额(orders.actual_amount)，不含退款', 'manual', 'approved');
```

**效果**:
- Prompt注入: "涉及**数值型指标，例如：销售额、收入、GMV**时..."
- 用户问"统计GMV" → LLM知道要找`orders.actual_amount`字段
- 自动识别这是**数值型**,需要SUM/AVG等聚合函数

---

### 3️⃣ **dimension** - 分析维度

**作用**: 定义分组/筛选的维度字段

**示例**:
```sql
INSERT INTO industry_concept VALUES
('ecommerce', 'dimension', 'region', '["地区","省份","城市","区域"]', 
 '地理维度，来自users.province/city或orders.shipping_address', 'manual', 'approved');
```

**效果**:
- Prompt注入: "分析维度，例如：地区、省份、城市..."
- 用户问"按地区统计" → LLM知道要GROUP BY province/city
- 提示可能来自多张表(users/orders)

---

### 4️⃣ **table_role** - 表角色说明

**作用**: 说明表在业务中的定位(主表/维度表/关联表)

**示例**:
```sql
INSERT INTO industry_concept VALUES
('ecommerce', 'table_role', 'main_table', '["主表","核心表"]', 
 '包含核心业务数据和主要指标的表，如orders(订单)、order_items(明细)', 'manual', 'approved');
```

**效果**:
- Prompt注入: "主表（包含核心业务数据）、维度表（提供分类信息）..."
- 帮助LLM理解应该优先选哪张表

---

### 5️⃣ **usage_rule** - 使用场景区分 ⭐新增

**作用**: **区分相似表的不同用途**,解决LLM混淆问题

#### ❌ 没有usage_rule的问题

```
用户问: "查询张三的用户名和订单"

LLM看到:
  - orders表有receiver_name='张三'
  - user_addresses表也有receiver_name
  
错误判断: "orders已有receiver_name,不需要额外表"
结果: 无法获取username字段!
```

#### ✅ 有了usage_rule的效果

```sql
INSERT INTO industry_concept VALUES
('ecommerce', 'usage_rule', 'users_vs_addresses', '[]', 
 'user_addresses是地址表(存储收货信息:receiver_name/province)，仅用于地址相关查询。
  users是用户主表(存储账户信息:username/real_name/gender/email)。
  当需要用户名、性别、邮箱等用户属性时，必须通过orders.user_id→users.id关联users表，
  不要混淆这两张表', 
 'manual', 'approved');
```

**Prompt注入**:
```
⚠️ **表使用场景区分（重要）**：
   - user_addresses是地址表(存储收货信息...)，仅用于地址相关查询。
     users是用户主表(存储账户信息...)。当需要用户名、性别、邮箱等用户属性时，
     必须通过orders.user_id→users.id关联users表，不要混淆这两张表
```

**LLM正确推理**:
1. 用户要"用户名" → username字段
2. 查看usage_rule → username在users表,不在user_addresses
3. 查看关联关系 → orders.user_id → users.id
4. 返回: `missing_tables: ["users"]`

---

## 📝 配置步骤

### 步骤1: 执行SQL脚本

```bash
# 在MySQL中执行
mysql -u root -p nl2sql_meta_db < init_ecommerce_industry_concepts.sql
```

### 步骤2: 关联数据源到行业

```sql
-- 假设你的数据源ID是1
INSERT INTO datasource_industry_mapping (datasource_id, industry_code, priority) 
VALUES (1, 'ecommerce', 1);
```

### 步骤3: 前端验证

访问: `http://localhost:8080/admin-industry-concepts.html`

1. 选择行业: "电子商务 (ecommerce)"
2. 点击"查询"
3. 应看到23条概念记录(5实体+7指标+6维度+3表角色+2使用规则)

### 步骤4: 测试效果

**测试用例1**: 基础指标查询
```
用户问: "统计本月GMV"
预期: LLM识别GMV=revenue,选择orders表,生成SUM(actual_amount)
```

**测试用例2**: 维度分析
```
用户问: "按地区统计销售额"
预期: LLM识别地区=region维度,选择orders+users表,生成GROUP BY province
```

**测试用例3**: 使用场景区分
```
用户问: "查询张三的用户名和订单号"
预期: 
  - LLM看到orders有receiver_name='张三'
  - 但需要username字段 → 查看usage_rule
  - 返回missing_tables: ["users"]
  - 第二轮迭代补充users表schema
  - 最终生成: SELECT u.username, o.order_no FROM orders o JOIN users u ON o.user_id=u.id WHERE u.real_name='张三'
```

---

## 💡 高级技巧

### 技巧1: 如何编写有效的usage_rule

**❌ 错误写法**(太抽象):
```sql
description: 'users表和user_addresses表用途不同'
```

**✅ 正确写法**(具体明确):
```sql
description: 'user_addresses是地址表(存储收货信息:receiver_name/province/city/detail_address)，
              仅用于地址相关查询。
              users是用户主表(存储账户信息:username/real_name/gender/email)。
              当需要用户名、性别、邮箱等用户属性时，必须通过orders.user_id→users.id关联users表，
              不要混淆这两张表'
```

**关键点**:
1. **明确每张表的字段**: 列出关键字段名
2. **说明适用场景**: "仅用于地址相关查询"
3. **给出操作指引**: "必须通过orders.user_id→users.id关联"
4. **警告常见错误**: "不要混淆这两张表"

---

### 技巧2: 为不同行业配置不同规则

**电商行业**:
```sql
('ecommerce', 'usage_rule', 'orders_vs_returns', '[]',
 'orders是正常订单，returns是退货单。计算净销售额时需要orders.actual_amount - returns.refund_amount')
```

**金融行业**:
```sql
('finance', 'usage_rule', 'accounts_vs_transactions', '[]',
 'accounts是账户余额表(静态快照)，transactions是交易流水表(动态记录)。
  查询当前余额用accounts，查询历史交易用transactions')
```

**医疗行业**:
```sql
('medical', 'usage_rule', 'patients_vs_visits', '[]',
 'patients是患者基本信息(姓名/年龄/性别)，visits是就诊记录(诊断/处方/费用)。
  查询患者档案用patients，查询就诊历史用visits')
```

---

### 技巧3: 利用alias增强语义理解

**场景**: 用户可能用多种说法表达同一概念

```sql
-- 单一别名
concept_aliases: '["销售额"]'

-- 多个别名(推荐)
concept_aliases: '["销售额","收入","GMV","成交金额","实付金额"]'
```

**效果**:
- 用户说"统计收入" → 匹配revenue
- 用户说"看GMV" → 匹配revenue
- 用户说"成交金额多少" → 匹配revenue

---

## 🔧 常见问题

### Q1: 配置后不生效?

**检查清单**:
1. ✅ 数据源是否关联了行业? (`datasource_industry_mapping`表)
2. ✅ 概念状态是否为`approved`? (pending状态不会注入Prompt)
3. ✅ 重启应用了吗? (IndustryConceptDictionary启动时加载)

**调试方法**:
```sql
-- 检查数据源关联
SELECT * FROM datasource_industry_mapping WHERE datasource_id = 1;

-- 检查概念状态
SELECT concept_type, concept_key, status 
FROM industry_concept 
WHERE industry_code = 'ecommerce';
```

---

### Q2: 如何添加新行业?

**步骤**:
1. 插入行业模板:
```sql
INSERT INTO industry_template (industry_code, industry_name, is_active) 
VALUES ('education', '教育行业', 1);
```

2. 配置该行业的概念(参考ecommerce示例)

3. 关联数据源:
```sql
INSERT INTO datasource_industry_mapping (datasource_id, industry_code, priority) 
VALUES (2, 'education', 1);
```

---

### Q3: usage_rule会影响性能吗?

**答案**: **几乎无影响**

- 启动时一次性加载到内存(`IndustryConcepts.usageRules` Map)
- 运行时只是字符串拼接,无数据库查询
- Prompt长度增加约200-500字符,对LLM推理时间影响<100ms

---

## 📊 效果对比

### 配置前
```
用户: "查询张三的用户名和订单"

LLM思考:
  - orders表有receiver_name='张三' ✓
  - 不需要其他表 ✗ (错误!)
  
生成SQL:
  SELECT receiver_name, order_no FROM orders WHERE receiver_name='张三'
  
结果: ❌ 没有username字段!
```

### 配置后
```
用户: "查询张三的用户名和订单"

Prompt注入:
  ⚠️ **表使用场景区分（重要）**：
     - user_addresses是地址表...users是用户主表(存储username...)。
       当需要用户名时，必须通过orders.user_id→users.id关联users表

LLM思考:
  - orders表有receiver_name='张三' ✓
  - 但需要username字段 → 查看usage_rule → username在users表
  - 查看关联关系 → orders.user_id → users.id ✓
  - 返回missing_tables: ["users"]
  
第二轮迭代:
  - 补充users表schema
  - 生成正确SQL:
    SELECT u.username, o.order_no 
    FROM orders o 
    JOIN users u ON o.user_id = u.id 
    WHERE u.real_name = '张三'
  
结果: ✅ 成功!
```

---

## 🎓 最佳实践

1. **优先配置metric和dimension**: 这俩对SQL生成影响最大
2. **usage_rule按需添加**: 只在LLM频繁混淆时才配置
3. **alias尽量全面**: 覆盖用户可能的各种说法
4. **description要具体**: 列出字段名、表名、关联方式
5. **定期review**: 根据低分反馈优化配置

---

## 📚 参考资料

- 电商配置示例: `init_ecommerce_industry_concepts.sql`
- 前端管理页面: `admin-industry-concepts.html`
- 后端Controller: `IndustryConceptAdminController.java`
- Core Service: `IndustryConceptDictionary.java`
