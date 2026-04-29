-- =====================================================
-- 电商行业术语库初始化脚本
-- 覆盖：业务实体、关键指标、分析维度、同义词映射
-- 数据来源：基于P2_OPTIMIZATION_TERM_AUTOCOMPLETE.md设计
-- =====================================================

-- 1. 插入电商行业模板（如果不存在）
INSERT INTO industry_template (industry_code, industry_name, description, is_active)
VALUES ('ecommerce', '电商行业', '电子商务零售行业，包含订单、商品、用户等核心业务', 1)
ON DUPLICATE KEY UPDATE industry_name = VALUES(industry_name);

-- 2. 插入业务实体概念
INSERT INTO industry_concept (industry_code, concept_type, concept_key, concept_aliases, description, status, confidence, usage_count, created_by)
VALUES 
('ecommerce', 'entity', '订单', '["订单", "定单", "Order"]', '客户购买商品的交易记录', 'approved', 0.95, 1250, 'system'),
('ecommerce', 'entity', '商品', '["商品", "产品", "货品", "Product"]', '可销售的商品单元', 'approved', 0.95, 1180, 'system'),
('ecommerce', 'entity', '用户', '["用户", "顾客", "会员", "User", "Customer"]', '注册或购买的用户', 'approved', 0.93, 1100, 'system'),
('ecommerce', 'entity', '店铺', '["店铺", "商店", "Shop", "Store"]', '商家经营的线上店铺', 'approved', 0.90, 850, 'system'),
('ecommerce', 'entity', '购物车', '["购物车", "Cart"]', '用户暂存待结算商品的容器', 'approved', 0.88, 720, 'system'),
('ecommerce', 'entity', '优惠券', '["优惠券", "Coupon", "代金券"]', '促销优惠凭证', 'approved', 0.87, 680, 'system'),
('ecommerce', 'entity', '物流', '["物流", "配送", "快递", "Logistics"]', '商品运输配送服务', 'approved', 0.85, 650, 'system'),
('ecommerce', 'entity', '库存', '["库存", "存货", "Stock"]', '商品储备数量', 'approved', 0.92, 920, 'system'),
('ecommerce', 'entity', '退款', '["退款", "退货", "Refund"]', '订单退款或退货流程', 'approved', 0.86, 580, 'system'),
('ecommerce', 'entity', '评价', '["评价", "评论", "Review", "Rating"]', '用户对商品或服务的反馈', 'approved', 0.84, 540, 'system');

-- 3. 插入关键指标概念
INSERT INTO industry_concept (industry_code, concept_type, concept_key, concept_aliases, description, status, confidence, usage_count, created_by)
VALUES 
('ecommerce', 'metric', '订单总额', '["订单总额", "GMV", "成交金额", "销售额"]', '所有订单的成交总金额', 'approved', 0.96, 980, 'system'),
('ecommerce', 'metric', '订单数量', '["订单数量", "订单数", "订单量"]', '订单总笔数', 'approved', 0.94, 950, 'system'),
('ecommerce', 'metric', '客单价', '["客单价", "平均订单金额", "AOV"]', '平均每笔订单的金额', 'approved', 0.91, 870, 'system'),
('ecommerce', 'metric', '转化率', '["转化率", "Conversion Rate"]', '访问用户转化为购买用户的比例', 'approved', 0.93, 900, 'system'),
('ecommerce', 'metric', '复购率', '["复购率", "回购率"]', '用户再次购买的比例', 'approved', 0.89, 780, 'system'),
('ecommerce', 'metric', '退货率', '["退货率", "退款率"]', '退货订单占总订单的比例', 'approved', 0.88, 750, 'system'),
('ecommerce', 'metric', '用户数量', '["用户数量", "用户数", "注册用户", "UV"]', '独立用户总数', 'approved', 0.92, 890, 'system'),
('ecommerce', 'metric', '活跃用户', '["活跃用户", "DAU", "MAU"]', '日活/月活用户数', 'approved', 0.90, 820, 'system'),
('ecommerce', 'metric', '库存周转率', '["库存周转率", "Inventory Turnover"]', '库存商品周转速度', 'approved', 0.85, 620, 'system'),
('ecommerce', 'metric', '毛利率', '["毛利率", "Gross Margin"]', '毛利润占销售额的比例', 'approved', 0.87, 700, 'system');

-- 4. 插入分析维度概念
INSERT INTO industry_concept (industry_code, concept_type, concept_key, concept_aliases, description, status, confidence, usage_count, created_by)
VALUES 
('ecommerce', 'dimension', '时间', '["时间", "日期", "Date", "Time"]', '按时间维度分析', 'approved', 0.95, 1050, 'system'),
('ecommerce', 'dimension', '地区', '["地区", "区域", "省份", "城市", "Region"]', '按地理区域分析', 'approved', 0.92, 880, 'system'),
('ecommerce', 'dimension', '品类', '["品类", "类目", "Category"]', '按商品分类分析', 'approved', 0.93, 910, 'system'),
('ecommerce', 'dimension', '渠道', '["渠道", "来源", "Channel"]', '按流量来源分析', 'approved', 0.89, 760, 'system'),
('ecommerce', 'dimension', '支付方式', '["支付方式", "Payment Method"]', '按支付类型分析', 'approved', 0.86, 640, 'system'),
('ecommerce', 'dimension', '用户等级', '["用户等级", "会员等级", "VIP Level"]', '按用户层级分析', 'approved', 0.87, 690, 'system');

-- 5. 插入概念关系（同义词映射）
-- 注意：需要先查询concept_id，这里使用子查询方式
INSERT INTO concept_relation (concept_id, synonym, usage_count, created_by)
SELECT id, '订单', 1250, 'system' FROM industry_concept WHERE industry_code='ecommerce' AND concept_key='订单' LIMIT 1;

INSERT INTO concept_relation (concept_id, synonym, usage_count, created_by)
SELECT id, '定单', 120, 'system' FROM industry_concept WHERE industry_code='ecommerce' AND concept_key='订单' LIMIT 1;

INSERT INTO concept_relation (concept_id, synonym, usage_count, created_by)
SELECT id, 'Order', 80, 'system' FROM industry_concept WHERE industry_code='ecommerce' AND concept_key='订单' LIMIT 1;

INSERT INTO concept_relation (concept_id, synonym, usage_count, created_by)
SELECT id, '商品', 1180, 'system' FROM industry_concept WHERE industry_code='ecommerce' AND concept_key='商品' LIMIT 1;

INSERT INTO concept_relation (concept_id, synonym, usage_count, created_by)
SELECT id, '产品', 450, 'system' FROM industry_concept WHERE industry_code='ecommerce' AND concept_key='商品' LIMIT 1;

INSERT INTO concept_relation (concept_id, synonym, usage_count, created_by)
SELECT id, '货品', 180, 'system' FROM industry_concept WHERE industry_code='ecommerce' AND concept_key='商品' LIMIT 1;

INSERT INTO concept_relation (concept_id, synonym, usage_count, created_by)
SELECT id, 'Product', 90, 'system' FROM industry_concept WHERE industry_code='ecommerce' AND concept_key='商品' LIMIT 1;

INSERT INTO concept_relation (concept_id, synonym, usage_count, created_by)
SELECT id, '用户', 1100, 'system' FROM industry_concept WHERE industry_code='ecommerce' AND concept_key='用户' LIMIT 1;

INSERT INTO concept_relation (concept_id, synonym, usage_count, created_by)
SELECT id, '顾客', 380, 'system' FROM industry_concept WHERE industry_code='ecommerce' AND concept_key='用户' LIMIT 1;

INSERT INTO concept_relation (concept_id, synonym, usage_count, created_by)
SELECT id, '会员', 520, 'system' FROM industry_concept WHERE industry_code='ecommerce' AND concept_key='用户' LIMIT 1;

INSERT INTO concept_relation (concept_id, synonym, usage_count, created_by)
SELECT id, 'User', 60, 'system' FROM industry_concept WHERE industry_code='ecommerce' AND concept_key='用户' LIMIT 1;

INSERT INTO concept_relation (concept_id, synonym, usage_count, created_by)
SELECT id, 'Customer', 45, 'system' FROM industry_concept WHERE industry_code='ecommerce' AND concept_key='用户' LIMIT 1;

INSERT INTO concept_relation (concept_id, synonym, usage_count, created_by)
SELECT id, '订单总额', 980, 'system' FROM industry_concept WHERE industry_code='ecommerce' AND concept_key='订单总额' LIMIT 1;

INSERT INTO concept_relation (concept_id, synonym, usage_count, created_by)
SELECT id, 'GMV', 920, 'system' FROM industry_concept WHERE industry_code='ecommerce' AND concept_key='订单总额' LIMIT 1;

INSERT INTO concept_relation (concept_id, synonym, usage_count, created_by)
SELECT id, '成交金额', 650, 'system' FROM industry_concept WHERE industry_code='ecommerce' AND concept_key='订单总额' LIMIT 1;

INSERT INTO concept_relation (concept_id, synonym, usage_count, created_by)
SELECT id, '销售额', 780, 'system' FROM industry_concept WHERE industry_code='ecommerce' AND concept_key='订单总额' LIMIT 1;

INSERT INTO concept_relation (concept_id, synonym, usage_count, created_by)
SELECT id, '订单数量', 950, 'system' FROM industry_concept WHERE industry_code='ecommerce' AND concept_key='订单数量' LIMIT 1;

INSERT INTO concept_relation (concept_id, synonym, usage_count, created_by)
SELECT id, '订单数', 420, 'system' FROM industry_concept WHERE industry_code='ecommerce' AND concept_key='订单数量' LIMIT 1;

INSERT INTO concept_relation (concept_id, synonym, usage_count, created_by)
SELECT id, '订单量', 280, 'system' FROM industry_concept WHERE industry_code='ecommerce' AND concept_key='订单数量' LIMIT 1;

INSERT INTO concept_relation (concept_id, synonym, usage_count, created_by)
SELECT id, '客单价', 870, 'system' FROM industry_concept WHERE industry_code='ecommerce' AND concept_key='客单价' LIMIT 1;

INSERT INTO concept_relation (concept_id, synonym, usage_count, created_by)
SELECT id, '平均订单金额', 320, 'system' FROM industry_concept WHERE industry_code='ecommerce' AND concept_key='客单价' LIMIT 1;

INSERT INTO concept_relation (concept_id, synonym, usage_count, created_by)
SELECT id, 'AOV', 180, 'system' FROM industry_concept WHERE industry_code='ecommerce' AND concept_key='客单价' LIMIT 1;

INSERT INTO concept_relation (concept_id, synonym, usage_count, created_by)
SELECT id, '转化率', 900, 'system' FROM industry_concept WHERE industry_code='ecommerce' AND concept_key='转化率' LIMIT 1;

INSERT INTO concept_relation (concept_id, synonym, usage_count, created_by)
SELECT id, 'Conversion Rate', 150, 'system' FROM industry_concept WHERE industry_code='ecommerce' AND concept_key='转化率' LIMIT 1;

INSERT INTO concept_relation (concept_id, synonym, usage_count, created_by)
SELECT id, '复购率', 780, 'system' FROM industry_concept WHERE industry_code='ecommerce' AND concept_key='复购率' LIMIT 1;

INSERT INTO concept_relation (concept_id, synonym, usage_count, created_by)
SELECT id, '回购率', 240, 'system' FROM industry_concept WHERE industry_code='ecommerce' AND concept_key='复购率' LIMIT 1;

INSERT INTO concept_relation (concept_id, synonym, usage_count, created_by)
SELECT id, '退货率', 750, 'system' FROM industry_concept WHERE industry_code='ecommerce' AND concept_key='退货率' LIMIT 1;

INSERT INTO concept_relation (concept_id, synonym, usage_count, created_by)
SELECT id, '退款率', 380, 'system' FROM industry_concept WHERE industry_code='ecommerce' AND concept_key='退货率' LIMIT 1;

INSERT INTO concept_relation (concept_id, synonym, usage_count, created_by)
SELECT id, '用户数量', 890, 'system' FROM industry_concept WHERE industry_code='ecommerce' AND concept_key='用户数量' LIMIT 1;

INSERT INTO concept_relation (concept_id, synonym, usage_count, created_by)
SELECT id, '用户数', 520, 'system' FROM industry_concept WHERE industry_code='ecommerce' AND concept_key='用户数量' LIMIT 1;

INSERT INTO concept_relation (concept_id, synonym, usage_count, created_by)
SELECT id, '注册用户', 340, 'system' FROM industry_concept WHERE industry_code='ecommerce' AND concept_key='用户数量' LIMIT 1;

INSERT INTO concept_relation (concept_id, synonym, usage_count, created_by)
SELECT id, 'UV', 280, 'system' FROM industry_concept WHERE industry_code='ecommerce' AND concept_key='用户数量' LIMIT 1;

INSERT INTO concept_relation (concept_id, synonym, usage_count, created_by)
SELECT id, '活跃用户', 820, 'system' FROM industry_concept WHERE industry_code='ecommerce' AND concept_key='活跃用户' LIMIT 1;

INSERT INTO concept_relation (concept_id, synonym, usage_count, created_by)
SELECT id, 'DAU', 450, 'system' FROM industry_concept WHERE industry_code='ecommerce' AND concept_key='活跃用户' LIMIT 1;

INSERT INTO concept_relation (concept_id, synonym, usage_count, created_by)
SELECT id, 'MAU', 380, 'system' FROM industry_concept WHERE industry_code='ecommerce' AND concept_key='活跃用户' LIMIT 1;

INSERT INTO concept_relation (concept_id, synonym, usage_count, created_by)
SELECT id, '库存', 920, 'system' FROM industry_concept WHERE industry_code='ecommerce' AND concept_key='库存' LIMIT 1;

INSERT INTO concept_relation (concept_id, synonym, usage_count, created_by)
SELECT id, '存货', 280, 'system' FROM industry_concept WHERE industry_code='ecommerce' AND concept_key='库存' LIMIT 1;

INSERT INTO concept_relation (concept_id, synonym, usage_count, created_by)
SELECT id, 'Stock', 120, 'system' FROM industry_concept WHERE industry_code='ecommerce' AND concept_key='库存' LIMIT 1;

-- 6. 创建索引优化查询性能
CREATE INDEX idx_concept_industry_status ON industry_concept(industry_code, status);
CREATE INDEX idx_relation_concept ON concept_relation(concept_id);
CREATE INDEX idx_relation_synonym ON concept_relation(synonym);
CREATE INDEX idx_concept_key ON industry_concept(concept_key);

-- 7. 验证数据
SELECT 
    ic.industry_code,
    ic.concept_type,
    COUNT(*) as concept_count,
    SUM(cr.usage_count) as total_usage
FROM industry_concept ic
LEFT JOIN concept_relation cr ON ic.id = cr.concept_id
WHERE ic.industry_code = 'ecommerce' AND ic.status = 'approved'
GROUP BY ic.industry_code, ic.concept_type
ORDER BY ic.concept_type;
