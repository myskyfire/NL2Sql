-- ========================================
-- 生成5月3日-5月8日的订单数据
-- 确保 orders 和 order_items 表的数据完整性
-- 基于实际数据库数据构造
-- ========================================

USE trade;

-- 现有数据统计：
-- users表: id 1-10 (zhangsan, lisi, wangwu, zhaoliu, sunqi, zhouba, wujiu, zhengshi, chenyi, liner)
-- products表: id 1-10 (华为Mate 60 Pro 6999, 小米14 Pro 4999, iPhone 15 Pro 8999, ...)
-- ========================================

-- ========================================
-- 1. 插入订单数据（5月3日-5月8日，每天5-10条）
-- ========================================

-- 5月3日订单（7条）
INSERT INTO orders (order_no, user_id, shipping_address, total_amount, actual_amount, status, payment_method, created_at) VALUES
('ORD20260503091523001', 1, '北京市朝阳区建国路88号', 6999.00, 6999.00, 'completed', 'alipay', '2026-05-03 09:15:23'),
('ORD20260503102211002', 2, '上海市浦东新区陆家嘴环路1000号', 4999.00, 4499.10, 'completed', 'wechat', '2026-05-03 10:22:11'),
('ORD20260503110547003', 3, '广州市天河区天河路208号', 8999.00, 8999.00, 'completed', 'credit_card', '2026-05-03 11:05:47'),
('ORD20260503143055004', 4, '深圳市南山区科技南路88号', 4299.00, 3869.10, 'completed', 'alipay', '2026-05-03 14:30:55'),
('ORD20260503154533005', 5, '杭州市西湖区文三路100号', 4999.00, 4999.00, 'completed', 'wechat', '2026-05-03 15:45:33'),
('ORD20260503162008006', 6, '成都市武侯区人民南路三段1号', 14999.00, 13499.10, 'completed', 'credit_card', '2026-05-03 16:20:08'),
('ORD20260503171042007', 7, '武汉市江汉区解放大道688号', 9999.00, 8999.10, 'completed', 'alipay', '2026-05-03 17:10:42');

-- 5月4日订单（8条）
INSERT INTO orders (order_no, user_id, shipping_address, total_amount, actual_amount, status, payment_method, created_at) VALUES
('ORD20260504083015008', 8, '南京市鼓楼区中山路200号', 12999.00, 11699.10, 'completed', 'alipay', '2026-05-04 08:30:15'),
('ORD20260504094522009', 9, '西安市雁塔区长安中路100号', 8999.00, 8999.00, 'completed', 'wechat', '2026-05-04 09:45:22'),
('ORD20260504101538010', 10, '重庆市渝中区解放碑步行街', 5999.00, 5399.10, 'completed', 'credit_card', '2026-05-04 10:15:38'),
('ORD20260504112045011', 1, '北京市朝阳区建国路88号', 4999.00, 4999.00, 'completed', 'alipay', '2026-05-04 11:20:45'),
('ORD20260504133512012', 3, '广州市天河区天河路208号', 6999.00, 6299.10, 'completed', 'wechat', '2026-05-04 13:35:12'),
('ORD20260504145028013', 5, '杭州市西湖区文三路100号', 4299.00, 4299.00, 'completed', 'credit_card', '2026-05-04 14:50:28'),
('ORD20260504160533014', 7, '武汉市江汉区解放大道688号', 14999.00, 13499.10, 'completed', 'alipay', '2026-05-04 16:05:33'),
('ORD20260504172547015', 2, '上海市浦东新区陆家嘴环路1000号', 9999.00, 8999.10, 'completed', 'wechat', '2026-05-04 17:25:47');

-- 5月5日订单（8条）
INSERT INTO orders (order_no, user_id, shipping_address, total_amount, actual_amount, status, payment_method, created_at) VALUES
('ORD20260505091025016', 4, '深圳市南山区科技南路88号', 8999.00, 8099.10, 'completed', 'alipay', '2026-05-05 09:10:25'),
('ORD20260505102538017', 6, '成都市武侯区人民南路三段1号', 4999.00, 4499.10, 'completed', 'wechat', '2026-05-05 10:25:38'),
('ORD20260505114012018', 8, '南京市鼓楼区中山路200号', 12999.00, 12999.00, 'completed', 'credit_card', '2026-05-05 11:40:12'),
('ORD20260505131545019', 10, '重庆市渝中区解放碑步行街', 4299.00, 4299.00, 'completed', 'alipay', '2026-05-05 13:15:45'),
('ORD20260505143022020', 1, '北京市朝阳区建国路88号', 6999.00, 6299.10, 'completed', 'wechat', '2026-05-05 14:30:22'),
('ORD20260505154538021', 3, '广州市天河区天河路208号', 9999.00, 9999.00, 'completed', 'credit_card', '2026-05-05 15:45:38'),
('ORD20260505165015022', 5, '杭州市西湖区文三路100号', 14999.00, 13499.10, 'completed', 'alipay', '2026-05-05 16:50:15'),
('ORD20260505173542023', 7, '武汉市江汉区解放大道688号', 5999.00, 5399.10, 'completed', 'wechat', '2026-05-05 17:35:42');

-- 5月6日订单（9条）
INSERT INTO orders (order_no, user_id, shipping_address, total_amount, actual_amount, status, payment_method, created_at) VALUES
('ORD20260506084518024', 2, '上海市浦东新区陆家嘴环路1000号', 4999.00, 4499.10, 'completed', 'alipay', '2026-05-06 08:45:18'),
('ORD20260506095032025', 9, '西安市雁塔区长安中路100号', 8999.00, 8999.00, 'completed', 'wechat', '2026-05-06 09:50:32'),
('ORD20260506103547026', 4, '深圳市南山区科技南路88号', 12999.00, 11699.10, 'completed', 'credit_card', '2026-05-06 10:35:47'),
('ORD20260506112015027', 6, '成都市武侯区人民南路三段1号', 6999.00, 6999.00, 'completed', 'alipay', '2026-05-06 11:20:15'),
('ORD20260506134528028', 8, '南京市鼓楼区中山路200号', 4299.00, 3869.10, 'completed', 'wechat', '2026-05-06 13:45:28'),
('ORD20260506143042029', 10, '重庆市渝中区解放碑步行街', 9999.00, 9999.00, 'completed', 'credit_card', '2026-05-06 14:30:42'),
('ORD20260506155518030', 1, '北京市朝阳区建国路88号', 14999.00, 13499.10, 'completed', 'alipay', '2026-05-06 15:55:18'),
('ORD20260506164035031', 3, '广州市天河区天河路208号', 5999.00, 5399.10, 'completed', 'wechat', '2026-05-06 16:40:35'),
('ORD20260506172548032', 5, '杭州市西湖区文三路100号', 4999.00, 4999.00, 'completed', 'credit_card', '2026-05-06 17:25:48');

-- 5月7日订单（7条）
INSERT INTO orders (order_no, user_id, shipping_address, total_amount, actual_amount, status, payment_method, created_at) VALUES
('ORD20260507090522033', 7, '武汉市江汉区解放大道688号', 8999.00, 8099.10, 'completed', 'alipay', '2026-05-07 09:05:22'),
('ORD20260507101538034', 9, '西安市雁塔区长安中路100号', 12999.00, 12999.00, 'completed', 'wechat', '2026-05-07 10:15:38'),
('ORD20260507113045035', 2, '上海市浦东新区陆家嘴环路1000号', 4299.00, 4299.00, 'completed', 'credit_card', '2026-05-07 11:30:45'),
('ORD20260507132012036', 4, '深圳市南山区科技南路88号', 6999.00, 6299.10, 'completed', 'alipay', '2026-05-07 13:20:12'),
('ORD20260507144528037', 6, '成都市武侯区人民南路三段1号', 9999.00, 9999.00, 'completed', 'wechat', '2026-05-07 14:45:28'),
('ORD20260507153035038', 8, '南京市鼓楼区中山路200号', 14999.00, 13499.10, 'completed', 'credit_card', '2026-05-07 15:30:35'),
('ORD20260507161542039', 10, '重庆市渝中区解放碑步行街', 5999.00, 5399.10, 'completed', 'alipay', '2026-05-07 16:15:42');

-- 5月8日订单（6条，今天）
INSERT INTO orders (order_no, user_id, shipping_address, total_amount, actual_amount, status, payment_method, created_at) VALUES
('ORD20260508085015040', 1, '北京市朝阳区建国路88号', 8999.00, 8099.10, 'completed', 'alipay', '2026-05-08 08:50:15'),
('ORD20260508094522041', 3, '广州市天河区天河路208号', 4999.00, 4499.10, 'completed', 'wechat', '2026-05-08 09:45:22'),
('ORD20260508103038042', 5, '杭州市西湖区文三路100号', 12999.00, 11699.10, 'completed', 'credit_card', '2026-05-08 10:30:38'),
('ORD20260508111545043', 7, '武汉市江汉区解放大道688号', 6999.00, 6999.00, 'completed', 'alipay', '2026-05-08 11:15:45'),
('ORD20260508133012044', 9, '西安市雁塔区长安中路100号', 4299.00, 3869.10, 'completed', 'wechat', '2026-05-08 13:30:12'),
('ORD20260508144528045', 2, '上海市浦东新区陆家嘴环路1000号', 14999.00, 13499.10, 'completed', 'credit_card', '2026-05-08 14:45:28');

-- ========================================
-- 2. 插入订单项数据（order_items）
-- 每个订单对应1个商品项，product_id使用真实存在的商品
-- ========================================

-- 获取新插入的订单ID范围
SET @min_order_id = (SELECT MIN(id) FROM orders WHERE created_at >= '2026-05-03');

-- 为每个订单添加商品项（45条订单项）
INSERT INTO order_items (order_id, product_id, quantity, unit_price, subtotal) VALUES
-- 5月3日订单的商品项（7条）
(@min_order_id + 0, 1, 1, 6999.00, 6999.00),   -- 华为Mate 60 Pro
(@min_order_id + 1, 2, 1, 4999.00, 4999.00),   -- 小米14 Pro
(@min_order_id + 2, 3, 1, 8999.00, 8999.00),   -- iPhone 15 Pro
(@min_order_id + 3, 4, 1, 4299.00, 4299.00),   -- OPPO Find X7
(@min_order_id + 4, 5, 1, 4999.00, 4999.00),   -- vivo X100 Pro
(@min_order_id + 5, 6, 1, 14999.00, 14999.00), -- MacBook Pro 14
(@min_order_id + 6, 7, 1, 9999.00, 9999.00),   -- ThinkPad X1 Carbon

-- 5月4日订单的商品项（8条）
(@min_order_id + 7, 8, 1, 12999.00, 12999.00), -- 戴尔XPS 15
(@min_order_id + 8, 9, 1, 8999.00, 8999.00),   -- 华为MateBook X
(@min_order_id + 9, 10, 1, 5999.00, 5999.00),  -- 小米笔记本Pro
(@min_order_id + 10, 2, 1, 4999.00, 4999.00),   -- 小米14 Pro
(@min_order_id + 11, 1, 1, 6999.00, 6999.00),   -- 华为Mate 60 Pro
(@min_order_id + 12, 4, 1, 4299.00, 4299.00),   -- OPPO Find X7
(@min_order_id + 13, 6, 1, 14999.00, 14999.00), -- MacBook Pro 14
(@min_order_id + 14, 7, 1, 9999.00, 9999.00),   -- ThinkPad X1 Carbon

-- 5月5日订单的商品项（8条）
(@min_order_id + 15, 3, 1, 8999.00, 8999.00),   -- iPhone 15 Pro
(@min_order_id + 16, 5, 1, 4999.00, 4999.00),   -- vivo X100 Pro
(@min_order_id + 17, 8, 1, 12999.00, 12999.00), -- 戴尔XPS 15
(@min_order_id + 18, 4, 1, 4299.00, 4299.00),   -- OPPO Find X7
(@min_order_id + 19, 1, 1, 6999.00, 6999.00),   -- 华为Mate 60 Pro
(@min_order_id + 20, 7, 1, 9999.00, 9999.00),   -- ThinkPad X1 Carbon
(@min_order_id + 21, 6, 1, 14999.00, 14999.00), -- MacBook Pro 14
(@min_order_id + 22, 10, 1, 5999.00, 5999.00),  -- 小米笔记本Pro

-- 5月6日订单的商品项（9条）
(@min_order_id + 23, 2, 1, 4999.00, 4999.00),   -- 小米14 Pro
(@min_order_id + 24, 3, 1, 8999.00, 8999.00),   -- iPhone 15 Pro
(@min_order_id + 25, 8, 1, 12999.00, 12999.00), -- 戴尔XPS 15
(@min_order_id + 26, 1, 1, 6999.00, 6999.00),   -- 华为Mate 60 Pro
(@min_order_id + 27, 4, 1, 4299.00, 4299.00),   -- OPPO Find X7
(@min_order_id + 28, 9, 1, 8999.00, 8999.00),   -- 华为MateBook X
(@min_order_id + 29, 6, 1, 14999.00, 14999.00), -- MacBook Pro 14
(@min_order_id + 30, 10, 1, 5999.00, 5999.00),  -- 小米笔记本Pro
(@min_order_id + 31, 5, 1, 4999.00, 4999.00),   -- vivo X100 Pro

-- 5月7日订单的商品项（7条）
(@min_order_id + 32, 3, 1, 8999.00, 8999.00),   -- iPhone 15 Pro
(@min_order_id + 33, 8, 1, 12999.00, 12999.00), -- 戴尔XPS 15
(@min_order_id + 34, 4, 1, 4299.00, 4299.00),   -- OPPO Find X7
(@min_order_id + 35, 1, 1, 6999.00, 6999.00),   -- 华为Mate 60 Pro
(@min_order_id + 36, 7, 1, 9999.00, 9999.00),   -- ThinkPad X1 Carbon
(@min_order_id + 37, 6, 1, 14999.00, 14999.00), -- MacBook Pro 14
(@min_order_id + 38, 10, 1, 5999.00, 5999.00),  -- 小米笔记本Pro

-- 5月8日订单的商品项（6条）
(@min_order_id + 39, 1, 1, 8999.00, 8999.00),   -- iPhone 15 Pro
(@min_order_id + 40, 2, 1, 4999.00, 4999.00),   -- 小米14 Pro
(@min_order_id + 41, 8, 1, 12999.00, 12999.00), -- 戴尔XPS 15
(@min_order_id + 42, 7, 1, 6999.00, 6999.00),   -- ThinkPad X1 Carbon
(@min_order_id + 43, 9, 1, 4299.00, 4299.00),   -- 华为MateBook X
(@min_order_id + 44, 6, 1, 14999.00, 14999.00); -- MacBook Pro 14

-- ========================================
-- 3. 验证数据
-- ========================================
SELECT DATE(created_at) as order_date, COUNT(*) as order_count, SUM(actual_amount) as total_amount
FROM orders 
WHERE created_at >= '2026-05-03' 
GROUP BY DATE(created_at)
ORDER BY order_date;

SELECT COUNT(*) as total_orders FROM orders WHERE created_at >= '2026-05-03';
SELECT COUNT(*) as total_items FROM order_items WHERE order_id >= @min_order_id;
