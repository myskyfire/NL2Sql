-- ============================================
-- 自动爬库生成术语脚本（简化版）
-- 直接从information_schema提取表名和字段注释
-- ============================================

-- 1. 清空现有数据
TRUNCATE TABLE term_suggestion;

-- 2. 插入业务实体（表名）
INSERT INTO term_suggestion (term, term_type, industry_code, usage_count, description, is_active)
SELECT 
    IFNULL(table_comment, table_name) AS term,
    'entity' AS term_type,
    'ecommerce' AS industry_code,
    100 AS usage_count,
    CONCAT('表:', table_name) AS description,
    1 AS is_active
FROM information_schema.tables
WHERE table_schema = DATABASE()
  AND table_type = 'BASE TABLE'
  AND table_name NOT LIKE 'sys_%'
  AND table_name NOT LIKE 'temp_%'
ON DUPLICATE KEY UPDATE usage_count = usage_count + VALUES(usage_count);

-- 3. 插入关键指标（数值型字段）
INSERT INTO term_suggestion (term, term_type, industry_code, usage_count, description, is_active)
SELECT 
    IFNULL(column_comment, column_name) AS term,
    'metric' AS term_type,
    'ecommerce' AS industry_code,
    50 AS usage_count,
    CONCAT('字段:', table_name, '.', column_name) AS description,
    1 AS is_active
FROM information_schema.columns
WHERE table_schema = DATABASE()
  AND data_type IN ('int', 'bigint', 'decimal', 'double', 'float')
  AND column_comment IS NOT NULL
  AND column_comment != ''
  AND column_name NOT IN ('id', 'version', 'deleted', 'sort_order')
ON DUPLICATE KEY UPDATE usage_count = usage_count + VALUES(usage_count);

-- 4. 插入分析维度（日期/字符串字段）
INSERT INTO term_suggestion (term, term_type, industry_code, usage_count, description, is_active)
SELECT 
    IFNULL(column_comment, column_name) AS term,
    'dimension' AS term_type,
    'ecommerce' AS industry_code,
    30 AS usage_count,
    CONCAT('字段:', table_name, '.', column_name) AS description,
    1 AS is_active
FROM information_schema.columns
WHERE table_schema = DATABASE()
  AND data_type IN ('date', 'datetime', 'timestamp', 'varchar', 'char')
  AND column_comment IS NOT NULL
  AND column_comment != ''
  AND column_name NOT IN ('id', 'version', 'deleted', 'remark', 'description', 'created_by', 'updated_by')
ON DUPLICATE KEY UPDATE usage_count = usage_count + VALUES(usage_count);

-- 5. 验证结果
SELECT 
    term_type,
    COUNT(*) AS count,
    SUM(usage_count) AS total_usage
FROM term_suggestion
GROUP BY term_type
ORDER BY total_usage DESC;
