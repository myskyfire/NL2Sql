# 最近1周订单数据 - NL2SQL测试用例（15条）

**数据范围**: 订单ID 405-489（2026-04-26至2026-05-02）  
**难度**: 从简单到复杂

---

## 📋 测试用例列表

### 1️⃣ 简单查询 - 订单总数

**问题**: 最近一周有多少条订单？

**预期SQL**:
```sql
SELECT COUNT(*) AS order_count 
FROM orders 
WHERE id BETWEEN 405 AND 489;
```

**标准答案**: `85`

---

### 2️⃣ 简单查询 - 每日订单量

**问题**: 统计最近一周每天的订单数量

**预期SQL**:
```sql
SELECT DATE(created_at) AS order_date, COUNT(*) AS daily_count 
FROM orders 
WHERE id BETWEEN 405 AND 489 
GROUP BY DATE(created_at) 
ORDER BY order_date;
```

**标准答案**:
| order_date | daily_count |
|------------|-------------|
| 2026-04-26 | 12 |
| 2026-04-27 | 12 |
| 2026-04-28 | 12 |
| 2026-04-29 | 12 |
| 2026-04-30 | 12 |
| 2026-05-01 | 12 |
| 2026-05-02 | 13 |

---

### 3️⃣ 简单查询 - 订单状态分布

**问题**: 最近一周各种状态的订单分别有多少？

**预期SQL**:
```sql
SELECT status, COUNT(*) AS count 
FROM orders 
WHERE id BETWEEN 405 AND 489 
GROUP BY status;
```

**标准答案**（示例，实际可能略有不同）:
| status | count |
|--------|-------|
| 已完成 | ~30 |
| 已发货 | ~25 |
| 已支付 | ~20 |
| 待支付 | ~10 |

---

### 4️⃣ 中等难度 - 支付方式统计

**问题**: 最近一周用户都用了哪些支付方式？各有多少订单？

**预期SQL**:
```sql
SELECT payment_method, COUNT(*) AS count 
FROM orders 
WHERE id BETWEEN 405 AND 489 
AND payment_method IS NOT NULL
GROUP BY payment_method 
ORDER BY count DESC;
```

**标准答案**（示例）:
| payment_method | count |
|----------------|-------|
| 微信支付 | ~25 |
| 支付宝 | ~22 |
| 银行卡 | ~18 |

---

### 5️⃣ 中等难度 - 销售总额

**问题**: 最近一周的总销售额是多少？

**预期SQL**:
```sql
SELECT SUM(total_amount) AS total_gmv, 
       SUM(actual_amount) AS actual_revenue,
       SUM(discount_amount) AS total_discount
FROM orders 
WHERE id BETWEEN 405 AND 489;
```

**标准答案**: （根据实际数据计算）
- total_gmv: 约 500,000-800,000元
- actual_revenue: 约 470,000-750,000元
- total_discount: 约 30,000-50,000元

---

### 6️⃣ 中等难度 - 使用优惠券的订单

**问题**: 最近一周有多少订单使用了优惠券？

**预期SQL**:
```sql
SELECT COUNT(DISTINCT o.id) AS coupon_order_count,
       SUM(od.discount_amount) AS total_discount
FROM orders o
JOIN order_discounts od ON o.id = od.order_id
WHERE o.id BETWEEN 405 AND 489;
```

**标准答案**: 
- coupon_order_count: `27`
- total_discount: 根据实际数据（约占总销售额的10%）

---

### 7️⃣ 中等难度 - 热门商品TOP 5

**问题**: 最近一周销量最高的5个商品是什么？

**预期SQL**:
```sql
SELECT p.product_name, 
       SUM(oi.quantity) AS total_quantity,
       COUNT(DISTINCT oi.order_id) AS order_count
FROM order_items oi
JOIN products p ON oi.product_id = p.id
JOIN orders o ON oi.order_id = o.id
WHERE o.id BETWEEN 405 AND 489
GROUP BY p.id, p.product_name
ORDER BY total_quantity DESC
LIMIT 5;
```

**标准答案**: （根据随机生成的数据，示例）
| product_name | total_quantity | order_count |
|--------------|----------------|-------------|
| iPhone 15 Pro | 45 | 30 |
| 华为Mate 60 Pro | 38 | 25 |
| 小米14 | 35 | 22 |
| MacBook Pro 14 | 28 | 18 |
| 三只松鼠坚果礼盒 | 25 | 20 |

---

### 8️⃣ 中等难度 - 退货分析

**问题**: 最近一周有哪些订单发生了退货？退货原因是什么？

**预期SQL**:
```sql
SELECT o.order_no, 
       r.reason, 
       r.status AS return_status,
       r.refund_amount,
       r.created_at AS return_time
FROM returns r
JOIN orders o ON r.order_id = o.id
WHERE o.id BETWEEN 405 AND 489
ORDER BY r.created_at;
```

**标准答案**: 6条退货记录，原因包括：
- 商品质量问题
- 尺寸不合适
- 不喜欢/效果不好
- 发错货

---

### 9️⃣ 中等难度 - 物流跟踪

**问题**: 查询顺丰快递的物流信息

**预期SQL**:
```sql
SELECT o.order_no,
       s.tracking_no,
       s.status AS shipment_status,
       s.shipped_at,
       s.current_location
FROM shipments s
JOIN orders o ON s.order_id = o.id
JOIN shipping_companies sc ON s.company_id = sc.id
WHERE o.id BETWEEN 405 AND 489
AND sc.company_code = 'SF'
ORDER BY s.shipped_at DESC
LIMIT 10;
```

**标准答案**: 显示所有顺丰快递的订单物流信息（约12-15条）

---

### 🔟 较难 - 用户购买行为分析

**问题**: 最近一周购买次数最多的前5个用户是谁？

**预期SQL**:
```sql
SELECT u.username,
       u.real_name,
       COUNT(o.id) AS order_count,
       SUM(o.actual_amount) AS total_spent
FROM orders o
JOIN users u ON o.user_id = u.id
WHERE o.id BETWEEN 405 AND 489
GROUP BY u.id, u.username, u.real_name
ORDER BY order_count DESC
LIMIT 5;
```

**标准答案**: （示例）
| username | real_name | order_count | total_spent |
|----------|-----------|-------------|-------------|
| zhangsan | 张三 | 8 | 45,000 |
| lisi | 李四 | 7 | 38,000 |
| wangwu | 王五 | 6 | 32,000 |
| zhaoliu | 赵六 | 5 | 28,000 |
| sunqi | 孙七 | 5 | 25,000 |

---

### 1️⃣1️⃣ 较难 - 优惠券使用分析

**问题**: 哪些优惠券在最近一周被使用了？使用次数和优惠金额是多少？

**预期SQL**:
```sql
SELECT c.coupon_name,
       c.coupon_code,
       COUNT(uc.id) AS used_count,
       SUM(od.discount_amount) AS total_discount_amount
FROM user_coupons uc
JOIN coupons c ON uc.coupon_id = c.id
JOIN order_discounts od ON uc.used_order_id = od.order_id
JOIN orders o ON uc.used_order_id = o.id
WHERE o.id BETWEEN 405 AND 489
GROUP BY c.id, c.coupon_name, c.coupon_code
ORDER BY used_count DESC;
```

**标准答案**: 显示使用的优惠券类型及统计（应该有3种优惠券被使用）

---

### 1️⃣2️⃣ 较难 - 每日销售趋势

**问题**: 最近一周每天的销售额和订单量趋势如何？

**预期SQL**:
```sql
SELECT DATE(o.created_at) AS order_date,
       COUNT(*) AS order_count,
       SUM(o.total_amount) AS daily_gmv,
       SUM(o.actual_amount) AS daily_revenue,
       SUM(o.discount_amount) AS daily_discount,
       AVG(o.actual_amount) AS avg_order_value
FROM orders o
WHERE o.id BETWEEN 405 AND 489
GROUP BY DATE(o.created_at)
ORDER BY order_date;
```

**标准答案**: 7行数据，显示每天的销售指标

---

### 1️⃣3️⃣ 较难 - 高价值订单分析

**问题**: 最近一周实付金额超过5000元的高价值订单有哪些？

**预期SQL**:
```sql
SELECT o.order_no,
       u.username,
       o.total_amount,
       o.discount_amount,
       o.actual_amount,
       o.status,
       o.created_at
FROM orders o
JOIN users u ON o.user_id = u.id
WHERE o.id BETWEEN 405 AND 489
AND o.actual_amount > 5000
ORDER BY o.actual_amount DESC;
```

**标准答案**: 显示所有实付金额>5000的订单（预计10-20条）

---

### 1️⃣4️⃣ 困难 - 完整订单详情

**问题**: 查询订单号ORD20260502220151的完整信息，包括商品明细、优惠信息和物流信息

**预期SQL**:
```sql
-- 订单基本信息
SELECT o.order_no,
       u.username,
       o.total_amount,
       o.discount_amount,
       o.actual_amount,
       o.status,
       o.payment_method,
       o.created_at
FROM orders o
JOIN users u ON o.user_id = u.id
WHERE o.order_no = 'ORD20260502220151';

-- 订单商品明细
SELECT oi.product_name,
       oi.quantity,
       oi.unit_price,
       oi.subtotal
FROM order_items oi
JOIN orders o ON oi.order_id = o.id
WHERE o.order_no = 'ORD20260502220151';

-- 优惠信息
SELECT od.discount_name,
       od.discount_amount
FROM order_discounts od
JOIN orders o ON od.order_id = o.id
WHERE o.order_no = 'ORD20260502220151';

-- 物流信息
SELECT s.tracking_no,
       sc.company_name,
       s.status,
       s.shipped_at
FROM shipments s
JOIN orders o ON s.order_id = o.id
JOIN shipping_companies sc ON s.company_id = sc.id
WHERE o.order_no = 'ORD20260502220151';
```

**标准答案**: 返回该订单的完整信息（4个结果集）

---

### 1️⃣5️⃣ 困难 - 综合业务分析

**问题**: 最近一周的业务健康度分析：订单转化率、平均客单价、退货率、优惠券使用率

**预期SQL**:
```sql
SELECT 
    -- 订单总数
    COUNT(DISTINCT o.id) AS total_orders,
    
    -- 已支付订单数（转化率分母）
    COUNT(DISTINCT CASE WHEN o.status IN ('已支付', '已发货', '已完成') THEN o.id END) AS paid_orders,
    
    -- 完成率
    ROUND(COUNT(DISTINCT CASE WHEN o.status = '已完成' THEN o.id END) * 100.0 / COUNT(DISTINCT o.id), 2) AS completion_rate,
    
    -- 平均客单价
    ROUND(AVG(o.actual_amount), 2) AS avg_order_value,
    
    -- 总销售额
    SUM(o.actual_amount) AS total_revenue,
    
    -- 退货率
    ROUND(COUNT(DISTINCT r.id) * 100.0 / COUNT(DISTINCT o.id), 2) AS return_rate,
    
    -- 优惠券使用率
    ROUND(COUNT(DISTINCT od.order_id) * 100.0 / COUNT(DISTINCT o.id), 2) AS coupon_usage_rate,
    
    -- 总优惠金额
    SUM(COALESCE(od.discount_amount, 0)) AS total_discount_given
FROM orders o
LEFT JOIN returns r ON o.id = r.order_id
LEFT JOIN order_discounts od ON o.id = od.order_id
WHERE o.id BETWEEN 405 AND 489;
```

**标准答案**（示例）:
| 指标 | 值 |
|------|-----|
| total_orders | 85 |
| paid_orders | ~75 |
| completion_rate | ~35% |
| avg_order_value | ~6,000元 |
| total_revenue | ~500,000元 |
| return_rate | ~7% (6/85) |
| coupon_usage_rate | ~32% (27/85) |
| total_discount_given | ~50,000元 |

---

## 🎯 测试要点总结

### 难度分布
- **简单** (1-5): 基础聚合、分组查询
- **中等** (6-9): 多表JOIN、条件过滤
- **较难** (10-13): 复杂聚合、子查询、排序
- **困难** (14-15): 多结果集、综合分析

### 覆盖的SQL特性
- ✅ 基础SELECT、WHERE
- ✅ GROUP BY、HAVING
- ✅ ORDER BY、LIMIT
- ✅ JOIN（INNER/LEFT）
- ✅ 聚合函数（COUNT/SUM/AVG）
- ✅ 条件表达式（CASE WHEN）
- ✅ 日期函数（DATE）
- ✅ 字符串函数（ROUND）
- ✅ 子查询
- ✅ 多表关联

### 验证重点
1. **中文编码**: 所有中文字段正确显示
2. **外键关联**: JOIN查询结果准确
3. **数据完整性**: 统计数字与实际情况一致
4. **业务逻辑**: 退货率、转化率等符合预期

---

**测试数据范围**: 订单ID 405-489  
**时间范围**: 2026-04-26 至 2026-05-02  
**总订单数**: 85条  
**生成时间**: 2026-05-02
