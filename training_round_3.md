# NL2SQL Training - Round 3

**Date**: 2026-04-21 08:47:45
**Average Score**: 2.30 / 5.0

| # | Question | Score | Expected SQL | Actual SQL | Issues |
|---|----------|-------|--------------|------------|--------|
| 1 | 江苏省用户购买的订单总额 | 1 | ```SELECT SUM(o.total_amount) FROM orders o JOIN user_addresses ua ON o.user_id = ua.user_id WHERE ua.province = '江苏省'``` | ```SELECT u.province AS '省份', SUM(o.actual_amount) AS '订单总金额' FROM orders o JOIN users u ON o.user_id = u.id WHERE u.province = '江苏' GROUP BY u.province;``` | Missing tables: user_addresses |
| 2 | 成都市用户数量 | 5 | ```SELECT COUNT(*) FROM user_addresses WHERE city = '成都市'``` | ```SELECT COUNT(*) AS '成都城市用户数量' FROM user_addresses ua WHERE ua.province = '四川省' AND ua.city = '成都市';``` |  |
| 3 | 华为Mate 60 Pro所属分类 | 2 | ```SELECT c.category_name FROM products p JOIN product_categories c ON p.category_id = c.id WHERE p.product_name = '华为Mate 60 Pro'``` | ```SELECT category_name AS '分类名称' FROM product_categories WHERE id IN (SELECT category_id FROM products WHERE product_name LIKE '%华为Mate 60 Pro%') LIMIT 1``` | Missing function: JOIN |
| 4 | 上海市用户订单数量 | 1 | ```SELECT COUNT(*) FROM orders o JOIN user_addresses ua ON o.user_id = ua.user_id WHERE ua.city = '上海市'``` | ```SELECT COUNT(*) AS '上海城市用户订单数量' FROM orders WHERE shipping_address LIKE '%上海市%' AND status IN ('paid', 'shipped', 'completed');``` | Missing tables: user_addresses |
| 5 | 用户孙七的地址 | 5 | ```SELECT ua.province, ua.city FROM users u JOIN user_addresses ua ON u.id = ua.user_id WHERE u.real_name = '孙七'``` | ```SELECT ua.receiver_name AS '收货人姓名', ua.receiver_phone AS '收货人电话', ua.province AS '省份', ua.city AS '城市', ua.district AS '区县', ua.detail_address AS '详细地址', ua.is_default AS '是否默认地址' FROM users u JOIN user_addresses ua ON u.id = ua.user_id WHERE u.username = '孙七';``` |  |
| 6 | 小米14 Pro的分类 | 1 | ```SELECT c.category_name FROM products p JOIN product_categories c ON p.category_id = c.id WHERE p.product_name = '小米14 Pro'``` | ```SELECT category_name AS '分类名称', COUNT(*) AS '产品数量' FROM product_categories pc WHERE 1=1 AND (pc.parent_id = 0 OR pc.id IN ( SELECT pc2.id FROM product_categories pc2 WHERE pc2.parent_id = pc.id )) GROUP BY pc.category_name ORDER BY category_name ASC LIMIT 10;``` | Missing tables: products |
| 7 | 杭州市用户数量 | 1 | ```SELECT COUNT(*) FROM user_addresses WHERE city = '杭州市'``` | ```SELECT u.province AS '省份', COUNT(u.id) AS '用户数量' FROM users u JOIN orders o ON u.id = o.user_id WHERE u.city = '杭州' GROUP BY u.province;``` | Missing tables: user_addresses |
| 8 | 武汉市用户数量 | 1 | ```SELECT COUNT(*) FROM user_addresses WHERE city = '武汉市'``` | ```SELECT COUNT(*) AS '武汉城市用户数量' FROM users u WHERE u.city = '武汉市';``` | Missing tables: user_addresses |
| 9 | 不同状态订单总额 | 5 | ```SELECT status, SUM(total_amount) FROM orders GROUP BY status``` | ```SELECT status AS '订单状态', SUM(actual_amount) AS '订单总额' FROM orders GROUP BY status;``` |  |
| 10 | 孙七的订单总金额 | 1 | ```SELECT SUM(o.total_amount) FROM orders o JOIN users u ON o.user_id = u.id WHERE u.real_name = '孙七'``` | ```Invalid Response``` | No SQL generated |
