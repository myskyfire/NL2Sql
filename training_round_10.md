# NL2SQL Training - Round 10

**Date**: 2026-04-21 08:36:15
**Average Score**: 3.90 / 5.0

| # | Question | Score | Expected SQL | Actual SQL | Issues |
|---|----------|-------|--------------|------------|--------|
| 1 | 用户孙七的地址 | 5 | ```SELECT ua.province, ua.city FROM users u JOIN user_addresses ua ON u.id = ua.user_id WHERE u.real_name = '孙七'``` | ```SELECT ua.receiver_name AS '收货人姓名', ua.receiver_phone AS '收货人电话', ua.province AS '省份', ua.city AS '城市', ua.district AS '区县', ua.detail_address AS '详细地址', ua.is_default AS '是否默认地址' FROM users u JOIN user_addresses ua ON u.id = ua.user_id WHERE u.username = '孙七';``` |  |
| 2 | MacBook Pro 14所属分类 | 5 | ```SELECT c.category_name FROM products p JOIN product_categories c ON p.category_id = c.id WHERE p.product_name = 'MacBook Pro 14'``` | ```SELECT category_name AS '分类名称', COUNT(*) AS '商品数量' FROM product_categories pc JOIN products p ON pc.id = p.category_id WHERE p.product_name LIKE '%MacBook Pro 14%' GROUP BY pc.id, pc.category_name LIMIT 10;``` |  |
| 3 | 王五的订单总金额 | 4 | ```SELECT SUM(o.total_amount) FROM orders o JOIN users u ON o.user_id = u.id WHERE u.real_name = '王五'``` | ```SELECT SUM(actual_amount) AS '总金额' FROM orders WHERE user_id IN (SELECT user_id FROM users WHERE receiver_name = '王五');``` | Missing JOIN |
| 4 | 四川省用户订单数量 | 1 | ```SELECT COUNT(*) FROM orders o JOIN user_addresses ua ON o.user_id = ua.user_id WHERE ua.province = '四川省'``` | ```Invalid Response``` | No SQL generated |
| 5 | 服装服饰类商品均价 | 5 | ```SELECT AVG(price) FROM products WHERE category_id = 4``` | ```SELECT AVG(products.price) AS '服装服饰类商品均价' FROM order_items JOIN products ON order_items.product_id = products.id WHERE products.category_id IN (1, 2, 3);``` |  |
| 6 | 福建省用户购买的订单总额 | 4 | ```SELECT SUM(o.total_amount) FROM orders o JOIN user_addresses ua ON o.user_id = ua.user_id WHERE ua.province = '福建省'``` | ```SELECT users.province AS '省份', SUM(orders.actual_amount) AS '订单总额' FROM orders JOIN users ON orders.user_id = users.id WHERE users.province = '福建' GROUP BY users.province;``` | Missing table: user_addresses |
| 7 | 广东省用户购买的订单总额 | 4 | ```SELECT SUM(o.total_amount) FROM orders o JOIN user_addresses ua ON o.user_id = ua.user_id WHERE ua.province = '广东省'``` | ```SELECT ua.province AS '省份', SUM(o.actual_amount) AS '订单总金额' FROM orders o JOIN user_addresses ua ON o.user_id = ua.user_id AND ua.province = '广东省' GROUP BY ua.province;``` | Missing WHERE |
| 8 | 待支付订单数量 | 5 | ```SELECT COUNT(*) FROM orders WHERE status = 'pending'``` | ```SELECT COUNT(*) AS '待支付订单数量' FROM orders WHERE status = 'pending';``` |  |
| 9 | 订单金额大于5000的数量 | 5 | ```SELECT COUNT(*) FROM orders WHERE total_amount > 5000``` | ```SELECT COUNT(*) AS '订单数量' FROM orders WHERE actual_amount > 5000;``` |  |
| 10 | 最低订单金额 | 1 | ```SELECT MIN(total_amount) FROM orders``` | ```Invalid Response``` | No SQL generated |
