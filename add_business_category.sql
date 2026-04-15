-- 为 datasource_config 表添加 business_category 字段
-- 用于更精准的数据源智能匹配

ALTER TABLE datasource_config 
ADD COLUMN business_category VARCHAR(100) COMMENT '业务类别（如：订单、财务、用户、库存等）' AFTER description;

-- 为现有数据源设置业务类别
-- 根据数据源名称和数据库名自动匹配

-- 交易/订单类数据源
UPDATE datasource_config 
SET business_category = '订单,交易,trade,order'
WHERE name LIKE '%交易%' 
   OR name LIKE '%订单%'
   OR database_name LIKE '%trade%'
   OR database_name LIKE '%order%'
   OR database_name LIKE '%transaction%';

-- 财务类数据源
UPDATE datasource_config 
SET business_category = '财务,会计,finance,accounting'
WHERE name LIKE '%财务%'
   OR name LIKE '%会计%'
   OR database_name LIKE '%finance%'
   OR database_name LIKE '%accounting%'
   OR database_name LIKE '%financial%';

-- 用户/会员类数据源
UPDATE datasource_config 
SET business_category = '用户,会员,customer,user'
WHERE name LIKE '%用户%'
   OR name LIKE '%会员%'
   OR database_name LIKE '%user%'
   OR database_name LIKE '%customer%'
   OR database_name LIKE '%member%';

-- 库存/仓库类数据源
UPDATE datasource_config 
SET business_category = '库存,仓库,inventory,warehouse'
WHERE name LIKE '%库存%'
   OR name LIKE '%仓库%'
   OR database_name LIKE '%inventory%'
   OR database_name LIKE '%warehouse%'
   OR database_name LIKE '%stock%';

-- 销售/营销类数据源
UPDATE datasource_config 
SET business_category = '销售,营销,sales,marketing'
WHERE name LIKE '%销售%'
   OR name LIKE '%营销%'
   OR database_name LIKE '%sales%'
   OR database_name LIKE '%marketing%';

-- 如果还有未设置的数据源，使用通用类别
UPDATE datasource_config 
SET business_category = '通用,general'
WHERE business_category IS NULL OR business_category = '';

-- 添加索引以提升查询性能
CREATE INDEX idx_business_category ON datasource_config(business_category);

-- 验证填充结果
SELECT id, name, database_name, business_category 
FROM datasource_config 
ORDER BY id;
