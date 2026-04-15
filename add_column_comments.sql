-- ========================================
-- 为 trade 数据库添加字段注释
-- ========================================

USE trade;

-- users 表字段注释
ALTER TABLE users 
    MODIFY COLUMN username VARCHAR(50) NOT NULL COMMENT '用户名',
    MODIFY COLUMN gender VARCHAR(10) COMMENT '性别：男/女',
    MODIFY COLUMN phone VARCHAR(20) COMMENT '手机号',
    MODIFY COLUMN email VARCHAR(100) COMMENT '邮箱地址';

-- orders 表字段注释
ALTER TABLE orders
    MODIFY COLUMN order_no VARCHAR(50) NOT NULL COMMENT '订单号',
    MODIFY COLUMN user_id BIGINT NOT NULL COMMENT '用户ID',
    MODIFY COLUMN total_amount DECIMAL(10,2) COMMENT '订单总金额',
    MODIFY COLUMN actual_amount DECIMAL(10,2) COMMENT '实付金额',
    MODIFY COLUMN status VARCHAR(20) COMMENT '订单状态：pending-待支付/paid-已支付/shipped-已发货/completed-已完成/cancelled-已取消',
    MODIFY COLUMN created_at DATETIME COMMENT '下单时间',
    MODIFY COLUMN paid_at DATETIME COMMENT '支付时间';

-- order_items 表字段注释
ALTER TABLE order_items
    MODIFY COLUMN order_id BIGINT NOT NULL COMMENT '订单ID',
    MODIFY COLUMN product_id BIGINT NOT NULL COMMENT '商品ID',
    MODIFY COLUMN product_name VARCHAR(200) COMMENT '商品名称',
    MODIFY COLUMN product_spec VARCHAR(100) COMMENT '商品规格',
    MODIFY COLUMN quantity INT COMMENT '购买数量',
    MODIFY COLUMN price DECIMAL(10,2) COMMENT '商品单价',
    MODIFY COLUMN subtotal DECIMAL(10,2) COMMENT '小计金额';

-- products 表字段注释
ALTER TABLE products
    MODIFY COLUMN product_name VARCHAR(200) NOT NULL COMMENT '商品名称',
    MODIFY COLUMN category_id BIGINT COMMENT '分类ID',
    MODIFY COLUMN price DECIMAL(10,2) COMMENT '商品价格',
    MODIFY COLUMN stock INT COMMENT '库存数量',
    MODIFY COLUMN status TINYINT COMMENT '商品状态：1-上架/0-下架',
    MODIFY COLUMN description TEXT COMMENT '商品描述';

-- product_categories 表字段注释
ALTER TABLE product_categories
    MODIFY COLUMN category_name VARCHAR(100) NOT NULL COMMENT '分类名称',
    MODIFY COLUMN parent_id BIGINT COMMENT '父分类ID',
    MODIFY COLUMN level INT COMMENT '分类层级';

-- inventory 表字段注释
ALTER TABLE inventory
    MODIFY COLUMN product_id BIGINT NOT NULL COMMENT '商品ID',
    MODIFY COLUMN warehouse VARCHAR(50) COMMENT '仓库名称',
    MODIFY COLUMN quantity INT COMMENT '库存数量',
    MODIFY COLUMN locked_quantity INT COMMENT '锁定库存';

-- returns 表字段注释
ALTER TABLE returns
    MODIFY COLUMN return_no VARCHAR(50) NOT NULL COMMENT '退货单号',
    MODIFY COLUMN order_id BIGINT NOT NULL COMMENT '订单ID',
    MODIFY COLUMN reason VARCHAR(500) COMMENT '退货原因',
    MODIFY COLUMN status VARCHAR(20) COMMENT '退货状态：pending-待处理/approved-已批准/rejected-已拒绝',
    MODIFY COLUMN created_at DATETIME COMMENT '申请时间';

-- user_addresses 表字段注释
ALTER TABLE user_addresses
    MODIFY COLUMN user_id BIGINT NOT NULL COMMENT '用户ID',
    MODIFY COLUMN receiver_name VARCHAR(50) COMMENT '收货人姓名',
    MODIFY COLUMN receiver_phone VARCHAR(20) COMMENT '收货人电话',
    MODIFY COLUMN province VARCHAR(50) COMMENT '省份',
    MODIFY COLUMN city VARCHAR(50) COMMENT '城市',
    MODIFY COLUMN district VARCHAR(50) COMMENT '区县',
    MODIFY COLUMN detail_address VARCHAR(500) COMMENT '详细地址',
    MODIFY COLUMN is_default TINYINT COMMENT '是否默认地址：1-是/0-否';
