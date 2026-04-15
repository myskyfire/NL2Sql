-- 更新地区字段的注释，明确区分用户注册地和收货地址
USE nl2sql_meta_db;

-- users表的地区字段 = 用户注册地
UPDATE column_metadata 
SET column_comment = '用户注册省份' 
WHERE table_name = 'users' AND column_name = 'province';

UPDATE column_metadata 
SET column_comment = '用户注册城市' 
WHERE table_name = 'users' AND column_name = 'city';

UPDATE column_metadata 
SET column_comment = '用户注册区县' 
WHERE table_name = 'users' AND column_name = 'district';

-- user_addresses表的地区字段 = 收货地址
UPDATE column_metadata 
SET column_comment = '收货地址省份' 
WHERE table_name = 'user_addresses' AND column_name = 'province';

UPDATE column_metadata 
SET column_comment = '收货地址城市' 
WHERE table_name = 'user_addresses' AND column_name = 'city';

UPDATE column_metadata 
SET column_comment = '收货地址区县' 
WHERE table_name = 'user_addresses' AND column_name = 'district';

-- orders表的shipping_address明确标注为非结构化文本
UPDATE column_metadata 
SET column_comment = '收货地址文本（非结构化，不可用于JOIN）' 
WHERE table_name = 'orders' AND column_name = 'shipping_address';

-- 验证更新结果
SELECT table_name, column_name, column_comment 
FROM column_metadata 
WHERE column_name IN ('province', 'city', 'district', 'shipping_address')
ORDER BY table_name, column_name;
