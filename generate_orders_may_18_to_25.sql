-- ========================================
-- 生成5月18日-5月25日的订单数据
-- 确保 orders 和 order_items 表的数据完整性
-- 基于实际数据库数据构造
-- ========================================

USE trade;

-- 现有数据统计：
-- users表: id 1-10 (zhangsan, lisi, wangwu, zhaoliu, sunqi, zhouba, wujiu, zhengshi, chenyi, liner)
-- products表: id 1-10 (华为Mate 60 Pro 6999, 小米14 Pro 4999, iPhone 15 Pro 8999, ...)
-- orders表: 当前最大ID=347，最后日期=2026-05-17
-- ========================================

-- ========================================
-- 插入订单数据（5月18日-5月25日，每天6-8条）
-- ========================================

-- 5月18日订单（7条）
INSERT INTO orders (order_no, user_id, shipping_address, total_amount, actual_amount, status, payment_method, created_at) VALUES
('ORD20260518091523348', 1, '北京市朝阳区建国路88号', 6999.00, 6999.00, 'completed', 'alipay', '2026-05-18 09:15:23'),
('ORD20260518102211349', 2, '上海市浦东新区陆家嘴环路1000号', 4999.00, 4499.10, 'completed', 'wechat', '2026-05-18 10:22:11'),
('ORD20260518110547350', 3, '广州市天河区天河路208号', 8999.00, 8999.00, 'completed', 'credit_card', '2026-05-18 11:05:47'),
('ORD20260518143055351', 4, '深圳市南山区科技南路88号', 4299.00, 3869.10, 'completed', 'alipay', '2026-05-18 14:30:55'),
('ORD20260518154533352', 5, '杭州市西湖区文三路100号', 4999.00, 4999.00, 'completed', 'wechat', '2026-05-18 15:45:33'),
('ORD20260518162008353', 6, '成都市武侯区人民南路三段1号', 14999.00, 13499.10, 'completed', 'credit_card', '2026-05-18 16:20:08'),
('ORD20260518171042354', 7, '武汉市江汉区解放大道688号', 9999.00, 8999.10, 'completed', 'alipay', '2026-05-18 17:10:42');

-- 5月19日订单（8条）
INSERT INTO orders (order_no, user_id, shipping_address, total_amount, actual_amount, status, payment_method, created_at) VALUES
('ORD20260519083015355', 8, '南京市鼓楼区中山路200号', 12999.00, 11699.10, 'completed', 'alipay', '2026-05-19 08:30:15'),
('ORD20260519094522356', 9, '西安市雁塔区长安中路100号', 8999.00, 8999.00, 'completed', 'wechat', '2026-05-19 09:45:22'),
('ORD20260519101538357', 10, '重庆市渝中区解放碑步行街', 5999.00, 5399.10, 'completed', 'credit_card', '2026-05-19 10:15:38'),
('ORD20260519112045358', 1, '北京市朝阳区建国路88号', 4999.00, 4999.00, 'completed', 'alipay', '2026-05-19 11:20:45'),
('ORD20260519133512359', 3, '广州市天河区天河路208号', 6999.00, 6299.10, 'completed', 'wechat', '2026-05-19 13:35:12'),
('ORD20260519145028360', 5, '杭州市西湖区文三路100号', 4299.00, 4299.00, 'completed', 'credit_card', '2026-05-19 14:50:28'),
('ORD20260519160533361', 7, '武汉市江汉区解放大道688号', 14999.00, 13499.10, 'completed', 'alipay', '2026-05-19 16:05:33'),
('ORD20260519172547362', 2, '上海市浦东新区陆家嘴环路1000号', 9999.00, 8999.10, 'completed', 'wechat', '2026-05-19 17:25:47');

-- 5月20日订单（8条）
INSERT INTO orders (order_no, user_id, shipping_address, total_amount, actual_amount, status, payment_method, created_at) VALUES
('ORD20260520091025363', 4, '深圳市南山区科技南路88号', 8999.00, 8099.10, 'completed', 'alipay', '2026-05-20 09:10:25'),
('ORD20260520102538364', 6, '成都市武侯区人民南路三段1号', 4999.00, 4499.10, 'completed', 'wechat', '2026-05-20 10:25:38'),
('ORD20260520114012365', 8, '南京市鼓楼区中山路200号', 12999.00, 12999.00, 'completed', 'credit_card', '2026-05-20 11:40:12'),
('ORD20260520131545366', 10, '重庆市渝中区解放碑步行街', 4299.00, 4299.00, 'completed', 'alipay', '2026-05-20 13:15:45'),
('ORD20260520143022367', 1, '北京市朝阳区建国路88号', 6999.00, 6299.10, 'completed', 'wechat', '2026-05-20 14:30:22'),
('ORD20260520154538368', 3, '广州市天河区天河路208号', 9999.00, 9999.00, 'completed', 'credit_card', '2026-05-20 15:45:38'),
('ORD20260520165015369', 5, '杭州市西湖区文三路100号', 14999.00, 13499.10, 'completed', 'alipay', '2026-05-20 16:50:15'),
('ORD20260520173542370', 7, '武汉市江汉区解放大道688号', 5999.00, 5399.10, 'completed', 'wechat', '2026-05-20 17:35:42');

-- 5月21日订单（7条）
INSERT INTO orders (order_no, user_id, shipping_address, total_amount, actual_amount, status, payment_method, created_at) VALUES
('ORD20260521084518371', 2, '上海市浦东新区陆家嘴环路1000号', 4999.00, 4499.10, 'completed', 'alipay', '2026-05-21 08:45:18'),
('ORD20260521095032372', 9, '西安市雁塔区长安中路100号', 8999.00, 8999.00, 'completed', 'wechat', '2026-05-21 09:50:32'),
('ORD20260521103547373', 4, '深圳市南山区科技南路88号', 12999.00, 11699.10, 'completed', 'credit_card', '2026-05-21 10:35:47'),
('ORD20260521112015374', 6, '成都市武侯区人民南路三段1号', 6999.00, 6999.00, 'completed', 'alipay', '2026-05-21 11:20:15'),
('ORD20260521134528375', 8, '南京市鼓楼区中山路200号', 4299.00, 3869.10, 'completed', 'wechat', '2026-05-21 13:45:28'),
('ORD20260521143042376', 10, '重庆市渝中区解放碑步行街', 9999.00, 9999.00, 'completed', 'credit_card', '2026-05-21 14:30:42'),
('ORD20260521155518377', 1, '北京市朝阳区建国路88号', 14999.00, 13499.10, 'completed', 'alipay', '2026-05-21 15:55:18');

-- 5月22日订单（8条）
INSERT INTO orders (order_no, user_id, shipping_address, total_amount, actual_amount, status, payment_method, created_at) VALUES
('ORD20260522164035378', 3, '广州市天河区天河路208号', 5999.00, 5399.10, 'completed', 'wechat', '2026-05-22 16:40:35'),
('ORD20260522172548379', 5, '杭州市西湖区文三路100号', 4999.00, 4999.00, 'completed', 'credit_card', '2026-05-22 17:25:48'),
('ORD20260522090522380', 7, '武汉市江汉区解放大道688号', 8999.00, 8099.10, 'completed', 'alipay', '2026-05-22 09:05:22'),
('ORD20260522101538381', 9, '西安市雁塔区长安中路100号', 12999.00, 12999.00, 'completed', 'wechat', '2026-05-22 10:15:38'),
('ORD20260522113045382', 2, '上海市浦东新区陆家嘴环路1000号', 4299.00, 4299.00, 'completed', 'credit_card', '2026-05-22 11:30:45'),
('ORD20260522132012383', 4, '深圳市南山区科技南路88号', 6999.00, 6299.10, 'completed', 'alipay', '2026-05-22 13:20:12'),
('ORD20260522144528384', 6, '成都市武侯区人民南路三段1号', 9999.00, 9999.00, 'completed', 'wechat', '2026-05-22 14:45:28'),
('ORD20260522153035385', 8, '南京市鼓楼区中山路200号', 14999.00, 13499.10, 'completed', 'credit_card', '2026-05-22 15:30:35');

-- 5月23日订单（7条）
INSERT INTO orders (order_no, user_id, shipping_address, total_amount, actual_amount, status, payment_method, created_at) VALUES
('ORD20260523161542386', 10, '重庆市渝中区解放碑步行街', 5999.00, 5399.10, 'completed', 'alipay', '2026-05-23 16:15:42'),
('ORD20260523091025387', 1, '北京市朝阳区建国路88号', 8999.00, 8099.10, 'completed', 'wechat', '2026-05-23 09:10:25'),
('ORD20260523102538388', 3, '广州市天河区天河路208号', 4999.00, 4499.10, 'completed', 'credit_card', '2026-05-23 10:25:38'),
('ORD20260523114012389', 5, '杭州市西湖区文三路100号', 12999.00, 12999.00, 'completed', 'alipay', '2026-05-23 11:40:12'),
('ORD20260523131545390', 7, '武汉市江汉区解放大道688号', 4299.00, 4299.00, 'completed', 'wechat', '2026-05-23 13:15:45'),
('ORD20260523143022391', 9, '西安市雁塔区长安中路100号', 6999.00, 6299.10, 'completed', 'credit_card', '2026-05-23 14:30:22'),
('ORD20260523154538392', 2, '上海市浦东新区陆家嘴环路1000号', 9999.00, 9999.00, 'completed', 'alipay', '2026-05-23 15:45:38');

-- 5月24日订单（8条）
INSERT INTO orders (order_no, user_id, shipping_address, total_amount, actual_amount, status, payment_method, created_at) VALUES
('ORD20260524165015393', 4, '深圳市南山区科技南路88号', 14999.00, 13499.10, 'completed', 'wechat', '2026-05-24 16:50:15'),
('ORD20260524173542394', 6, '成都市武侯区人民南路三段1号', 5999.00, 5399.10, 'completed', 'credit_card', '2026-05-24 17:35:42'),
('ORD20260524084518395', 8, '南京市鼓楼区中山路200号', 4999.00, 4499.10, 'completed', 'alipay', '2026-05-24 08:45:18'),
('ORD20260524095032396', 10, '重庆市渝中区解放碑步行街', 8999.00, 8999.00, 'completed', 'wechat', '2026-05-24 09:50:32'),
('ORD20260524103547397', 1, '北京市朝阳区建国路88号', 12999.00, 11699.10, 'completed', 'credit_card', '2026-05-24 10:35:47'),
('ORD20260524112015398', 3, '广州市天河区天河路208号', 6999.00, 6999.00, 'completed', 'alipay', '2026-05-24 11:20:15'),
('ORD20260524134528399', 5, '杭州市西湖区文三路100号', 4299.00, 3869.10, 'completed', 'wechat', '2026-05-24 13:45:28'),
('ORD20260524143042400', 7, '武汉市江汉区解放大道688号', 9999.00, 9999.00, 'completed', 'credit_card', '2026-05-24 14:30:42');

-- 5月25日订单（7条，今天）
INSERT INTO orders (order_no, user_id, shipping_address, total_amount, actual_amount, status, payment_method, created_at) VALUES
('ORD20260525155518401', 9, '西安市雁塔区长安中路100号', 14999.00, 13499.10, 'completed', 'alipay', '2026-05-25 15:55:18'),
('ORD20260525164035402', 2, '上海市浦东新区陆家嘴环路1000号', 5999.00, 5399.10, 'completed', 'wechat', '2026-05-25 16:40:35'),
('ORD20260525172548403', 4, '深圳市南山区科技南路88号', 4999.00, 4999.00, 'completed', 'credit_card', '2026-05-25 17:25:48'),
('ORD20260525090522404', 6, '成都市武侯区人民南路三段1号', 8999.00, 8099.10, 'completed', 'alipay', '2026-05-25 09:05:22'),
('ORD20260525101538405', 8, '南京市鼓楼区中山路200号', 12999.00, 12999.00, 'completed', 'wechat', '2026-05-25 10:15:38'),
('ORD20260525113045406', 10, '重庆市渝中区解放碑步行街', 4299.00, 4299.00, 'completed', 'credit_card', '2026-05-25 11:30:45'),
('ORD20260525132012407', 1, '北京市朝阳区建国路88号', 6999.00, 6299.10, 'completed', 'alipay', '2026-05-25 13:20:12');

-- ========================================
-- 插入订单项数据（order_items）
-- 每个订单对应1个商品项，product_id使用真实存在的商品
-- ========================================

-- 获取新插入的订单ID范围
SET @min_order_id = (SELECT MIN(id) FROM orders WHERE created_at >= '2026-05-18');

-- 为每个订单添加商品项（60条订单项）
INSERT INTO order_items (order_id, product_id, quantity, unit_price, subtotal) VALUES
-- 5月18日订单的商品项（7条）
(@min_order_id + 0, 1, 1, 6999.00, 6999.00),   -- 华为Mate 60 Pro
(@min_order_id + 1, 2, 1, 4999.00, 4999.00),   -- 小米14 Pro
(@min_order_id + 2, 3, 1, 8999.00, 8999.00),   -- iPhone 15 Pro
(@min_order_id + 3, 4, 1, 4299.00, 4299.00),   -- OPPO Find X7
(@min_order_id + 4, 5, 1, 4999.00, 4999.00),   -- vivo X100 Pro
(@min_order_id + 5, 6, 1, 14999.00, 14999.00), -- MacBook Pro 14
(@min_order_id + 6, 7, 1, 9999.00, 9999.00),   -- ThinkPad X1 Carbon

-- 5月19日订单的商品项（8条）
(@min_order_id + 7, 8, 1, 12999.00, 12999.00), -- 戴尔XPS 15
(@min_order_id + 8, 9, 1, 8999.00, 8999.00),   -- 华为MateBook X
(@min_order_id + 9, 10, 1, 5999.00, 5999.00),  -- 小米笔记本Pro
(@min_order_id + 10, 2, 1, 4999.00, 4999.00),   -- 小米14 Pro
(@min_order_id + 11, 1, 1, 6999.00, 6999.00),   -- 华为Mate 60 Pro
(@min_order_id + 12, 4, 1, 4299.00, 4299.00),   -- OPPO Find X7
(@min_order_id + 13, 6, 1, 14999.00, 14999.00), -- MacBook Pro 14
(@min_order_id + 14, 7, 1, 9999.00, 9999.00),   -- ThinkPad X1 Carbon

-- 5月20日订单的商品项（8条）
(@min_order_id + 15, 3, 1, 8999.00, 8999.00),   -- iPhone 15 Pro
(@min_order_id + 16, 5, 1, 4999.00, 4999.00),   -- vivo X100 Pro
(@min_order_id + 17, 8, 1, 12999.00, 12999.00), -- 戴尔XPS 15
(@min_order_id + 18, 4, 1, 4299.00, 4299.00),   -- OPPO Find X7
(@min_order_id + 19, 1, 1, 6999.00, 6999.00),   -- 华为Mate 60 Pro
(@min_order_id + 20, 7, 1, 9999.00, 9999.00),   -- ThinkPad X1 Carbon
(@min_order_id + 21, 6, 1, 14999.00, 14999.00), -- MacBook Pro 14
(@min_order_id + 22, 10, 1, 5999.00, 5999.00),  -- 小米笔记本Pro

-- 5月21日订单的商品项（7条）
(@min_order_id + 23, 2, 1, 4999.00, 4999.00),   -- 小米14 Pro
(@min_order_id + 24, 3, 1, 8999.00, 8999.00),   -- iPhone 15 Pro
(@min_order_id + 25, 8, 1, 12999.00, 12999.00), -- 戴尔XPS 15
(@min_order_id + 26, 1, 1, 6999.00, 6999.00),   -- 华为Mate 60 Pro
(@min_order_id + 27, 4, 1, 4299.00, 4299.00),   -- OPPO Find X7
(@min_order_id + 28, 9, 1, 8999.00, 8999.00),   -- 华为MateBook X
(@min_order_id + 29, 6, 1, 14999.00, 14999.00), -- MacBook Pro 14

-- 5月22日订单的商品项（8条）
(@min_order_id + 30, 10, 1, 5999.00, 5999.00),  -- 小米笔记本Pro
(@min_order_id + 31, 5, 1, 4999.00, 4999.00),   -- vivo X100 Pro
(@min_order_id + 32, 3, 1, 8999.00, 8999.00),   -- iPhone 15 Pro
(@min_order_id + 33, 8, 1, 12999.00, 12999.00), -- 戴尔XPS 15
(@min_order_id + 34, 4, 1, 4299.00, 4299.00),   -- OPPO Find X7
(@min_order_id + 35, 1, 1, 6999.00, 6999.00),   -- 华为Mate 60 Pro
(@min_order_id + 36, 7, 1, 9999.00, 9999.00),   -- ThinkPad X1 Carbon
(@min_order_id + 37, 6, 1, 14999.00, 14999.00), -- MacBook Pro 14

-- 5月23日订单的商品项（7条）
(@min_order_id + 38, 10, 1, 5999.00, 5999.00),  -- 小米笔记本Pro
(@min_order_id + 39, 3, 1, 8999.00, 8999.00),   -- iPhone 15 Pro
(@min_order_id + 40, 5, 1, 4999.00, 4999.00),   -- vivo X100 Pro
(@min_order_id + 41, 8, 1, 12999.00, 12999.00), -- 戴尔XPS 15
(@min_order_id + 42, 4, 1, 4299.00, 4299.00),   -- OPPO Find X7
(@min_order_id + 43, 1, 1, 6999.00, 6999.00),   -- 华为Mate 60 Pro
(@min_order_id + 44, 7, 1, 9999.00, 9999.00),   -- ThinkPad X1 Carbon

-- 5月24日订单的商品项（8条）
(@min_order_id + 45, 6, 1, 14999.00, 14999.00), -- MacBook Pro 14
(@min_order_id + 46, 10, 1, 5999.00, 5999.00),  -- 小米笔记本Pro
(@min_order_id + 47, 2, 1, 4999.00, 4999.00),   -- 小米14 Pro
(@min_order_id + 48, 9, 1, 8999.00, 8999.00),   -- 华为MateBook X
(@min_order_id + 49, 8, 1, 12999.00, 12999.00), -- 戴尔XPS 15
(@min_order_id + 50, 1, 1, 6999.00, 6999.00),   -- 华为Mate 60 Pro
(@min_order_id + 51, 4, 1, 4299.00, 4299.00),   -- OPPO Find X7
(@min_order_id + 52, 7, 1, 9999.00, 9999.00),   -- ThinkPad X1 Carbon

-- 5月25日订单的商品项（7条）
(@min_order_id + 53, 6, 1, 14999.00, 14999.00), -- MacBook Pro 14
(@min_order_id + 54, 10, 1, 5999.00, 5999.00),  -- 小米笔记本Pro
(@min_order_id + 55, 5, 1, 4999.00, 4999.00),   -- vivo X100 Pro
(@min_order_id + 56, 3, 1, 8999.00, 8999.00),   -- iPhone 15 Pro
(@min_order_id + 57, 8, 1, 12999.00, 12999.00), -- 戴尔XPS 15
(@min_order_id + 58, 4, 1, 4299.00, 4299.00),   -- OPPO Find X7
(@min_order_id + 59, 1, 1, 6999.00, 6999.00);   -- 华为Mate 60 Pro

-- ========================================
-- 验证数据
-- ========================================
SELECT DATE(created_at) as order_date, COUNT(*) as order_count, SUM(actual_amount) as total_amount
FROM orders 
WHERE created_at >= '2026-05-18' 
GROUP BY DATE(created_at)
ORDER BY order_date;

SELECT COUNT(*) as total_orders FROM orders WHERE created_at >= '2026-05-18';
SELECT COUNT(*) as total_items FROM order_items WHERE order_id >= @min_order_id;
