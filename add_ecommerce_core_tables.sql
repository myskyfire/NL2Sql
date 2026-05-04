-- ============================================
-- 微小型电商核心补充表 - trade 数据库
-- 字符集：utf8mb4（完整支持中文和emoji）
-- ============================================

USE trade;

-- 1. 商品评价表
CREATE TABLE IF NOT EXISTS product_reviews (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '评价ID',
    order_id BIGINT NOT NULL COMMENT '订单ID',
    product_id BIGINT NOT NULL COMMENT '商品ID',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    rating TINYINT NOT NULL COMMENT '评分(1-5星)',
    content TEXT COMMENT '评价内容',
    images JSON COMMENT '评价图片URL数组',
    is_anonymous TINYINT DEFAULT 0 COMMENT '是否匿名评价',
    status VARCHAR(20) DEFAULT 'published' COMMENT '状态: published/archived',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '评价时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    FOREIGN KEY (order_id) REFERENCES orders(id),
    FOREIGN KEY (product_id) REFERENCES products(id),
    FOREIGN KEY (user_id) REFERENCES users(id),
    INDEX idx_product (product_id),
    INDEX idx_user (user_id),
    INDEX idx_rating (rating),
    INDEX idx_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Product review table';

-- 2. 优惠券主表
CREATE TABLE IF NOT EXISTS coupons (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '优惠券ID',
    coupon_code VARCHAR(50) NOT NULL UNIQUE COMMENT '优惠码',
    coupon_name VARCHAR(100) NOT NULL COMMENT '优惠券名称',
    coupon_type VARCHAR(20) NOT NULL COMMENT '类型: fixed/discount/shipping',
    discount_value DECIMAL(10,2) COMMENT '优惠金额或折扣率',
    min_amount DECIMAL(10,2) DEFAULT 0 COMMENT '最低消费金额',
    max_discount DECIMAL(10,2) COMMENT '最大优惠金额(折扣券用)',
    total_quantity INT NOT NULL COMMENT '发放总量',
    used_quantity INT DEFAULT 0 COMMENT '已使用数量',
    valid_from DATETIME NOT NULL COMMENT '有效期开始',
    valid_to DATETIME NOT NULL COMMENT '有效期结束',
    status VARCHAR(20) DEFAULT 'active' COMMENT '状态: active/inactive/expired',
    description VARCHAR(500) COMMENT '使用说明',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_code (coupon_code),
    INDEX idx_status (status),
    INDEX idx_valid (valid_from, valid_to)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Coupon table';

-- 3. 用户优惠券关联表
CREATE TABLE IF NOT EXISTS user_coupons (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '关联ID',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    coupon_id BIGINT NOT NULL COMMENT '优惠券ID',
    status VARCHAR(20) DEFAULT 'unused' COMMENT '状态: unused/used/expired',
    used_order_id BIGINT COMMENT '使用的订单ID',
    used_at DATETIME COMMENT '使用时间',
    received_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '领取时间',
    expired_at DATETIME COMMENT '过期时间',
    FOREIGN KEY (user_id) REFERENCES users(id),
    FOREIGN KEY (coupon_id) REFERENCES coupons(id),
    FOREIGN KEY (used_order_id) REFERENCES orders(id),
    INDEX idx_user (user_id),
    INDEX idx_coupon (coupon_id),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='User coupon association table';

-- 4. 订单优惠明细表
CREATE TABLE IF NOT EXISTS order_discounts (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '明细ID',
    order_id BIGINT NOT NULL COMMENT '订单ID',
    discount_type VARCHAR(20) NOT NULL COMMENT '优惠类型: coupon/promotion/points',
    discount_id BIGINT COMMENT '优惠ID(如优惠券ID)',
    discount_name VARCHAR(100) COMMENT '优惠名称',
    discount_amount DECIMAL(10,2) NOT NULL COMMENT '优惠金额',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    FOREIGN KEY (order_id) REFERENCES orders(id),
    INDEX idx_order (order_id),
    INDEX idx_type (discount_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Order discount details table';

-- 5. 物流公司表
CREATE TABLE IF NOT EXISTS shipping_companies (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '物流公司ID',
    company_name VARCHAR(100) NOT NULL COMMENT '公司名称',
    company_code VARCHAR(20) NOT NULL UNIQUE COMMENT '公司编码',
    contact_phone VARCHAR(20) COMMENT '联系电话',
    website VARCHAR(200) COMMENT '官网地址',
    status VARCHAR(20) DEFAULT 'active' COMMENT '状态: active/inactive',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_code (company_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Shipping company table';

-- 6. 物流跟踪表
CREATE TABLE IF NOT EXISTS shipments (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '物流ID',
    order_id BIGINT NOT NULL COMMENT '订单ID',
    tracking_no VARCHAR(100) NOT NULL UNIQUE COMMENT '物流单号',
    company_id BIGINT COMMENT '物流公司ID',
    status VARCHAR(20) DEFAULT 'pending' COMMENT '物流状态: pending/shipped/in_transit/delivered',
    shipped_at DATETIME COMMENT '发货时间',
    delivered_at DATETIME COMMENT '签收时间',
    current_location VARCHAR(200) COMMENT '当前位置',
    estimated_delivery DATETIME COMMENT '预计送达时间',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    FOREIGN KEY (order_id) REFERENCES orders(id),
    FOREIGN KEY (company_id) REFERENCES shipping_companies(id),
    INDEX idx_order (order_id),
    INDEX idx_tracking (tracking_no),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Shipment tracking table';

-- 7. 商品收藏表
CREATE TABLE IF NOT EXISTS product_favorites (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '收藏ID',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    product_id BIGINT NOT NULL COMMENT '商品ID',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '收藏时间',
    FOREIGN KEY (user_id) REFERENCES users(id),
    FOREIGN KEY (product_id) REFERENCES products(id),
    UNIQUE KEY uk_user_product (user_id, product_id),
    INDEX idx_user (user_id),
    INDEX idx_product (product_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Product favorites table';

-- 8. 用户积分表
CREATE TABLE IF NOT EXISTS user_points (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '积分记录ID',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    points INT NOT NULL COMMENT '积分变化(正数增加，负数减少)',
    type VARCHAR(20) NOT NULL COMMENT '类型: purchase/refund/bonus/redemption',
    related_order_id BIGINT COMMENT '关联订单ID',
    balance_after INT NOT NULL COMMENT '变化后余额',
    description VARCHAR(200) COMMENT '描述',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    FOREIGN KEY (user_id) REFERENCES users(id),
    FOREIGN KEY (related_order_id) REFERENCES orders(id),
    INDEX idx_user (user_id),
    INDEX idx_type (type),
    INDEX idx_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='User points table';

-- ============================================
-- 插入基础数据
-- ============================================

-- 插入物流公司数据
INSERT INTO shipping_companies (company_name, company_code, contact_phone, website) VALUES
('顺丰速运', 'SF', '95338', 'https://www.sf-express.com'),
('圆通速递', 'YTO', '95554', 'https://www.yto.net.cn'),
('中通快递', 'ZTO', '95311', 'https://www.zto.com'),
('韵达快递', 'YD', '95546', 'https://www.yundaex.com'),
('申通快递', 'STO', '95543', 'https://www.sto.cn'),
('京东物流', 'JD', '950616', 'https://www.jdl.cn'),
('邮政EMS', 'EMS', '11183', 'https://www.ems.com.cn');

-- 插入测试优惠券数据
INSERT INTO coupons (coupon_code, coupon_name, coupon_type, discount_value, min_amount, max_discount, total_quantity, used_quantity, valid_from, valid_to, status, description) VALUES
('WELCOME10', '新用户专享券', 'fixed', 10.00, 50.00, NULL, 1000, 0, NOW(), DATE_ADD(NOW(), INTERVAL 30 DAY), 'active', '新用户注册即送，满50减10'),
('SUMMER20', '夏季促销券', 'discount', 0.20, 100.00, 50.00, 500, 0, NOW(), DATE_ADD(NOW(), INTERVAL 60 DAY), 'active', '全场8折，最高优惠50元'),
('FREESHIP', '包邮券', 'shipping', 0, 0, NULL, 2000, 0, NOW(), DATE_ADD(NOW(), INTERVAL 90 DAY), 'active', '全场包邮，无门槛');

-- ============================================
-- 验证表创建成功
-- ============================================

SELECT '=== Table Creation Verification ===' AS info;
SELECT TABLE_NAME, TABLE_COMMENT 
FROM INFORMATION_SCHEMA.TABLES 
WHERE TABLE_SCHEMA = 'trade' 
AND TABLE_NAME IN (
    'product_reviews', 'coupons', 'user_coupons', 'order_discounts',
    'shipping_companies', 'shipments', 'product_favorites', 'user_points'
)
ORDER BY TABLE_NAME;

SELECT '=== Shipping Companies Data ===' AS info;
SELECT * FROM shipping_companies;

SELECT '=== Coupons Data ===' AS info;
SELECT coupon_code, coupon_name, coupon_type, discount_value, status FROM coupons;

SELECT '=== Summary ===' AS info;
SELECT COUNT(*) AS new_tables_count 
FROM INFORMATION_SCHEMA.TABLES 
WHERE TABLE_SCHEMA = 'trade' 
AND TABLE_NAME IN (
    'product_reviews', 'coupons', 'user_coupons', 'order_discounts',
    'shipping_companies', 'shipments', 'product_favorites', 'user_points'
);
