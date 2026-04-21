package com.nl2sql.core.agent.extension.impl;

import com.nl2sql.core.agent.extension.SemanticMappingExtension;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 电商行业语义映射扩展
 * 
 * 解决LLM对电商领域词汇的误解问题：
 * - "价格" → products.price（商品单价），而非订单统计
 * - "销量" → SUM(order_items.quantity)（销售数量）
 * - "销售额" → SUM(orders.actual_amount)（实际成交金额）
 * 
 * 【使用方式】
 * 1. 激活Profile: 在application.yml中添加 spring.profiles.active=ecommerce
 * 2. 或者移除此类的@Profile注解，使其始终生效
 */
@Slf4j
@Component
@Profile("ecommerce") // ← 默认不激活，需要时移除或配置profile
public class EcommerceSemanticMappingExtension implements SemanticMappingExtension {
    
    @Override
    public Map<String, String> getSemanticMappings() {
        Map<String, String> mappings = new HashMap<>();
        
        // ===== 商品价格相关 =====
        mappings.put("价格", "查询商品的单价字段，直接查询products.price，不要JOIN orders表做SUM聚合");
        mappings.put("单价", "同'价格'，指products.price字段");
        mappings.put("售价", "同'价格'，指products.price字段");
        mappings.put("零售价", "同'价格'，指products.price字段");
        
        // ===== 销售指标相关 =====
        mappings.put("销售额", "统计订单的实际成交金额，使用SUM(orders.actual_amount)，排除已取消订单(status != 'cancelled')");
        mappings.put("销售金额", "同'销售额'，SUM(orders.actual_amount)");
        mappings.put("GMV", "电商平台交易总额，SUM(orders.actual_amount)，包含所有状态订单");
        mappings.put("成交额", "同'销售额'，SUM(orders.actual_amount)");
        mappings.put("营收", "同'销售额'，SUM(orders.actual_amount)");
        
        mappings.put("销量", "统计商品销售数量，使用SUM(order_items.quantity)，需JOIN order_items表");
        mappings.put("销售量", "同'销量'，SUM(order_items.quantity)");
        mappings.put("卖出数量", "同'销量'，SUM(order_items.quantity)");
        mappings.put("售出数量", "同'销量'，SUM(order_items.quantity)");
        
        mappings.put("订单数", "统计订单数量，使用COUNT(DISTINCT orders.id)，排除已取消订单");
        mappings.put("订单量", "同'订单数'，COUNT(DISTINCT orders.id)");
        mappings.put("成交订单", "同'订单数'，但需排除已取消订单");
        
        // ===== 用户指标相关 =====
        mappings.put("用户数", "统计用户数量，使用COUNT(DISTINCT user_id)");
        mappings.put("客户数", "同'用户数'，COUNT(DISTINCT user_id)");
        mappings.put("购买用户", "统计有订单的用户，SELECT DISTINCT user_id FROM orders WHERE status != 'cancelled'");
        mappings.put("复购用户", "统计有多次订单的用户，需GROUP BY user_id HAVING COUNT(*) > 1");
        
        // ===== 时间维度相关 =====
        mappings.put("最近7天", "时间范围：DATE_SUB(CURDATE(), INTERVAL 7 DAY) 到 CURDATE()");
        mappings.put("本月", "时间范围：本月1号到当前日期，DATE_FORMAT(NOW(), '%Y-%m-01') 到 NOW()");
        mappings.put("上月", "时间范围：上月1号到上月最后一天");
        mappings.put("今年", "时间范围：今年1月1号到现在，YEAR(create_time) = YEAR(NOW())");
        
        // ===== 地域相关 =====
        mappings.put("地区", "优先使用users表的province/city字段，而非address_book表");
        mappings.put("省份", "查询users.province字段");
        mappings.put("城市", "查询users.city字段");
        
        log.info("[电商语义映射] 加载{}条电商行业语义规则", mappings.size());
        return mappings;
    }
    
    @Override
    public int getPriority() {
        return 10; // 高优先级，覆盖默认实现
    }
    
    @Override
    public String getName() {
        return "EcommerceSemanticMapping";
    }
}
