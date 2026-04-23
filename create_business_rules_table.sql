-- 业务规则配置表
CREATE TABLE IF NOT EXISTS business_rules (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    rule_id VARCHAR(100) NOT NULL UNIQUE COMMENT '规则ID（唯一标识）',
    rule_name VARCHAR(200) NOT NULL COMMENT '规则名称',
    rule_type VARCHAR(50) NOT NULL COMMENT '规则类型（table_mapping/time_parsing/synonym/field_mapping）',
    rule_content JSON NOT NULL COMMENT '规则内容（JSON格式）',
    datasource_id BIGINT DEFAULT NULL COMMENT '适用数据源ID（NULL表示全局）',
    priority INT DEFAULT 0 COMMENT '优先级（数字越大优先级越高）',
    enabled TINYINT(1) DEFAULT 1 COMMENT '是否启用（1=启用，0=禁用）',
    description TEXT COMMENT '规则描述',
    created_by VARCHAR(100) DEFAULT NULL COMMENT '创建人',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_by VARCHAR(100) DEFAULT NULL COMMENT '更新人',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_rule_type (rule_type),
    INDEX idx_datasource_id (datasource_id),
    INDEX idx_enabled (enabled),
    INDEX idx_priority (priority DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='业务规则配置表';

-- 插入示例数据
INSERT INTO business_rules (rule_id, rule_name, rule_type, rule_content, datasource_id, priority, enabled, description) VALUES
('table_mapping_orders', '订单表映射', 'table_mapping', 
 '{"naturalName": "订单", "tableName": "orders"}', 
 NULL, 10, 1, '将"订单"映射到orders表'),

('time_parse_last_month', '上月时间解析', 'time_parsing',
 '{"pattern": "上个月", "sqlFormat": "DATE_FORMAT(DATE_SUB(CURDATE(), INTERVAL 1 MONTH), \'%Y-%m\')"}',
 NULL, 10, 1, '将"上个月"解析为SQL日期格式'),

('synonym_revenue', '收入同义词', 'synonym',
 '{"word": "收入", "standardWord": "销售额"}',
 NULL, 5, 1, '将"收入"标准化为"销售额"'),

('field_mapping_order_amount', '订单金额字段映射', 'field_mapping',
 '{"tableName": "orders", "naturalName": "金额", "fieldName": "total_amount"}',
 NULL, 10, 1, '将orders表的"金额"映射到total_amount字段');
