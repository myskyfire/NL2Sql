-- ============================================
-- 修复表注释乱码问题
-- ============================================
-- 说明：此脚本用于修复数据库中因编码问题导致的COMMENT乱码
-- 执行前请备份数据库！
-- ============================================

USE nl2sql_meta_db;

-- 修复元数据管理模块表注释
ALTER TABLE datasource_config COMMENT='数据源配置表';
ALTER TABLE table_metadata COMMENT='表元数据表';
ALTER TABLE column_metadata COMMENT='字段元数据表';
ALTER TABLE foreign_key_metadata COMMENT='外键关系表';
ALTER TABLE metadata_sync_log COMMENT='元数据采集日志表';

-- 修复认证与权限模块表注释
ALTER TABLE users COMMENT='用户表';
ALTER TABLE user_sessions COMMENT='用户会话表';
ALTER TABLE table_permissions COMMENT='表权限表';
ALTER TABLE column_permissions COMMENT='列权限表';
ALTER TABLE whitelist COMMENT='白名单表';

-- 修复审计日志模块表注释
ALTER TABLE operation_logs COMMENT='操作日志表';
ALTER TABLE sql_execution_logs COMMENT='SQL执行日志表';

-- 验证修复结果
SELECT table_name, table_comment 
FROM information_schema.tables 
WHERE table_schema = 'nl2sql_meta_db'
ORDER BY table_name;
