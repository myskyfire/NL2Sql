-- ============================================
-- 意图动作映射表
-- 术语 → 可用操作（列表/详情/统计/导出等）
-- ============================================

CREATE TABLE IF NOT EXISTS term_action_mapping (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    term VARCHAR(100) NOT NULL COMMENT '术语名称',
    action VARCHAR(50) NOT NULL COMMENT '操作类型：list/detail/stats/export/compare',
    action_label VARCHAR(50) NOT NULL COMMENT '操作标签（前端显示）',
    sql_template_id VARCHAR(100) COMMENT '关联的SQL模板ID',
    priority INT DEFAULT 0 COMMENT '优先级（越高越靠前）',
    is_active TINYINT DEFAULT 1 COMMENT '是否启用',
    
    INDEX idx_term (term),
    INDEX idx_action (action),
    UNIQUE KEY uk_term_action (term, action)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='术语-动作映射表';

-- 插入常用术语的动作映射
INSERT INTO term_action_mapping (term, action, action_label, sql_template_id, priority, is_active) VALUES
-- 订单相关
('订单', 'list', '列表', 'order_list', 100, 1),
('订单', 'stats', '统计', 'order_stats', 90, 1),
('订单', 'detail', '详情', 'order_detail', 80, 1),
('订单', 'export', '导出', 'order_export', 70, 1),

-- 用户相关
('用户', 'list', '列表', 'user_list', 100, 1),
('用户', 'stats', '活跃度', 'user_activity_stats', 90, 1),
('用户', 'detail', '画像', 'user_profile', 80, 1),

-- 商品相关
('商品', 'list', '列表', 'product_list', 100, 1),
('商品', 'stats', '销售统计', 'product_sales_stats', 90, 1),
('商品', 'detail', '详情', 'product_detail', 80, 1),

-- 金额相关
('订单总额', 'stats', '汇总', 'amount_sum', 100, 1),
('订单总额', 'compare', '同比环比', 'amount_compare', 90, 1),

-- 转化率相关
('转化率', 'stats', '趋势', 'conversion_trend', 100, 1),
('转化率', 'compare', '对比', 'conversion_compare', 90, 1);

-- 验证结果
SELECT 
    term,
    GROUP_CONCAT(action_label ORDER BY priority DESC SEPARATOR ' / ') AS actions
FROM term_action_mapping
WHERE is_active = 1
GROUP BY term
ORDER BY term;
