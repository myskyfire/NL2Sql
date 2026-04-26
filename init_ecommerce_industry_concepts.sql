-- ============================================
-- 电商行业概念配置 - ecommerce
-- ============================================

USE nl2sql_meta_db;

-- 1. 确保行业模板存在
INSERT IGNORE INTO industry_template (industry_code, industry_name, description, is_active) VALUES
('ecommerce', '电子商务', '电商平台业务，包含订单、商品、用户等核心业务', 1);

-- 2. 插入业务实体 (entity)
INSERT INTO industry_concept (industry_code, concept_type, concept_key, concept_aliases, description, source, status) VALUES
('ecommerce', 'entity', 'user', '["用户","客户","买家","会员","顾客"]', '系统注册用户，存储在users表', 'manual', 'approved'),
('ecommerce', 'entity', 'order', '["订单","交易","购买记录"]', '用户下单生成的交易记录，主表orders', 'manual', 'approved'),
('ecommerce', 'entity', 'product', '["商品","产品","SKU","货品","库存"]', '可销售的商品，存储在products表', 'manual', 'approved'),
('ecommerce', 'entity', 'category', '["分类","类目","品类"]', '商品分类，如电子产品/服装/食品', 'manual', 'approved'),
('ecommerce', 'entity', 'address', '["地址","收货地址","配送地址"]', '用户收货地址，存储在user_addresses表', 'manual', 'approved'),
('ecommerce', 'entity', 'logistics', '["物流","快递","发货","配送","运输"]', '物流配送信息', 'manual', 'approved'),
('ecommerce', 'entity', 'after_sale', '["退款","退货","售后","投诉","评价"]', '售后服务相关', 'manual', 'approved');

-- 3. 插入关键指标 (metric)
INSERT INTO industry_concept (industry_code, concept_type, concept_key, concept_aliases, description, source, status) VALUES
('ecommerce', 'metric', 'revenue', '["销售额","收入","GMV","成交金额","实付金额","支付","付款","金额"]', '订单实际支付金额(orders.actual_amount)，不含退款', 'manual', 'approved'),
('ecommerce', 'metric', 'order_count', '["订单数","交易量","下单次数","订单","下单","购买","成交"]', '订单数量统计', 'manual', 'approved'),
('ecommerce', 'metric', 'quantity', '["销量","数量","件数","购买数量"]', '商品销售数量(order_items.quantity)', 'manual', 'approved'),
('ecommerce', 'metric', 'profit', '["利润","毛利","盈利"]', '销售额-成本价*销量', 'manual', 'approved'),
('ecommerce', 'metric', 'conversion_rate', '["转化率","下单率"]', '下单用户数/访问用户数', 'manual', 'approved'),
('ecommerce', 'metric', 'return_rate', '["退货率","退款率"]', '退货订单数/总订单数', 'manual', 'approved'),
('ecommerce', 'metric', 'avg_order_value', '["客单价","平均订单金额"]', '销售额/订单数', 'manual', 'approved');

-- 4. 插入分析维度 (dimension)
INSERT INTO industry_concept (industry_code, concept_type, concept_key, concept_aliases, description, source, status) VALUES
('ecommerce', 'dimension', 'region', '["地区","省份","城市","区域","地域"]', '地理维度，来自users.province/city或orders.shipping_address', 'manual', 'approved'),
('ecommerce', 'dimension', 'time', '["时间","日期","月份","季度","年份"]', '时间维度，按orders.created_at分组', 'manual', 'approved'),
('ecommerce', 'dimension', 'category', '["分类","商品类别","品类"]', '商品分类维度，来自product_categories', 'manual', 'approved'),
('ecommerce', 'dimension', 'brand', '["品牌","牌子"]', '商品品牌维度，来自products.brand', 'manual', 'approved'),
('ecommerce', 'dimension', 'payment_method', '["支付方式","付款渠道"]', '支付方式维度，如微信/支付宝/银行卡', 'manual', 'approved'),
('ecommerce', 'dimension', 'order_status', '["订单状态","状态"]', '订单状态维度：待支付/已支付/已发货/已完成', 'manual', 'approved');

-- 5. 插入表角色说明 (table_role)
INSERT INTO industry_concept (industry_code, concept_type, concept_key, concept_aliases, description, source, status) VALUES
('ecommerce', 'table_role', 'main_table', '["主表","核心表","事实表"]', '包含核心业务数据和主要指标的表，如orders(订单)、order_items(明细)', 'manual', 'approved'),
('ecommerce', 'table_role', 'dimension_table', '["维度表","辅助表","字典表"]', '提供分类、描述信息的辅助表，如users(用户)、products(商品)、product_categories(分类)', 'manual', 'approved'),
('ecommerce', 'table_role', 'relation_table', '["关联表","中间表","桥接表"]', '连接多张表的中间表（多对多关系），本系统中暂无典型场景', 'manual', 'approved');

-- 6. 插入使用场景区分规则 (usage_rule) - ✅ 新增
INSERT INTO industry_concept (industry_code, concept_type, concept_key, concept_aliases, description, source, status) VALUES
('ecommerce', 'usage_rule', 'users_vs_addresses', '[]', 
 'user_addresses是地址表(存储收货信息:receiver_name/province/city/detail_address)，仅用于地址相关查询。users是用户主表(存储账户信息:username/real_name/gender/email)，当需要用户名、性别、邮箱等用户属性时，必须通过orders.user_id→users.id关联users表，不要混淆这两张表', 
 'manual', 'approved'),
('ecommerce', 'usage_rule', 'orders_vs_order_items', '[]', 
 'orders是订单主表(存储订单级信息:order_no/total_amount/status)，order_items是订单明细表(存储商品级信息:product_id/quantity/unit_price)。查询订单总额用orders，查询具体买了什么商品用order_items，两者常需JOIN使用', 
 'manual', 'approved');

-- 7. 插入同义词关系 (concept_relation)
INSERT INTO concept_relation (industry_code, source_concept_key, target_concept_key) VALUES
('ecommerce', 'revenue', 'gmv'),
('ecommerce', 'revenue', '销售额'),
('ecommerce', 'revenue', '成交金额'),
('ecommerce', 'order_count', '订单数'),
('ecommerce', 'order_count', '交易量'),
('ecommerce', 'quantity', '销量'),
('ecommerce', 'quantity', '件数'),
('ecommerce', 'region', '地区'),
('ecommerce', 'region', '省份'),
('ecommerce', 'time', '时间'),
('ecommerce', 'time', '日期');

-- 8. 验证插入结果
SELECT 
    concept_type AS '类型',
    COUNT(*) AS '数量'
FROM industry_concept 
WHERE industry_code = 'ecommerce' AND status = 'approved'
GROUP BY concept_type
ORDER BY concept_type;
