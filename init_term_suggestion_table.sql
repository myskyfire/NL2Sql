-- =====================================================
-- 术语自动补全功能 - 数据库表结构
-- =====================================================

-- 1. 创建术语表
CREATE TABLE IF NOT EXISTS term_suggestion (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    term VARCHAR(100) NOT NULL COMMENT '术语名称',
    term_type VARCHAR(20) NOT NULL COMMENT '术语类型：entity/metric/dimension/synonym',
    industry_code VARCHAR(50) DEFAULT 'ecommerce' COMMENT '行业代码',
    usage_count INT DEFAULT 0 COMMENT '使用频率（用于排序）',
    description VARCHAR(500) COMMENT '术语描述',
    is_active TINYINT DEFAULT 1 COMMENT '是否启用：1-启用，0-禁用',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    
    INDEX idx_term (term),
    INDEX idx_industry (industry_code),
    INDEX idx_type (term_type),
    INDEX idx_usage_count (usage_count DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='术语建议表';

-- 2. 插入电商行业术语数据
INSERT INTO term_suggestion (term, term_type, industry_code, usage_count, description) VALUES
-- 业务实体
('订单', 'entity', 'ecommerce', 1250, '客户购买商品的交易记录'),
('商品', 'entity', 'ecommerce', 1180, '可销售的商品单元'),
('用户', 'entity', 'ecommerce', 1100, '注册或购买的用户'),
('店铺', 'entity', 'ecommerce', 850, '商家经营的线上店铺'),
('购物车', 'entity', 'ecommerce', 720, '用户暂存待结算商品的容器'),
('优惠券', 'entity', 'ecommerce', 680, '促销优惠凭证'),
('物流', 'entity', 'ecommerce', 650, '商品运输配送服务'),
('库存', 'entity', 'ecommerce', 920, '商品储备数量'),
('退款', 'entity', 'ecommerce', 580, '订单退款或退货流程'),
('评价', 'entity', 'ecommerce', 540, '用户对商品或服务的反馈'),

-- 关键指标
('订单总额', 'metric', 'ecommerce', 980, '所有订单的成交总金额'),
('订单数量', 'metric', 'ecommerce', 950, '订单总笔数'),
('客单价', 'metric', 'ecommerce', 870, '平均每笔订单的金额'),
('转化率', 'metric', 'ecommerce', 900, '访问用户转化为购买用户的比例'),
('复购率', 'metric', 'ecommerce', 780, '用户再次购买的比例'),
('退货率', 'metric', 'ecommerce', 750, '退货订单占总订单的比例'),
('用户数量', 'metric', 'ecommerce', 890, '独立用户总数'),
('活跃用户', 'metric', 'ecommerce', 820, '日活/月活用户数'),
('库存周转率', 'metric', 'ecommerce', 620, '库存商品周转速度'),
('毛利率', 'metric', 'ecommerce', 700, '毛利润占销售额的比例'),

-- 分析维度
('时间', 'dimension', 'ecommerce', 1050, '按时间维度分析'),
('地区', 'dimension', 'ecommerce', 880, '按地理区域分析'),
('品类', 'dimension', 'ecommerce', 910, '按商品分类分析'),
('渠道', 'dimension', 'ecommerce', 760, '按流量来源分析'),
('支付方式', 'dimension', 'ecommerce', 640, '按支付类型分析'),
('用户等级', 'dimension', 'ecommerce', 690, '按用户层级分析'),

-- 同义词
('定单', 'synonym', 'ecommerce', 120, '订单的同义词'),
('Order', 'synonym', 'ecommerce', 80, '订单的英文'),
('产品', 'synonym', 'ecommerce', 450, '商品的同义词'),
('货品', 'synonym', 'ecommerce', 180, '商品的同义词'),
('Product', 'synonym', 'ecommerce', 90, '商品的英文'),
('顾客', 'synonym', 'ecommerce', 380, '用户的同义词'),
('会员', 'synonym', 'ecommerce', 520, '用户的同义词'),
('User', 'synonym', 'ecommerce', 60, '用户的英文'),
('Customer', 'synonym', 'ecommerce', 45, '用户的英文'),
('GMV', 'synonym', 'ecommerce', 920, '订单总额的缩写'),
('成交金额', 'synonym', 'ecommerce', 650, '订单总额的同义词'),
('销售额', 'synonym', 'ecommerce', 780, '订单总额的同义词'),
('订单数', 'synonym', 'ecommerce', 420, '订单数量的简称'),
('订单量', 'synonym', 'ecommerce', 280, '订单数量的简称'),
('平均订单金额', 'synonym', 'ecommerce', 320, '客单价的全称'),
('AOV', 'synonym', 'ecommerce', 180, '客单价的英文缩写'),
('Conversion Rate', 'synonym', 'ecommerce', 150, '转化率的英文'),
('回购率', 'synonym', 'ecommerce', 240, '复购率的同义词'),
('退款率', 'synonym', 'ecommerce', 380, '退货率的同义词'),
('用户数', 'synonym', 'ecommerce', 520, '用户数量的简称'),
('注册用户', 'synonym', 'ecommerce', 340, '用户数量的同义词'),
('UV', 'synonym', 'ecommerce', 280, '用户数量的缩写'),
('DAU', 'synonym', 'ecommerce', 450, '活跃用户的缩写'),
('MAU', 'synonym', 'ecommerce', 380, '活跃用户的缩写'),
('存货', 'synonym', 'ecommerce', 280, '库存的同义词'),
('Stock', 'synonym', 'ecommerce', 120, '库存的英文');

-- 3. 验证数据
SELECT 
    term_type,
    COUNT(*) as count,
    SUM(usage_count) as total_usage
FROM term_suggestion
WHERE industry_code = 'ecommerce' AND is_active = 1
GROUP BY term_type
ORDER BY term_type;
