-- 创建 industry_concept 表
USE nl2sql_meta_db;

CREATE TABLE IF NOT EXISTS industry_concept (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    industry_code VARCHAR(50) NOT NULL COMMENT '行业代码',
    concept_type VARCHAR(20) NOT NULL COMMENT '概念类型：entity/metric/dimension/table_role',
    concept_key VARCHAR(100) NOT NULL COMMENT '概念键（英文标识，如：revenue）',
    concept_aliases TEXT NOT NULL COMMENT '中文别名列表（JSON数组）',
    description VARCHAR(500) COMMENT '概念描述',
    usage_count INT DEFAULT 0 COMMENT '使用次数',
    confidence DECIMAL(3,2) DEFAULT 1.00 COMMENT '置信度（0-1）',
    source VARCHAR(20) DEFAULT 'manual' COMMENT '来源：manual/rag_learned',
    status VARCHAR(20) DEFAULT 'approved' COMMENT '状态：approved/pending/rejected',
    created_by VARCHAR(50) COMMENT '创建人',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_industry (industry_code),
    INDEX idx_type (concept_type),
    INDEX idx_key (concept_key),
    INDEX idx_status (status),
    UNIQUE KEY uk_industry_key (industry_code, concept_type, concept_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='行业概念映射表';
