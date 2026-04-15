-- ========================================
-- 字段值映射配置示例
-- ========================================

-- 1. 为 users.gender 字段配置性别映射（假设数据库存储 0/1）
UPDATE column_metadata 
SET value_mapping = '{"男":1,"女":0,"male":1,"female":0}'
WHERE table_name = 'users' AND column_name = 'gender';

-- 2. 为 orders.status 字段配置状态映射（假设数据库存储字符串）
UPDATE column_metadata 
SET value_mapping = '{"待支付":"pending","已支付":"paid","已取消":"cancelled"}'
WHERE table_name = 'orders' AND column_name = 'status';

-- 3. 为 products.is_active 字段配置是否映射
UPDATE column_metadata 
SET value_mapping = '{"是":1,"否":0,"yes":1,"no":0}'
WHERE table_name = 'products' AND column_name = 'is_active';

-- ========================================
-- 工作原理说明
-- ========================================

-- 当用户查询："查询昨天性别为男的客户的订单"
-- LLM生成的SQL可能是: WHERE gender = '男'
-- 
-- ValueMappingService会自动转换:
-- WHERE gender = '男'  ->  WHERE gender = 1
--
-- 这样即使用户说"男性"、"男士"、"male"，都能正确转换为数据库的实际值

-- ========================================
-- 内置默认映射规则（无需配置即可使用）
-- ========================================

-- 以下字段名会自动应用默认映射：
-- - gender, sex: 性别映射 (男->1, 女->0)
-- - status, state: 状态映射 (启用->1, 禁用->0)
-- - is_xxx: 是否映射 (是->1, 否->0)
