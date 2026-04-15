-- ========================================
-- 为 trade 数据库添加完整的字段注释（理想情况）
-- 包含详细的值映射说明
-- ========================================

USE trade;

-- ========================================
-- users 表 - 用户表
-- ========================================
ALTER TABLE users 
    MODIFY COLUMN id BIGINT NOT NULL AUTO_INCREMENT COMMENT '用户ID',
    MODIFY COLUMN username VARCHAR(50) NOT NULL COMMENT '用户名',
    MODIFY COLUMN password VARCHAR(255) NOT NULL COMMENT '密码（加密）',
    MODIFY COLUMN real_name VARCHAR(50) COMMENT '真实姓名',
    MODIFY COLUMN gender VARCHAR(10) COMMENT '性别：1-男/0-女',
    MODIFY COLUMN phone VARCHAR(20) COMMENT '手机号',
    MODIFY COLUMN email VARCHAR(100) COMMENT '邮箱地址',
    MODIFY COLUMN province VARCHAR(50) COMMENT '省份',
    MODIFY COLUMN city VARCHAR(50) COMMENT '城市',
    MODIFY COLUMN district VARCHAR(50) COMMENT '区县',
    MODIFY COLUMN created_at DATETIME COMMENT '创建时间',
    MODIFY COLUMN updated_at DATETIME COMMENT '更新时间';

-- ========================================
-- user_addresses 表 - 收货地址表
-- ========================================
ALTER TABLE user_addresses
    MODIFY COLUMN id BIGINT NOT NULL AUTO_INCREMENT COMMENT '地址ID',
    MODIFY COLUMN user_id BIGINT NOT NULL COMMENT '用户ID',
    MODIFY COLUMN receiver_name VARCHAR(50) COMMENT '收货人姓名',
    MODIFY COLUMN receiver_phone VARCHAR(20) COMMENT '收货人电话',
    MODIFY COLUMN province VARCHAR(50) COMMENT '省份',
    MODIFY COLUMN city VARCHAR(50) COMMENT '城市',
    MODIFY COLUMN district VARCHAR(50) COMMENT '区县',
    MODIFY COLUMN detail_address VARCHAR(500) COMMENT '详细地址',
    MODIFY COLUMN is_default TINYINT COMMENT '是否默认地址：1-是/0-否';

-- ========================================
-- orders 表 - 订单主表
-- ========================================
ALTER TABLE orders
    MODIFY COLUMN id BIGINT NOT NULL AUTO_INCREMENT COMMENT '订单ID',
    MODIFY COLUMN order_no VARCHAR(50) NOT NULL COMMENT '订单号',
    MODIFY COLUMN user_id BIGINT NOT NULL COMMENT '用户ID',
    MODIFY COLUMN total_amount DECIMAL(10,2) COMMENT '订单总金额',
    MODIFY COLUMN discount_amount DECIMAL(10,2) COMMENT '优惠金额',
    MODIFY COLUMN actual_amount DECIMAL(10,2) COMMENT '实付金额(GMV)',
    MODIFY COLUMN status VARCHAR(20) COMMENT '订单状态：pending-待支付/paid-已支付/shipped-已发货/completed-已完成/cancelled-已取消',
    MODIFY COLUMN payment_method VARCHAR(50) COMMENT '支付方式：alipay-支付宝/wechat-微信/card-银行卡/cod-货到付款',
    MODIFY COLUMN payment_time DATETIME COMMENT '支付时间',
    MODIFY COLUMN shipping_address VARCHAR(500) COMMENT '收货地址',
    MODIFY COLUMN receiver_name VARCHAR(50) COMMENT '收货人',
    MODIFY COLUMN receiver_phone VARCHAR(20) COMMENT '收货人电话',
    MODIFY COLUMN created_at DATETIME COMMENT '下单时间',
    MODIFY COLUMN paid_at DATETIME COMMENT '支付时间',
    MODIFY COLUMN shipped_at DATETIME COMMENT '发货时间',
    MODIFY COLUMN completed_at DATETIME COMMENT '完成时间';

-- ========================================
-- order_items 表 - 订单明细表
-- ========================================
ALTER TABLE order_items
    MODIFY COLUMN id BIGINT NOT NULL AUTO_INCREMENT COMMENT '明细ID',
    MODIFY COLUMN order_id BIGINT NOT NULL COMMENT '订单ID',
    MODIFY COLUMN product_id BIGINT NOT NULL COMMENT '商品ID',
    MODIFY COLUMN product_name VARCHAR(200) COMMENT '商品名称',
    MODIFY COLUMN product_spec VARCHAR(200) COMMENT '商品规格',
    MODIFY COLUMN quantity INT COMMENT '购买数量',
    MODIFY COLUMN unit_price DECIMAL(10,2) COMMENT '商品单价',
    MODIFY COLUMN subtotal DECIMAL(10,2) COMMENT '小计金额',
    MODIFY COLUMN return_status VARCHAR(20) COMMENT '退货状态：none-无退货/pending-申请中/approved-已批准/completed-已完成',
    MODIFY COLUMN return_quantity INT COMMENT '退货数量';

-- ========================================
-- products 表 - 商品表
-- ========================================
ALTER TABLE products
    MODIFY COLUMN id BIGINT NOT NULL AUTO_INCREMENT COMMENT '商品ID',
    MODIFY COLUMN product_name VARCHAR(200) NOT NULL COMMENT '商品名称',
    MODIFY COLUMN category_id BIGINT COMMENT '分类ID',
    MODIFY COLUMN brand VARCHAR(100) COMMENT '品牌',
    MODIFY COLUMN model VARCHAR(100) COMMENT '型号',
    MODIFY COLUMN specification VARCHAR(200) COMMENT '规格参数',
    MODIFY COLUMN description TEXT COMMENT '商品描述',
    MODIFY COLUMN detail_info TEXT COMMENT '商品详情',
    MODIFY COLUMN price DECIMAL(10,2) COMMENT '商品价格',
    MODIFY COLUMN cost_price DECIMAL(10,2) COMMENT '成本价',
    MODIFY COLUMN status VARCHAR(20) COMMENT '商品状态：active-上架/inactive-下架',
    MODIFY COLUMN created_at DATETIME COMMENT '创建时间',
    MODIFY COLUMN updated_at DATETIME COMMENT '更新时间';

-- ========================================
-- product_categories 表 - 商品分类表
-- ========================================
ALTER TABLE product_categories
    MODIFY COLUMN id BIGINT NOT NULL AUTO_INCREMENT COMMENT '分类ID',
    MODIFY COLUMN category_name VARCHAR(50) NOT NULL COMMENT '分类名称',
    MODIFY COLUMN parent_id BIGINT COMMENT '父分类ID，0表示一级分类',
    MODIFY COLUMN level INT COMMENT '分类层级：1-一级/2-二级/3-三级',
    MODIFY COLUMN description VARCHAR(200) COMMENT '分类描述';

-- ========================================
-- inventory 表 - 库存表
-- ========================================
ALTER TABLE inventory
    MODIFY COLUMN id BIGINT NOT NULL AUTO_INCREMENT COMMENT '库存ID',
    MODIFY COLUMN product_id BIGINT NOT NULL COMMENT '商品ID',
    MODIFY COLUMN warehouse_name VARCHAR(100) COMMENT '仓库名称',
    MODIFY COLUMN stock_quantity INT COMMENT '库存总量',
    MODIFY COLUMN locked_quantity INT COMMENT '锁定库存（已下单未发货）',
    MODIFY COLUMN available_quantity INT COMMENT '可用库存=stock_quantity-locked_quantity',
    MODIFY COLUMN last_updated DATETIME COMMENT '最后更新时间';

-- ========================================
-- returns 表 - 退货表
-- ========================================
ALTER TABLE returns
    MODIFY COLUMN id BIGINT NOT NULL AUTO_INCREMENT COMMENT '退货ID',
    MODIFY COLUMN return_no VARCHAR(50) NOT NULL COMMENT '退货单号',
    MODIFY COLUMN order_id BIGINT NOT NULL COMMENT '订单ID',
    MODIFY COLUMN user_id BIGINT NOT NULL COMMENT '用户ID',
    MODIFY COLUMN reason VARCHAR(500) COMMENT '退货原因',
    MODIFY COLUMN status VARCHAR(20) COMMENT '退货状态：pending-待处理/approved-已批准/rejected-已拒绝/completed-已完成',
    MODIFY COLUMN return_quantity INT COMMENT '退货数量',
    MODIFY COLUMN refund_amount DECIMAL(10,2) COMMENT '退款金额',
    MODIFY COLUMN created_at DATETIME COMMENT '申请时间',
    MODIFY COLUMN processed_at DATETIME COMMENT '处理时间';
