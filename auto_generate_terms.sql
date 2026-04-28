-- ============================================
-- 自动爬库生成术语建议脚本
-- 从元数据表提取表名、字段名、枚举值
-- ============================================

-- 1. 清空现有术语数据（可选）
TRUNCATE TABLE term_suggestion;

-- 2. 插入业务实体（表名）
INSERT INTO term_suggestion (term, term_type, industry_code, usage_count, description, is_active)
SELECT 
    table_comment AS term,
    'entity' AS term_type,
    'ecommerce' AS industry_code,
    100 AS usage_count,
    CONCAT('数据表：', table_name) AS description,
    1 AS is_active
FROM information_schema.tables
WHERE table_schema = DATABASE()
  AND table_type = 'BASE TABLE'
  AND table_comment IS NOT NULL
  AND table_comment != ''
ON DUPLICATE KEY UPDATE usage_count = usage_count + VALUES(usage_count);

-- 3. 插入关键指标（数值型字段）
INSERT INTO term_suggestion (term, term_type, industry_code, usage_count, description, is_active)
SELECT 
    column_comment AS term,
    'metric' AS term_type,
    'ecommerce' AS industry_code,
    50 AS usage_count,
    CONCAT('字段：', table_name, '.', column_name, ' (', data_type, ')') AS description,
    1 AS is_active
FROM information_schema.columns
WHERE table_schema = DATABASE()
  AND data_type IN ('int', 'bigint', 'decimal', 'double', 'float')
  AND column_comment IS NOT NULL
  AND column_comment != ''
  AND column_name NOT IN ('id', 'version', 'deleted')
ON DUPLICATE KEY UPDATE usage_count = usage_count + VALUES(usage_count);

-- 4. 插入分析维度（日期/字符串型字段）
INSERT INTO term_suggestion (term, term_type, industry_code, usage_count, description, is_active)
SELECT 
    column_comment AS term,
    'dimension' AS term_type,
    'ecommerce' AS industry_code,
    30 AS usage_count,
    CONCAT('字段：', table_name, '.', column_name, ' (', data_type, ')') AS description,
    1 AS is_active
FROM information_schema.columns
WHERE table_schema = DATABASE()
  AND data_type IN ('date', 'datetime', 'timestamp', 'varchar', 'char')
  AND column_comment IS NOT NULL
  AND column_comment != ''
  AND column_name NOT IN ('id', 'version', 'deleted', 'remark', 'description')
ON DUPLICATE KEY UPDATE usage_count = usage_count + VALUES(usage_count);

-- 5. 插入同义词（常见业务术语映射）
INSERT INTO term_suggestion (term, term_type, industry_code, usage_count, description, is_active) VALUES
('GMV', 'synonym', 'ecommerce', 200, '订单总额', 1),
('DAU', 'synonym', 'ecommerce', 180, '日活跃用户', 1),
('MAU', 'synonym', 'ecommerce', 160, '月活跃用户', 1),
('客单价', 'synonym', 'ecommerce', 150, '平均每单金额', 1),
('转化率', 'synonym', 'ecommerce', 140, '下单转化率', 1),
('复购率', 'synonym', 'ecommerce', 130, '重复购买率', 1),
('到货', 'synonym', 'ecommerce', 120, '已签收', 1),
('买单', 'synonym', 'ecommerce', 110, '支付', 1),
('定单', 'synonym', 'ecommerce', 100, '订单', 1),
('货品', 'synonym', 'ecommerce', 90, '商品', 1),
('买家', 'synonym', 'ecommerce', 80, '用户', 1),
('店家', 'synonym', 'ecommerce', 70, '店铺', 1),
('营收', 'synonym', 'ecommerce', 60, '销售额', 1),
('流水', 'synonym', 'ecommerce', 50, '交易金额', 1),
('新客', 'synonym', 'ecommerce', 40, '新用户', 1),
('老客', 'synonym', 'ecommerce', 30, '老用户', 1),
('爆款', 'synonym', 'ecommerce', 20, '热销商品', 1),
('滞销', 'synonym', 'ecommerce', 10, '冷门商品', 1)
ON DUPLICATE KEY UPDATE usage_count = usage_count + VALUES(usage_count);

-- 6. 验证结果
SELECT 
    term_type,
    COUNT(*) AS count,
    SUM(usage_count) AS total_usage
FROM term_suggestion
GROUP BY term_type
ORDER BY total_usage DESC;
