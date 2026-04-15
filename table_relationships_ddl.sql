-- ============================================
-- 表关联关系管理表
-- ============================================

CREATE TABLE IF NOT EXISTS table_relationships (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    datasource_id BIGINT NOT NULL COMMENT '数据源ID',
    source_table VARCHAR(100) NOT NULL COMMENT '源表名',
    source_column VARCHAR(100) NOT NULL COMMENT '源表字段',
    target_table VARCHAR(100) NOT NULL COMMENT '目标表名',
    target_column VARCHAR(100) NOT NULL COMMENT '目标表字段',
    relationship_type VARCHAR(20) DEFAULT 'MANY_TO_ONE' COMMENT '关联类型: ONE_TO_ONE/MANY_TO_ONE/MANY_TO_MANY',
    confidence FLOAT DEFAULT 0.0 COMMENT '置信度(0-1): LLM推测的可靠程度',
    description VARCHAR(500) COMMENT '关联描述',
    is_active TINYINT DEFAULT 1 COMMENT '是否启用: 0-禁用, 1-启用',
    created_by BIGINT COMMENT '创建人ID',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_relationship (datasource_id, source_table, source_column, target_table, target_column),
    INDEX idx_datasource (datasource_id),
    INDEX idx_source_table (source_table),
    INDEX idx_target_table (target_table)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='表关联关系表';
