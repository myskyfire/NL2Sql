# NL2SQL Training - Round 6

**Date**: 2026-04-21 08:59:24
**Average Score**: 3.00 / 5.0

| # | Question | Score | Expected SQL | Actual SQL | Issues |
|---|----------|-------|--------------|------------|--------|
| 1 | 王五的订单总金额 | 5 | ```SELECT SUM(total_amount) FROM orders WHERE user_id = 3``` | ```SELECT SUM(actual_amount) AS '总金额' FROM orders WHERE user_id IN (SELECT user_id FROM users WHERE receiver_name = '王五');``` |  |
| 2 | 订单ORD20260401110005的用户 | 5 | ```SELECT u.real_name FROM orders o JOIN users u ON o.user_id = u.id WHERE o.order_no = 'ORD20260401110005'``` | ```SELECT u.id AS '用户ID', u.username AS '用户名', u.real_name AS '真实姓名', u.gender AS '性别', u.phone AS '手机号', u.email AS '邮箱', u.province AS '省份', u.city AS '城市', u.district AS '区县', u.created_at AS '创建时间', u.updated_at AS '更新时间' FROM orders o JOIN users u ON o.user_id = u.id WHERE o.order_no = 'ORD20260401110005';``` |  |
| 3 | 所有订单总金额 | 5 | ```SELECT SUM(total_amount) FROM orders``` | ```SELECT SUM(actual_amount) AS '所有订单总金额' FROM orders;``` |  |
| 4 | 索尼65寸电视的信息 | 1 | ```SELECT * FROM products WHERE product_name = '索尼65寸电视'``` | ```Invalid Response``` | No SQL generated |
| 5 | 孙七的订单总金额 | 1 | ```SELECT SUM(total_amount) FROM orders WHERE user_id = 5``` | ```Invalid Response``` | No SQL generated |
| 6 | 郑十的订单 | 1 | ```SELECT * FROM orders WHERE user_id = 8``` | ```Invalid Response``` | No SQL generated |
| 7 | 湖北省用户的订单 | 1 | ```SELECT o.* FROM orders o JOIN user_addresses ua ON o.user_id = ua.user_id WHERE ua.province = '湖北省'``` | ```SELECT u.username AS '用户名', o.order_no AS '订单号', o.total_amount AS '总金额', o.status AS '状态' FROM orders o JOIN users u ON o.user_id = u.id WHERE u.province = '湖北' LIMIT 10;``` | Missing tables: user_addresses |
| 8 | 江苏省用户订单数量 | 5 | ```SELECT COUNT(*) FROM orders o JOIN user_addresses ua ON o.user_id = ua.user_id WHERE ua.province = '江苏省'``` | ```SELECT users.province AS '省份', COUNT(orders.id) AS '订单数量' FROM orders JOIN user_addresses ON orders.user_id = user_addresses.user_id AND user_addresses.is_default = 1 JOIN users ON orders.user_id = users.id WHERE users.province = '江苏' GROUP BY users.province;``` |  |
| 9 | 北京市用户订单数量 | 1 | ```SELECT COUNT(*) FROM orders o JOIN user_addresses ua ON o.user_id = ua.user_id WHERE ua.city = '北京市'``` | ```SELECT COUNT(*) AS '北京城市用户订单数量' FROM orders WHERE shipping_address LIKE '%北京市%' AND status IN ('paid', 'shipped', 'completed')``` | Missing tables: user_addresses |
| 10 | 订单总数 | 5 | ```SELECT COUNT(*) FROM orders``` | ```SELECT COUNT(*) AS '订单数量' FROM orders WHERE status IN ('paid', 'shipped', 'completed');``` |  |
