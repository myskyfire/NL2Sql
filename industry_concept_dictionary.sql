-- ========================================
-- 行业概念词典管理表
-- ========================================

-- 1. 行业模板表
CREATE TABLE IF NOT EXISTS industry_template (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    industry_code VARCHAR(50) NOT NULL UNIQUE COMMENT '行业代码（如：ecommerce, finance）',
    industry_name VARCHAR(100) NOT NULL COMMENT '行业名称（如：电子商务）',
    description TEXT COMMENT '行业描述',
    is_active TINYINT(1) DEFAULT 1 COMMENT '是否启用',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_code (industry_code),
    INDEX idx_active (is_active)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='行业模板表';

-- 2. 行业概念映射表
CREATE TABLE IF NOT EXISTS industry_concept (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    industry_code VARCHAR(50) NOT NULL COMMENT '行业代码',
    concept_type VARCHAR(20) NOT NULL COMMENT '概念类型：entity/metric/dimension/table_role',
    concept_key VARCHAR(100) NOT NULL COMMENT '概念键（英文标识，如：revenue）',
    concept_aliases TEXT NOT NULL COMMENT '中文别名列表（JSON数组，如：["销售额","收入","GMV"]）',
    description VARCHAR(500) COMMENT '概念描述',
    usage_count INT DEFAULT 0 COMMENT '使用次数（用于热度排序）',
    confidence DECIMAL(3,2) DEFAULT 1.00 COMMENT '置信度（0-1，RAG学习时降低）',
    source VARCHAR(20) DEFAULT 'manual' COMMENT '来源：manual(手动)/rag_learned(RAG学习)',
    status VARCHAR(20) DEFAULT 'approved' COMMENT '状态：approved(已审核)/pending(待审核)/rejected(已拒绝)',
    created_by VARCHAR(50) COMMENT '创建人',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_industry (industry_code),
    INDEX idx_type (concept_type),
    INDEX idx_key (concept_key),
    INDEX idx_status (status),
    UNIQUE KEY uk_industry_key (industry_code, concept_type, concept_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='行业概念映射表';

-- 3. 数据源行业关联表
CREATE TABLE IF NOT EXISTS datasource_industry_mapping (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    datasource_id BIGINT NOT NULL COMMENT '数据源ID',
    industry_code VARCHAR(50) NOT NULL COMMENT '行业代码',
    priority INT DEFAULT 1 COMMENT '优先级（多行业时排序）',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    UNIQUE KEY uk_datasource (datasource_id),
    INDEX idx_industry (industry_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='数据源行业关联表';

-- 4. 概念关系表（仅支持同义词扩展）
CREATE TABLE IF NOT EXISTS concept_relation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    industry_code VARCHAR(50) NOT NULL COMMENT '行业代码',
    source_concept_key VARCHAR(100) NOT NULL COMMENT '源概念',
    target_concept_key VARCHAR(100) NOT NULL COMMENT '目标概念（同义词）',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_source (source_concept_key),
    UNIQUE KEY uk_relation (industry_code, source_concept_key, target_concept_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='概念同义词表';



-- ========================================
-- 初始化数据
-- ========================================

-- 插入5大行业模板
INSERT INTO industry_template (industry_code, industry_name, description) VALUES
('ecommerce', '电子商务', '电商零售行业，包含订单、商品、客户等核心业务'),
('finance', '金融服务', '银行、证券、保险等金融行业'),
('medical', '医疗健康', '医院、诊所、健康管理等医疗行业'),
('education', '教育培训', '学校、培训机构、在线教育等教育行业'),
('manufacturing', '制造业', '生产制造、供应链管理等行业');

-- 插入电商行业概念示例
INSERT INTO industry_concept (industry_code, concept_type, concept_key, concept_aliases, description, source) VALUES
('ecommerce', 'entity', 'order', '["订单","交易记录","购买记录"]', '客户下单产生的交易记录', 'manual'),
('ecommerce', 'entity', 'product', '["商品","SKU","产品","货品"]', '销售的商品或服务', 'manual'),
('ecommerce', 'entity', 'customer', '["客户","买家","用户","会员"]', '购买商品的用户', 'manual'),
('ecommerce', 'metric', 'revenue', '["销售额","收入","成交金额","GMV","营业额"]', '销售总金额', 'manual'),
('ecommerce', 'metric', 'quantity', '["销量","数量","件数","销售量"]', '销售商品数量', 'manual'),
('ecommerce', 'dimension', 'region', '["地区","省份","城市","区域","地理位置"]', '地理维度', 'manual'),
('ecommerce', 'dimension', 'time', '["时间","日期","月份","季度","年份"]', '时间维度', 'manual');

-- 插入金融行业概念示例
INSERT INTO industry_concept (industry_code, concept_type, concept_key, concept_aliases, description, source) VALUES
('finance', 'entity', 'account', '["账户","客户档案","账号","银行卡"]', '客户开立的金融账户', 'manual'),
('finance', 'entity', 'transaction', '["交易","流水","转账记录","收支记录"]', '资金流动记录', 'manual'),
('finance', 'metric', 'balance', '["余额","资产","存款","账户余额"]', '账户当前余额', 'manual'),
('finance', 'metric', 'amount', '["交易金额","流水金额","转账金额"]', '单笔交易金额', 'manual'),
('finance', 'dimension', 'branch', '["分支机构","网点","分行","支行"]', '银行分支机构', 'manual');

-- 插入医疗行业概念示例
INSERT INTO industry_concept (industry_code, concept_type, concept_key, concept_aliases, description, source) VALUES
('medical', 'entity', 'patient', '["患者","就诊人","病人","病患"]', '接受医疗服务的患者', 'manual'),
('medical', 'entity', 'visit', '["就诊记录","门诊","住院","诊疗"]', '患者的就诊行为', 'manual'),
('medical', 'metric', 'cost', '["医疗费用","药费","检查费","诊疗费"]', '就诊产生的费用', 'manual'),
('medical', 'dimension', 'department', '["科室","部门","专科","诊室"]', '医院科室', 'manual');

-- 插入电商行业概念关系示例
INSERT INTO concept_relation (industry_code, source_concept_key, target_concept_key, relation_type) VALUES
('ecommerce', 'revenue', 'gmv', 'synonym'),  -- GMV是销售额的同义词
('ecommerce', 'revenue', 'sales_amount', 'synonym'),  -- 销售金额也是同义词
('ecommerce', 'order', 'transaction', 'related');  -- 订单与交易相关

-- ========================================
-- 验证数据
-- ========================================

SELECT 
    t.industry_name,
    COUNT(c.id) as concept_count,
    SUM(CASE WHEN c.concept_type = 'entity' THEN 1 ELSE 0 END) as entity_count,
    SUM(CASE WHEN c.concept_type = 'metric' THEN 1 ELSE 0 END) as metric_count,
    SUM(CASE WHEN c.concept_type = 'dimension' THEN 1 ELSE 0 END) as dimension_count
FROM industry_template t
LEFT JOIN industry_concept c ON t.industry_code = c.industry_code AND c.status = 'approved'
GROUP BY t.id, t.industry_name
ORDER BY t.id;
