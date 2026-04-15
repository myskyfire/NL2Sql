-- ============================================
-- 完整数据库初始化脚本（正确编码）
-- ============================================

USE nl2sql_meta_db;

-- 1. 用户表
CREATE TABLE IF NOT EXISTS users (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '用户ID',
    username VARCHAR(50) NOT NULL UNIQUE COMMENT '用户名',
    password VARCHAR(255) NOT NULL COMMENT '密码(BCrypt加密)',
    real_name VARCHAR(50) COMMENT '真实姓名',
    email VARCHAR(100) COMMENT '邮箱',
    phone VARCHAR(20) COMMENT '手机号',
    role VARCHAR(20) NOT NULL DEFAULT 'user' COMMENT '角色: admin/user',
    status TINYINT NOT NULL DEFAULT 1 COMMENT '状态: 1启用 0禁用',
    last_login_at DATETIME COMMENT '最后登录时间',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_username (username),
    INDEX idx_role (role)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

-- 2. 用户会话表
CREATE TABLE IF NOT EXISTS user_sessions (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    token VARCHAR(255) NOT NULL COMMENT 'Token',
    ip_address VARCHAR(50) COMMENT 'IP地址',
    user_agent VARCHAR(500) COMMENT 'User-Agent',
    expires_at DATETIME COMMENT '过期时间',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    UNIQUE KEY uk_token (token),
    INDEX idx_user_id (user_id),
    INDEX idx_expires (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户会话表';

-- 3. 白名单表
CREATE TABLE IF NOT EXISTS whitelist (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    added_by BIGINT NOT NULL COMMENT '添加人ID',
    reason VARCHAR(500) COMMENT '添加原因',
    expires_at DATETIME COMMENT '过期时间(NULL表示永久)',
    is_active TINYINT NOT NULL DEFAULT 1 COMMENT '是否激活',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    UNIQUE KEY uk_user_id (user_id),
    INDEX idx_expires (expires_at),
    INDEX idx_active (is_active)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='白名单表';

-- 4. 表权限表
CREATE TABLE IF NOT EXISTS table_permissions (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    table_name VARCHAR(100) NOT NULL COMMENT '表名',
    granted_by BIGINT NOT NULL COMMENT '授权人ID',
    granted_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '授权时间',
    expires_at DATETIME COMMENT '过期时间(NULL表示永久)',
    is_active TINYINT NOT NULL DEFAULT 1 COMMENT '是否激活',
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    UNIQUE KEY uk_user_table (user_id, table_name),
    INDEX idx_table_name (table_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='表权限表';

-- 5. 列权限表
CREATE TABLE IF NOT EXISTS column_permissions (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    table_name VARCHAR(100) NOT NULL COMMENT '表名',
    column_name VARCHAR(100) NOT NULL COMMENT '列名',
    can_view TINYINT NOT NULL DEFAULT 1 COMMENT '是否可查看',
    need_desensitize TINYINT NOT NULL DEFAULT 0 COMMENT '是否需脱敏',
    granted_by BIGINT NOT NULL COMMENT '授权人ID',
    granted_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '授权时间',
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    UNIQUE KEY uk_user_table_column (user_id, table_name, column_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='列权限表';

-- 6. 操作日志表
CREATE TABLE IF NOT EXISTS operation_logs (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    user_id BIGINT COMMENT '用户ID',
    username VARCHAR(50) COMMENT '用户名',
    operation VARCHAR(50) NOT NULL COMMENT '操作类型',
    target_type VARCHAR(50) COMMENT '目标类型',
    target_id BIGINT COMMENT '目标ID',
    details JSON COMMENT '操作详情',
    ip_address VARCHAR(50) COMMENT 'IP地址',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '操作时间',
    INDEX idx_user_id (user_id),
    INDEX idx_operation (operation),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='操作日志表';

-- 7. SQL执行日志表
CREATE TABLE IF NOT EXISTS sql_execution_logs (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '日志ID',
    user_id BIGINT COMMENT '用户ID',
    username VARCHAR(50) COMMENT '用户名',
    sql_text TEXT COMMENT '执行的SQL',
    execution_time_ms BIGINT COMMENT '执行时间(毫秒)',
    row_count INT COMMENT '返回行数',
    is_slow_query TINYINT DEFAULT 0 COMMENT '是否慢查询',
    status VARCHAR(20) COMMENT '状态: SUCCESS/FAILED/TIMEOUT',
    error_message TEXT COMMENT '错误信息',
    ip_address VARCHAR(50) COMMENT 'IP地址',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '执行时间',
    INDEX idx_user_id (user_id),
    INDEX idx_status (status),
    INDEX idx_is_slow (is_slow_query),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='SQL执行日志表';

-- 8. 数据源配置表
CREATE TABLE IF NOT EXISTS datasource_config (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    name VARCHAR(100) NOT NULL COMMENT '数据源名称',
    db_type VARCHAR(20) NOT NULL COMMENT '数据库类型: MYSQL/ORACLE/DAMENG/POSTGRESQL',
    host VARCHAR(200) NOT NULL COMMENT '主机地址',
    port INT NOT NULL COMMENT '端口',
    database_name VARCHAR(100) NOT NULL COMMENT '数据库名',
    username VARCHAR(100) NOT NULL COMMENT '用户名',
    password_encrypted VARCHAR(500) NOT NULL COMMENT '加密后的密码',
    encryption_algorithm VARCHAR(50) DEFAULT 'AES-256' COMMENT '加密算法',
    is_active TINYINT DEFAULT 1 COMMENT '是否激活: 0-禁用, 1-启用',
    description VARCHAR(500) COMMENT '描述',
    created_by BIGINT COMMENT '创建人ID',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_name (name),
    INDEX idx_db_type (db_type),
    INDEX idx_is_active (is_active)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='数据源配置表';

-- 9. 表元数据表
CREATE TABLE IF NOT EXISTS table_metadata (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    datasource_id BIGINT NOT NULL COMMENT '数据源ID',
    table_name VARCHAR(100) NOT NULL COMMENT '表名',
    table_comment VARCHAR(500) COMMENT '表注释',
    table_type VARCHAR(20) DEFAULT 'TABLE' COMMENT '表类型: TABLE/VIEW',
    schema_name VARCHAR(100) COMMENT 'Schema名称',
    row_count_estimate BIGINT COMMENT '预估行数',
    data_size_kb BIGINT COMMENT '数据大小(KB)',
    index_size_kb BIGINT COMMENT '索引大小(KB)',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '采集时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_datasource_table (datasource_id, table_name),
    INDEX idx_table_name (table_name),
    INDEX idx_datasource_id (datasource_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='表元数据表';

-- 10. 字段元数据表
CREATE TABLE IF NOT EXISTS column_metadata (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    datasource_id BIGINT NOT NULL COMMENT '数据源ID',
    table_name VARCHAR(100) NOT NULL COMMENT '表名',
    column_name VARCHAR(100) NOT NULL COMMENT '字段名',
    data_type VARCHAR(50) NOT NULL COMMENT '数据类型',
    column_size INT COMMENT '字段长度',
    decimal_digits INT COMMENT '小数位数',
    is_nullable TINYINT DEFAULT 1 COMMENT '是否可空: 0-否, 1-是',
    column_default VARCHAR(500) COMMENT '默认值',
    column_comment VARCHAR(500) COMMENT '字段注释',
    is_primary_key TINYINT DEFAULT 0 COMMENT '是否主键: 0-否, 1-是',
    is_unique TINYINT DEFAULT 0 COMMENT '是否唯一: 0-否, 1-是',
    ordinal_position INT COMMENT '字段顺序',
    character_set_name VARCHAR(50) COMMENT '字符集',
    collation_name VARCHAR(50) COMMENT '排序规则',
    extra_info VARCHAR(200) COMMENT '额外信息(auto_increment等)',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '采集时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_datasource_table (datasource_id, table_name),
    INDEX idx_column_name (column_name),
    INDEX idx_datasource_id (datasource_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='字段元数据表';

-- 11. 外键关系表
CREATE TABLE IF NOT EXISTS foreign_key_metadata (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    datasource_id BIGINT NOT NULL COMMENT '数据源ID',
    fk_name VARCHAR(100) COMMENT '外键约束名',
    table_name VARCHAR(100) NOT NULL COMMENT '主表名',
    column_name VARCHAR(100) NOT NULL COMMENT '主表字段',
    ref_table_name VARCHAR(100) NOT NULL COMMENT '引用表名',
    ref_column_name VARCHAR(100) NOT NULL COMMENT '引用字段',
    update_rule VARCHAR(20) COMMENT '更新规则',
    delete_rule VARCHAR(20) COMMENT '删除规则',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '采集时间',
    UNIQUE KEY uk_fk (datasource_id, fk_name),
    INDEX idx_datasource_id (datasource_id),
    INDEX idx_table_name (table_name),
    INDEX idx_ref_table (ref_table_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='外键关系表';

-- 12. 元数据采集日志表
CREATE TABLE IF NOT EXISTS metadata_sync_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    datasource_id BIGINT NOT NULL COMMENT '数据源ID',
    sync_status VARCHAR(20) NOT NULL COMMENT '同步状态: SUCCESS/FAILED/PARTIAL',
    table_count INT DEFAULT 0 COMMENT '同步表数量',
    column_count INT DEFAULT 0 COMMENT '同步字段数量',
    foreign_key_count INT DEFAULT 0 COMMENT '同步外键数量',
    error_message TEXT COMMENT '错误信息',
    started_at DATETIME COMMENT '开始时间',
    completed_at DATETIME COMMENT '完成时间',
    duration_seconds INT COMMENT '耗时(秒)',
    created_by BIGINT COMMENT '操作人ID',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '记录时间',
    INDEX idx_datasource (datasource_id),
    INDEX idx_status (sync_status),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='元数据采集日志表';

-- 插入admin用户（密码: admin123，BCrypt哈希）
INSERT INTO users (username, password, real_name, role, status) VALUES 
('admin', '$2b$12$2IL6XWNPZo325TVTJiqB4uXYXqkIK142MZbHEU1sPdRlH6hWViWRO', '管理员', 'admin', 1);

-- 验证
SELECT 'Tables created successfully!' AS status;
SHOW TABLES;
