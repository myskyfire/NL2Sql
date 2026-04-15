-- ============================================
-- 元数据管理模块 DDL
-- ============================================

-- 1. 数据源配置表
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

-- 2. 表元数据表
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

-- 3. 字段元数据表
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

-- 4. 外键关系表
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

-- 5. 元数据采集日志表
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

-- 6. 插入默认数据源配置示例（可选）
-- INSERT INTO datasource_config (name, db_type, host, port, database_name, username, password_encrypted, description)
-- VALUES ('生产库', 'MYSQL', 'localhost', 3306, 'production_db', 'root', 'encrypted_password_here', '生产环境数据库');
