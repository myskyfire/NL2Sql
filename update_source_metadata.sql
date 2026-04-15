-- 修改trade库表字段注释，区分用户注册地和收货地址
USE trade;

-- users表：用户注册地
ALTER TABLE users MODIFY COLUMN province VARCHAR(50) COMMENT '用户注册省份';
ALTER TABLE users MODIFY COLUMN city VARCHAR(50) COMMENT '用户注册城市';
ALTER TABLE users MODIFY COLUMN district VARCHAR(50) COMMENT '用户注册区县';

-- user_addresses表：收货地址
ALTER TABLE user_addresses MODIFY COLUMN province VARCHAR(50) COMMENT '收货地址省份';
ALTER TABLE user_addresses MODIFY COLUMN city VARCHAR(50) COMMENT '收货地址城市';
ALTER TABLE user_addresses MODIFY COLUMN district VARCHAR(50) COMMENT '收货地址区县';

-- orders表：收货地址文本
ALTER TABLE orders MODIFY COLUMN shipping_address VARCHAR(500) COMMENT '收货地址文本（非结构化，不可用于JOIN）';

-- 验证修改结果
SELECT 
    TABLE_NAME, 
    COLUMN_NAME, 
    COLUMN_COMMENT 
FROM INFORMATION_SCHEMA.COLUMNS 
WHERE TABLE_SCHEMA = 'trade' 
    AND COLUMN_NAME IN ('province', 'city', 'district', 'shipping_address')
ORDER BY TABLE_NAME, COLUMN_NAME;
