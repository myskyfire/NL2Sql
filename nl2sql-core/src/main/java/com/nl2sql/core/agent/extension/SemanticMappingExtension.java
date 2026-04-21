package com.nl2sql.core.agent.extension;

import java.util.Map;

/**
 * NL2SQL语义映射扩展点
 * 
 * 用于将自然语言中的行业特定词汇映射到数据库语义
 * 例如：
 * - "价格" → products.price（而非订单统计）
 * - "销量" → SUM(order_items.quantity)
 * - "GMV" → SUM(orders.actual_amount)
 * 
 * 实现方式：
 * 1. 标准产品提供默认空实现
 * 2. 行业插件通过实现此接口注入领域知识
 */
public interface SemanticMappingExtension {
    
    /**
     * 获取语义映射规则
     * 
     * @return Map<自然语言词汇, 数据库语义描述>
     * 示例：
     * {
     *   "价格": "查询商品的单价字段，通常是products.price",
     *   "单价": "同'价格'",
     *   "销售额": "统计订单的实际成交金额，SUM(orders.actual_amount)",
     *   "销量": "统计商品销售数量，SUM(order_items.quantity)"
     * }
     */
    Map<String, String> getSemanticMappings();
    
    /**
     * 扩展点优先级（数字越小优先级越高）
     * 默认实现返回100，行业插件可返回更低值以获得更高优先级
     */
    default int getPriority() {
        return 100;
    }
    
    /**
     * 扩展点名称（用于日志和调试）
     */
    default String getName() {
        return this.getClass().getSimpleName();
    }
}
