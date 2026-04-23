-- ============================================
-- 为表权限表添加数据库维度
-- ============================================

-- 1. 添加 database_name 字段
ALTER TABLE table_permissions 
ADD COLUMN database_name VARCHAR(100) COMMENT '数据库名' AFTER user_id;

-- 2. 更新唯一索引（包含database_name）
ALTER TABLE table_permissions DROP INDEX uk_user_table;
ALTER TABLE table_permissions ADD UNIQUE KEY uk_user_db_table (user_id, database_name, table_name);

-- 3. 添加数据库索引
ALTER TABLE table_permissions ADD INDEX idx_database (database_name);

-- 4. 为现有数据设置默认数据库名（需要根据实际情况调整）
UPDATE table_permissions SET database_name = 'default_db' WHERE database_name IS NULL;

-- 5. 修改字段为非空约束
ALTER TABLE table_permissions MODIFY COLUMN database_name VARCHAR(100) NOT NULL COMMENT '数据库名';

SELECT '✅ 表权限表已添加数据库维度' as message;
