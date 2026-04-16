-- ========================================
-- 生成4月11日-4月15日的订单数据
-- 确保 orders 和 order_items 表的数据完整性
-- 基于实际数据库数据构造
-- ========================================

USE trade;

-- 现有数据统计：
-- users表: id 1-10 (zhangsan, lisi, wangwu, zhaoliu, sunqi, zhouba, wujiu, zhengshi, chenyi, liner)
-- products表: id 1-10 (华为Mate 60 Pro 6999, 小米14 Pro 4999, iPhone 15 Pro 8999, ...)
-- orders表: 当前最大ID=188，共188条记录
-- ========================================

-- ========================================
-- 2. 插入订单数据（4月11日-4月15日，每天5-10条）
-- ========================================

-- 4月11日订单（7条）
INSERT INTO orders (order_no, user_id, shipping_address, total_amount, actual_amount, status, payment_method, created_at) VALUES
('ORD20240411091523001', 1, '北京市朝阳区建国路88号', 6999.00, 6999.00, 'completed', 'alipay', '2024-04-11 09:15:23'),
('ORD20240411102211002', 2, '上海市浦东新区陆家嘴环路1000号', 4999.00, 4499.10, 'completed', 'wechat', '2024-04-11 10:22:11'),
('ORD20240411110547003', 3, '广州市天河区天河路208号', 8999.00, 8999.00, 'completed', 'credit_card', '2024-04-11 11:05:47'),
('ORD20240411143055004', 4, '深圳市南山区科技南路88号', 4299.00, 3869.10, 'completed', 'alipay', '2024-04-11 14:30:55'),
('ORD20240411154533005', 5, '杭州市西湖区文三路100号', 4999.00, 4999.00, 'completed', 'wechat', '2024-04-11 15:45:33'),
('ORD20240411162008006', 6, '成都市武侯区人民南路三段1号', 14999.00, 13499.10, 'completed', 'credit_card', '2024-04-11 16:20:08'),
('ORD20240411171042007', 7, '武汉市江汉区解放大道688号', 9999.00, 8999.10, 'completed', 'alipay', '2024-04-11 17:10:42');

-- 4月12日订单（8条）
INSERT INTO orders (order_no, user_id, shipping_address, total_amount, actual_amount, status, payment_method, created_at) VALUES
('ORD20240412083015008', 8, '南京市鼓楼区中山路200号', 12999.00, 11699.10, 'completed', 'alipay', '2024-04-12 08:30:15'),
('ORD20240412094522009', 9, '西安市雁塔区长安中路100号', 8999.00, 8999.00, 'completed', 'wechat', '2024-04-12 09:45:22'),
('ORD20240412101538010', 10, '重庆市渝中区解放碑步行街', 5999.00, 5399.10, 'completed', 'credit_card', '2024-04-12 10:15:38'),
('ORD20240412112045011', 1, '北京市朝阳区建国路88号', 4999.00, 4999.00, 'completed', 'alipay', '2024-04-12 11:20:45'),
('ORD20240412133512012', 3, '广州市天河区天河路208号', 6999.00, 6299.10, 'completed', 'wechat', '2024-04-12 13:35:12'),
('ORD20240412145028013', 5, '杭州市西湖区文三路100号', 4299.00, 4299.00, 'completed', 'credit_card', '2024-04-12 14:50:28'),
('ORD20240412160533014', 7, '武汉市江汉区解放大道688号', 14999.00, 13499.10, 'completed', 'alipay', '2024-04-12 16:05:33'),
('ORD20240412172547015', 2, '上海市浦东新区陆家嘴环路1000号', 9999.00, 8999.10, 'completed', 'wechat', '2024-04-12 17:25:47');

-- 4月13日订单（8条）
INSERT INTO orders (order_no, user_id, shipping_address, total_amount, actual_amount, status, payment_method, created_at) VALUES
('ORD20240413091025016', 4, '深圳市南山区科技南路88号', 8999.00, 8099.10, 'completed', 'alipay', '2024-04-13 09:10:25'),
('ORD20240413102538017', 6, '成都市武侯区人民南路三段1号', 4999.00, 4499.10, 'completed', 'wechat', '2024-04-13 10:25:38'),
('ORD20240413114012018', 8, '南京市鼓楼区中山路200号', 12999.00, 12999.00, 'completed', 'credit_card', '2024-04-13 11:40:12'),
('ORD20240413131545019', 10, '重庆市渝中区解放碑步行街', 4299.00, 4299.00, 'completed', 'alipay', '2024-04-13 13:15:45'),
('ORD20240413143022020', 1, '北京市朝阳区建国路88号', 6999.00, 6299.10, 'completed', 'wechat', '2024-04-13 14:30:22'),
('ORD20240413154538021', 3, '广州市天河区天河路208号', 9999.00, 9999.00, 'completed', 'credit_card', '2024-04-13 15:45:38'),
('ORD20240413165015022', 5, '杭州市西湖区文三路100号', 14999.00, 13499.10, 'completed', 'alipay', '2024-04-13 16:50:15'),
('ORD20240413173542023', 7, '武汉市江汉区解放大道688号', 5999.00, 5399.10, 'completed', 'wechat', '2024-04-13 17:35:42');

-- 4月14日订单（9条）
INSERT INTO orders (order_no, user_id, shipping_address, total_amount, actual_amount, status, payment_method, created_at) VALUES
('ORD20240414084518024', 2, '上海市浦东新区陆家嘴环路1000号', 4999.00, 4499.10, 'completed', 'alipay', '2024-04-14 08:45:18'),
('ORD20240414095032025', 9, '西安市雁塔区长安中路100号', 8999.00, 8999.00, 'completed', 'wechat', '2024-04-14 09:50:32'),
('ORD20240414103547026', 4, '深圳市南山区科技南路88号', 12999.00, 11699.10, 'completed', 'credit_card', '2024-04-14 10:35:47'),
('ORD20240414112015027', 6, '成都市武侯区人民南路三段1号', 6999.00, 6999.00, 'completed', 'alipay', '2024-04-14 11:20:15'),
('ORD20240414134528028', 8, '南京市鼓楼区中山路200号', 4299.00, 3869.10, 'completed', 'wechat', '2024-04-14 13:45:28'),
('ORD20240414143042029', 10, '重庆市渝中区解放碑步行街', 9999.00, 9999.00, 'completed', 'credit_card', '2024-04-14 14:30:42'),
('ORD20240414155518030', 1, '北京市朝阳区建国路88号', 14999.00, 13499.10, 'completed', 'alipay', '2024-04-14 15:55:18'),
('ORD20240414164035031', 3, '广州市天河区天河路208号', 5999.00, 5399.10, 'completed', 'wechat', '2024-04-14 16:40:35'),
('ORD20240414172548032', 5, '杭州市西湖区文三路100号', 4999.00, 4999.00, 'completed', 'credit_card', '2024-04-14 17:25:48');

-- 4月15日订单（7条，今天）
INSERT INTO orders (order_no, user_id, shipping_address, total_amount, actual_amount, status, payment_method, created_at) VALUES
('ORD20240415090522033', 7, '武汉市江汉区解放大道688号', 8999.00, 8099.10, 'completed', 'alipay', '2024-04-15 09:05:22'),
('ORD20240415101538034', 9, '西安市雁塔区长安中路100号', 12999.00, 12999.00, 'completed', 'wechat', '2024-04-15 10:15:38'),
('ORD20240415113045035', 2, '上海市浦东新区陆家嘴环路1000号', 4299.00, 4299.00, 'completed', 'credit_card', '2024-04-15 11:30:45'),
('ORD20240415132012036', 4, '深圳市南山区科技南路88号', 6999.00, 6299.10, 'completed', 'alipay', '2024-04-15 13:20:12'),
('ORD20240415144528037', 6, '成都市武侯区人民南路三段1号', 9999.00, 9999.00, 'completed', 'wechat', '2024-04-15 14:45:28'),
('ORD20240415153035038', 8, '南京市鼓楼区中山路200号', 14999.00, 13499.10, 'completed', 'credit_card', '2024-04-15 15:30:35'),
('ORD20240415161542039', 10, '重庆市渝中区解放碑步行街', 5999.00, 5399.10, 'completed', 'alipay', '2024-04-15 16:15:42');

-- ========================================
-- 3. 插入订单项数据（order_items）
-- 每个订单对应1个商品项，product_id使用真实存在的商品
-- ========================================

-- 获取新插入的订单ID范围
SET @min_order_id = (SELECT MIN(id) FROM orders WHERE created_at >= '2024-04-11');

-- 为每个订单添加商品项（39条订单项）
INSERT INTO order_items (order_id, product_id, quantity, unit_price, subtotal) VALUES
-- 4月11日订单的商品项（7条）
(@min_order_id + 0, 1, 1, 6999.00, 6999.00),   -- 华为Mate 60 Pro
(@min_order_id + 1, 2, 1, 4999.00, 4999.00),   -- 小米14 Pro
(@min_order_id + 2, 3, 1, 8999.00, 8999.00),   -- iPhone 15 Pro
(@min_order_id + 3, 4, 1, 4299.00, 4299.00),   -- OPPO Find X7
(@min_order_id + 4, 5, 1, 4999.00, 4999.00),   -- vivo X100 Pro
(@min_order_id + 5, 6, 1, 14999.00, 14999.00), -- MacBook Pro 14
(@min_order_id + 6, 7, 1, 9999.00, 9999.00),   -- ThinkPad X1 Carbon

-- 4月12日订单的商品项（8条）
(@min_order_id + 7, 8, 1, 12999.00, 12999.00), -- 戴尔XPS 15
(@min_order_id + 8, 9, 1, 8999.00, 8999.00),   -- 华为MateBook X
(@min_order_id + 9, 10, 1, 5999.00, 5999.00),  -- 小米笔记本Pro
(@min_order_id + 10, 2, 1, 4999.00, 4999.00),   -- 小米14 Pro
(@min_order_id + 11, 1, 1, 6999.00, 6999.00),   -- 华为Mate 60 Pro
(@min_order_id + 12, 4, 1, 4299.00, 4299.00),   -- OPPO Find X7
(@min_order_id + 13, 6, 1, 14999.00, 14999.00), -- MacBook Pro 14
(@min_order_id + 14, 7, 1, 9999.00, 9999.00),   -- ThinkPad X1 Carbon

-- 4月13日订单的商品项（8条）
(@min_order_id + 15, 3, 1, 8999.00, 8999.00),   -- iPhone 15 Pro
(@min_order_id + 16, 5, 1, 4999.00, 4999.00),   -- vivo X100 Pro
(@min_order_id + 17, 8, 1, 12999.00, 12999.00), -- 戴尔XPS 15
(@min_order_id + 18, 4, 1, 4299.00, 4299.00),   -- OPPO Find X7
(@min_order_id + 19, 1, 1, 6999.00, 6999.00),   -- 华为Mate 60 Pro
(@min_order_id + 20, 7, 1, 9999.00, 9999.00),   -- ThinkPad X1 Carbon
(@min_order_id + 21, 6, 1, 14999.00, 14999.00), -- MacBook Pro 14
(@min_order_id + 22, 10, 1, 5999.00, 5999.00),  -- 小米笔记本Pro

-- 4月14日订单的商品项（9条）
(@min_order_id + 23, 2, 1, 4999.00, 4999.00),   -- 小米14 Pro
(@min_order_id + 24, 3, 1, 8999.00, 8999.00),   -- iPhone 15 Pro
(@min_order_id + 25, 8, 1, 12999.00, 12999.00), -- 戴尔XPS 15
(@min_order_id + 26, 1, 1, 6999.00, 6999.00),   -- 华为Mate 60 Pro
(@min_order_id + 27, 4, 1, 4299.00, 4299.00),   -- OPPO Find X7
(@min_order_id + 28, 9, 1, 8999.00, 8999.00),   -- 华为MateBook X
(@min_order_id + 29, 6, 1, 14999.00, 14999.00), -- MacBook Pro 14
(@min_order_id + 30, 10, 1, 5999.00, 5999.00),  -- 小米笔记本Pro
(@min_order_id + 31, 5, 1, 4999.00, 4999.00),   -- vivo X100 Pro

-- 4月15日订单的商品项（7条）
(@min_order_id + 32, 3, 1, 8999.00, 8999.00),   -- iPhone 15 Pro
(@min_order_id + 33, 8, 1, 12999.00, 12999.00), -- 戴尔XPS 15
(@min_order_id + 34, 4, 1, 4299.00, 4299.00),   -- OPPO Find X7
(@min_order_id + 35, 1, 1, 6999.00, 6999.00),   -- 华为Mate 60 Pro
(@min_order_id + 36, 7, 1, 9999.00, 9999.00),   -- ThinkPad X1 Carbon
(@min_order_id + 37, 6, 1, 14999.00, 14999.00), -- MacBook Pro 14
(@min_order_id + 38, 10, 1, 5999.00, 5999.00);  -- 小米笔记本Pro

-- ========================================
-- 4. 验证数据
-- ========================================
SELECT DATE(created_at) as order_date, COUNT(*) as order_count, SUM(actual_amount) as total_amount
FROM orders 
WHERE created_at >= '2024-04-11' 
GROUP BY DATE(created_at)
ORDER BY order_date;

SELECT COUNT(*) as total_orders FROM orders WHERE created_at >= '2024-04-11';
SELECT COUNT(*) as total_items FROM order_items WHERE order_id >= @last_order_id;
