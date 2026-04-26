package com.nl2sql.core.cache;

import lombok.extern.slf4j.Slf4j;

/**
 * 电商行业目标提取器
 * 从查询中提取电商特定目标关键词（订单、商品、用户等）
 */
@Slf4j
public class EcommerceTargetExtractor implements IndustryTargetExtractor {
    
    // 电商核心业务对象
    private static final String[] ORDER_KEYWORDS = {"订单", "下单", "购买", "成交"};
    private static final String[] PRODUCT_KEYWORDS = {"商品", "产品", "SKU", "货品", "库存"};
    private static final String[] USER_KEYWORDS = {"用户", "客户", "会员", "买家", "顾客"};
    private static final String[] PAYMENT_KEYWORDS = {"支付", "付款", "金额", "收入", "GMV", "销售额"};
    private static final String[] LOGISTICS_KEYWORDS = {"物流", "快递", "发货", "配送", "运输"};
    private static final String[] AFTER_SALE_KEYWORDS = {"退款", "退货", "售后", "投诉", "评价"};
    
    @Override
    public String extract(String query) {
        if (query == null || query.trim().isEmpty()) {
            return null;
        }
        
        // ✅ 修正优先级：支付/金额类 > 订单 > 商品 > 用户 > 物流 > 售后
        // 原因：GMV/销售额等是核心业务指标，优先级应高于普通实体
        if (containsAny(query, PAYMENT_KEYWORDS)) {
            return "支付";
        }
        if (containsAny(query, ORDER_KEYWORDS)) {
            return "订单";
        }
        if (containsAny(query, PRODUCT_KEYWORDS)) {
            return "商品";
        }
        if (containsAny(query, USER_KEYWORDS)) {
            return "用户";
        }
        if (containsAny(query, LOGISTICS_KEYWORDS)) {
            return "物流";
        }
        if (containsAny(query, AFTER_SALE_KEYWORDS)) {
            return "售后";
        }
        
        return null;
    }
    
    /**
     * 检查查询是否包含任意关键词
     */
    private boolean containsAny(String query, String[] keywords) {
        for (String keyword : keywords) {
            if (query.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
