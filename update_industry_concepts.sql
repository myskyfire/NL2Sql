USE nl2sql_meta_db;

-- 1. 更新 revenue 的别名（增加支付相关关键词）
UPDATE industry_concept 
SET concept_aliases = '["销售额","收入","GMV","成交金额","实付金额","支付","付款","金额"]' 
WHERE industry_code = 'ecommerce' AND concept_type = 'metric' AND concept_key = 'revenue';

-- 2. 更新 order_count 的别名（增加订单相关关键词）
UPDATE industry_concept 
SET concept_aliases = '["订单数","交易量","下单次数","订单","下单","购买","成交"]' 
WHERE industry_code = 'ecommerce' AND concept_type = 'metric' AND concept_key = 'order_count';

-- 3. 更新 user 的别名（增加"顾客"）
UPDATE industry_concept 
SET concept_aliases = '["用户","客户","买家","会员","顾客"]' 
WHERE industry_code = 'ecommerce' AND concept_type = 'entity' AND concept_key = 'user';

-- 4. 更新 product 的别名（增加"库存"）
UPDATE industry_concept 
SET concept_aliases = '["商品","产品","SKU","货品","库存"]' 
WHERE industry_code = 'ecommerce' AND concept_type = 'entity' AND concept_key = 'product';

-- 5. 新增 logistics entity
INSERT IGNORE INTO industry_concept (industry_code, concept_type, concept_key, concept_aliases, description, source, status) 
VALUES ('ecommerce', 'entity', 'logistics', '["物流","快递","发货","配送","运输"]', '物流配送信息', 'manual', 'approved');

-- 6. 新增 after_sale entity
INSERT IGNORE INTO industry_concept (industry_code, concept_type, concept_key, concept_aliases, description, source, status) 
VALUES ('ecommerce', 'entity', 'after_sale', '["退款","退货","售后","投诉","评价"]', '售后服务相关', 'manual', 'approved');

-- 验证结果
SELECT concept_type AS type, concept_key AS key_name, concept_aliases AS aliases
FROM industry_concept 
WHERE industry_code = 'ecommerce' AND status = 'approved' AND concept_type IN ('entity', 'metric')
ORDER BY FIELD(concept_type, 'metric', 'entity'), concept_key;
