-- ============================================
-- 补全trade库缺失的表注释和列注释
-- ============================================

USE trade;

-- 1. coupons (优惠券表)
ALTER TABLE coupons COMMENT '优惠券表';
ALTER TABLE coupons 
    MODIFY COLUMN id BIGINT NOT NULL AUTO_INCREMENT COMMENT '优惠券ID',
    MODIFY COLUMN coupon_code VARCHAR(50) NOT NULL COMMENT '优惠券编码',
    MODIFY COLUMN coupon_name VARCHAR(100) NOT NULL COMMENT '优惠券名称',
    MODIFY COLUMN coupon_type VARCHAR(20) NOT NULL COMMENT '优惠券类型: discount-折扣, cashback-返现, free_shipping-包邮',
    MODIFY COLUMN discount_value DECIMAL(10,2) COMMENT '优惠金额或折扣值',
    MODIFY COLUMN min_amount DECIMAL(10,2) DEFAULT 0.00 COMMENT '最低消费金额',
    MODIFY COLUMN max_discount DECIMAL(10,2) COMMENT '最大优惠金额',
    MODIFY COLUMN total_quantity INT NOT NULL COMMENT '发放总量',
    MODIFY COLUMN used_quantity INT DEFAULT 0 COMMENT '已使用数量',
    MODIFY COLUMN valid_from DATETIME NOT NULL COMMENT '生效时间',
    MODIFY COLUMN valid_to DATETIME NOT NULL COMMENT '失效时间',
    MODIFY COLUMN status VARCHAR(20) DEFAULT 'active' COMMENT '状态: active-有效, inactive-无效, expired-过期',
    MODIFY COLUMN description VARCHAR(500) COMMENT '优惠券描述',
    MODIFY COLUMN created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    MODIFY COLUMN updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间';

-- 2. order_discounts (订单优惠明细表)
ALTER TABLE order_discounts COMMENT '订单优惠明细表';
ALTER TABLE order_discounts
    MODIFY COLUMN id BIGINT NOT NULL AUTO_INCREMENT COMMENT '优惠记录ID',
    MODIFY COLUMN order_id BIGINT NOT NULL COMMENT '订单ID',
    MODIFY COLUMN discount_type VARCHAR(20) NOT NULL COMMENT '优惠类型: coupon-优惠券, promotion-促销活动, vip-VIP优惠',
    MODIFY COLUMN discount_id BIGINT COMMENT '优惠来源ID(优惠券ID或活动ID)',
    MODIFY COLUMN discount_name VARCHAR(100) COMMENT '优惠名称',
    MODIFY COLUMN discount_amount DECIMAL(10,2) NOT NULL COMMENT '优惠金额',
    MODIFY COLUMN created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间';

-- 3. product_favorites (商品收藏表)
ALTER TABLE product_favorites COMMENT '商品收藏表';
ALTER TABLE product_favorites
    MODIFY COLUMN id BIGINT NOT NULL AUTO_INCREMENT COMMENT '收藏ID',
    MODIFY COLUMN user_id BIGINT NOT NULL COMMENT '用户ID',
    MODIFY COLUMN product_id BIGINT NOT NULL COMMENT '商品ID',
    MODIFY COLUMN created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '收藏时间';

-- 4. product_reviews (商品评价表)
ALTER TABLE product_reviews COMMENT '商品评价表';
ALTER TABLE product_reviews
    MODIFY COLUMN id BIGINT NOT NULL AUTO_INCREMENT COMMENT '评价ID',
    MODIFY COLUMN order_id BIGINT NOT NULL COMMENT '订单ID',
    MODIFY COLUMN product_id BIGINT NOT NULL COMMENT '商品ID',
    MODIFY COLUMN user_id BIGINT NOT NULL COMMENT '用户ID',
    MODIFY COLUMN rating TINYINT NOT NULL COMMENT '评分: 1-5星',
    MODIFY COLUMN content TEXT COMMENT '评价内容',
    MODIFY COLUMN images JSON COMMENT '评价图片(JSON数组)',
    MODIFY COLUMN is_anonymous TINYINT DEFAULT 0 COMMENT '是否匿名: 0-否, 1-是',
    MODIFY COLUMN status VARCHAR(20) DEFAULT 'published' COMMENT '状态: published-已发布, hidden-隐藏, deleted-删除',
    MODIFY COLUMN created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '评价时间',
    MODIFY COLUMN updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间';

-- 5. shipments (物流信息表)
ALTER TABLE shipments COMMENT '物流信息表';
ALTER TABLE shipments
    MODIFY COLUMN id BIGINT NOT NULL AUTO_INCREMENT COMMENT '物流单ID',
    MODIFY COLUMN order_id BIGINT NOT NULL COMMENT '订单ID',
    MODIFY COLUMN tracking_no VARCHAR(100) NOT NULL COMMENT '物流单号',
    MODIFY COLUMN company_id BIGINT COMMENT '物流公司ID',
    MODIFY COLUMN status VARCHAR(20) DEFAULT 'pending' COMMENT '状态: pending-待发货, shipped-已发货, delivered-已送达, returned-已退回',
    MODIFY COLUMN shipped_at DATETIME COMMENT '发货时间',
    MODIFY COLUMN delivered_at DATETIME COMMENT '送达时间',
    MODIFY COLUMN current_location VARCHAR(200) COMMENT '当前位置',
    MODIFY COLUMN estimated_delivery DATETIME COMMENT '预计送达时间',
    MODIFY COLUMN created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    MODIFY COLUMN updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间';

-- 6. shipping_companies (物流公司表)
ALTER TABLE shipping_companies COMMENT '物流公司表';
ALTER TABLE shipping_companies
    MODIFY COLUMN id BIGINT NOT NULL AUTO_INCREMENT COMMENT '公司ID',
    MODIFY COLUMN company_name VARCHAR(100) NOT NULL COMMENT '公司名称',
    MODIFY COLUMN company_code VARCHAR(20) NOT NULL COMMENT '公司编码',
    MODIFY COLUMN contact_phone VARCHAR(20) COMMENT '联系电话',
    MODIFY COLUMN website VARCHAR(200) COMMENT '官方网站',
    MODIFY COLUMN status VARCHAR(20) DEFAULT 'active' COMMENT '状态: active-运营中, inactive-停用',
    MODIFY COLUMN created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间';

-- 7. user_coupons (用户优惠券表)
ALTER TABLE user_coupons COMMENT '用户优惠券表';
ALTER TABLE user_coupons
    MODIFY COLUMN id BIGINT NOT NULL AUTO_INCREMENT COMMENT '用户优惠券ID',
    MODIFY COLUMN user_id BIGINT NOT NULL COMMENT '用户ID',
    MODIFY COLUMN coupon_id BIGINT NOT NULL COMMENT '优惠券ID',
    MODIFY COLUMN status VARCHAR(20) DEFAULT 'unused' COMMENT '状态: unused-未使用, used-已使用, expired-已过期',
    MODIFY COLUMN used_order_id BIGINT COMMENT '使用的订单ID',
    MODIFY COLUMN used_at DATETIME COMMENT '使用时间',
    MODIFY COLUMN received_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '领取时间',
    MODIFY COLUMN expired_at DATETIME COMMENT '过期时间';

-- 8. user_points (用户积分流水表)
ALTER TABLE user_points COMMENT '用户积分流水表';
ALTER TABLE user_points
    MODIFY COLUMN id BIGINT NOT NULL AUTO_INCREMENT COMMENT '积分记录ID',
    MODIFY COLUMN user_id BIGINT NOT NULL COMMENT '用户ID',
    MODIFY COLUMN points INT NOT NULL COMMENT '积分变化(正数为获得,负数为消耗)',
    MODIFY COLUMN type VARCHAR(20) NOT NULL COMMENT '类型: order_reward-订单奖励, refund_deduct-退款扣减, exchange-兑换, manual_adjust-手动调整',
    MODIFY COLUMN related_order_id BIGINT COMMENT '关联订单ID',
    MODIFY COLUMN balance_after INT NOT NULL COMMENT '变动后余额',
    MODIFY COLUMN description VARCHAR(200) COMMENT '备注说明',
    MODIFY COLUMN created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '记录时间';
