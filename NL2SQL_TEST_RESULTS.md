# NL2SQL Test Results

**Time**: 2026-04-21 06:52:40
**Datasource**: Docker Trade DB (ID=1)
**Timeout**: 180s

| ID | Question | Expected SQL | Actual SQL | Match |
|----|----------|--------------|------------|-------|
| 1 | total order count | ```SELECT COUNT(*) FROM orders``` | ```SELECT DATE_FORMAT(orders.created_at, '%Y-%m-%d') AS '订单日期', COUNT(*) AS '订单数量' FROM orders GROUP BY DATE_FORMAT(orders.created_at, '%Y-%m-%d');``` | Partial: Expected simple COUNT but has GROUP BY |
| 2 | order count by status | ```SELECT status, COUNT(*) FROM orders GROUP BY status``` | ```SELECT status AS '订单状态', COUNT(*) AS '订单数量' FROM orders GROUP BY status;``` | Yes |
| 3 | total amount for Guangdong users | ```SELECT SUM(actual_amount) FROM orders JOIN users WHERE province = Guangdong``` | ```SELECT o.id AS '订单ID', o.order_no AS '订单号', o.user_id AS '用户ID', SUM(oi.quantity) AS '购买数量', SUM(oi.subtotal) AS '总金额' FROM orders o JOIN order_items oi ON o.id = oi.order_id WHERE o.status = 'completed' AND o.user_id IN ( SELECT user_id FROM orders WHERE shipping_address LIKE '%Guangdong%' ) GROUP BY o.id, o.order_no, o.user_id ORDER BY SUM(oi.quantity) DESC LIMIT 10;``` | No: Missing users table; Expected no LIMIT but has limit; Expected actual_amount not found |
| 4 | top 5 products by sales | ```SELECT product_name, SUM(subtotal) FROM order_items JOIN products GROUP BY product_name ORDER BY SUM DESC LIMIT 5``` | ```SELECT product_name AS '商品名称', SUM(quantity) AS '销售数量' FROM order_items GROUP BY product_id, product_name ORDER BY SUM(quantity) DESC LIMIT 5;``` | No: Missing products table; Expected subtotal but uses quantity |
| 5 | user count by province | ```SELECT province, COUNT(*) FROM users GROUP BY province``` | ```SELECT ua.province AS '省份', COUNT(*) AS '用户数量' FROM user_addresses ua JOIN users u ON ua.user_id = u.id GROUP BY ua.province LIMIT 100;``` | Partial: Expected no LIMIT but has limit |
| 6 | 10 products with lowest inventory | ```SELECT product_name, available_quantity FROM inventory JOIN products ORDER BY available_quantity ASC LIMIT 10``` | ```SELECT p.product_name AS '商品名称', i.warehouse_name AS '仓库名称', i.stock_quantity AS '库存总量', i.locked_quantity AS '锁定库存', i.available_quantity AS '可用库存' FROM inventory i JOIN products p ON i.product_id = p.id ORDER BY i.available_quantity ASC LIMIT 10;``` | Yes |
| 7 | all orders for user Zhang San | ```SELECT order_no, total_amount, status FROM orders JOIN users WHERE real_name = Zhang San``` | ```Error: success``` | Failed |
| 8 | products with return records | ```SELECT product_name, COUNT(returns.id) FROM returns JOIN orders JOIN order_items JOIN products GROUP BY product_name``` | ```SELECT o.order_no AS '订单号', DATE_FORMAT(o.created_at, '%Y-%m-%d') AS '下单日期', COUNT(ri.id) AS '退货记录数' FROM orders o JOIN returns r ON o.id = r.order_id LEFT JOIN order_items ri ON o.id = ri.order_id AND ri.return_status != 'none' GROUP BY o.order_no, DATE_FORMAT(o.created_at, '%Y-%m-%d') ORDER BY '下单日期' DESC;``` | No: Missing products table |
| 9 | total sales by category | ```SELECT category_name, SUM(subtotal) FROM order_items JOIN products JOIN product_categories GROUP BY category_name``` | ```SELECT p.category_id AS '分类ID', COUNT(oi.id) AS '销售数量' FROM order_items oi JOIN products p ON oi.product_id = p.id GROUP BY p.category_id;``` | Yes |
| 10 | daily order count last 7 days | ```SELECT DATE(created_at), COUNT(*) FROM orders WHERE created_at >= DATE_SUB CURDATE INTERVAL 7 DAY GROUP BY DATE``` | ```SELECT DATE_FORMAT(created_at, '%Y-%m-%d') AS '订单日期', COUNT(*) AS '订单数量' FROM orders WHERE created_at >= NOW() - INTERVAL 7 DAY GROUP BY DATE_FORMAT(created_at, '%Y-%m-%d');``` | Yes |

## Summary

- **Total**: 10
- **Fully Match**: 4
- **Partial Match**: 2
- **Timeout**: 0
- **Failed**: 4
- **Success Rate**: 40%

