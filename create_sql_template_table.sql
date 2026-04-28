-- ============================================
-- SQL模板表
-- 预定义常用查询的SQL模板，避免LLM调用
-- ============================================

CREATE TABLE IF NOT EXISTS sql_template (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    template_id VARCHAR(100) NOT NULL UNIQUE COMMENT '模板ID（唯一标识）',
    template_name VARCHAR(200) NOT NULL COMMENT '模板名称',
    template_content TEXT NOT NULL COMMENT 'SQL模板内容',
    description VARCHAR(500) COMMENT '模板描述',
    is_active TINYINT DEFAULT 1 COMMENT '是否启用',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    
    INDEX idx_template_id (template_id),
    INDEX idx_active (is_active)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='SQL模板表';

-- 插入常用SQL模板
INSERT INTO sql_template (template_id, template_name, template_content, description, is_active) VALUES
('order_list', '订单列表', 'SELECT * FROM orders WHERE 1=1 {conditions} ORDER BY created_at DESC LIMIT 100', '查询订单列表，支持时间/状态筛选', 1),
('order_stats', '订单统计', 'SELECT COUNT(*) AS order_count, SUM(amount) AS total_amount, AVG(amount) AS avg_amount FROM orders WHERE 1=1 {conditions}', '订单汇总统计', 1),
('order_detail', '订单详情', 'SELECT * FROM orders WHERE id = {id}', '查询单个订单详情', 1),
('user_list', '用户列表', 'SELECT * FROM users WHERE 1=1 {conditions} ORDER BY created_at DESC LIMIT 100', '查询用户列表', 1),
('user_activity_stats', '用户活跃度', 'SELECT COUNT(DISTINCT user_id) AS dau FROM user_activity WHERE activity_date = {date}', '日活跃用户统计', 1),
('product_list', '商品列表', 'SELECT * FROM products WHERE 1=1 {conditions} ORDER BY sales DESC LIMIT 100', '查询商品列表，按销量排序', 1),
('product_sales_stats', '商品销售统计', 'SELECT product_id, SUM(quantity) AS total_sales, SUM(amount) AS total_revenue FROM order_items GROUP BY product_id ORDER BY total_sales DESC LIMIT 10', '商品销售排行', 1),
('amount_sum', '金额汇总', 'SELECT SUM(amount) AS total_amount FROM orders WHERE 1=1 {conditions}', '订单金额汇总', 1),
('amount_compare', '金额同比环比', 'SELECT DATE_FORMAT(created_at, ''%Y-%m'') AS month, SUM(amount) AS monthly_amount FROM orders GROUP BY month ORDER BY month', '按月统计订单金额', 1),
('conversion_trend', '转化率趋势', 'SELECT DATE_FORMAT(created_at, ''%Y-%m-%d'') AS date, COUNT(*) AS orders FROM orders WHERE 1=1 {conditions} GROUP BY date ORDER BY date', '每日订单趋势', 1),
('conversion_compare', '渠道对比', 'SELECT channel, COUNT(*) AS orders, SUM(amount) AS total_amount FROM orders GROUP BY channel ORDER BY total_amount DESC', '按渠道统计订单', 1);

-- 验证结果
SELECT template_id, template_name, LEFT(template_content, 50) AS preview FROM sql_template WHERE is_active = 1;
