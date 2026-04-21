package com.nl2sql.core.llm.extension.example;

import com.nl2sql.core.llm.LocationSemanticService;
import com.nl2sql.core.llm.extension.IndustryConceptExtension;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * ✅ 电商行业概念扩展
 * 
 * 专门处理电商行业特有的业务逻辑：
 * 1. 地域语义识别（用户注册地 vs 订单配送地 vs 地址簿）
 * 2. 电商指标计算（GMV、复购率等）
 * 3. 订单状态过滤（排除取消订单等）
 */
@Slf4j
@Component
@Profile("ecommerce") // ← 默认不激活，需要时移除此行或在application.yml中配置
public class EcommerceConceptExtension implements IndustryConceptExtension {
    
    @Autowired
    private LocationSemanticService locationSemanticService;
    
    // ==================== Level 2: SQL生成干预 ====================
    
    @Override
    public String enhancePromptBeforeGeneration(String originalPrompt, String question, Long datasourceId) {
        // 检测是否包含地域相关关键词
        LocationSemanticService.LocationMatchResult match = 
            locationSemanticService.resolve(question, datasourceId);
        
        if (match == null) {
            log.debug("[电商扩展] 未匹配到地域规则，不干预");
            return null;
        }
        
        log.info("[电商扩展] 匹配到地域规则: type={}, table={}", 
            match.getRuleType(), match.getTargetTable());
        
        // 构建地域语义提示词
        String locationHint = buildLocationHint(match);
        
        return originalPrompt + "\n\n" + locationHint;
    }
    
    /**
     * 构建地域语义提示词
     */
    private String buildLocationHint(LocationSemanticService.LocationMatchResult match) {
        StringBuilder hint = new StringBuilder();
        hint.append("⚠️ **地域查询特别注意事项**：\n\n");
        
        switch (match.getRuleType()) {
            case "user_registration":
                hint.append("📍 **用户维度查询**（按用户注册地统计）\n");
                hint.append("- 必须通过 JOIN users 表获取用户的 province/city 字段\n");
                hint.append("- 示例：SELECT u.city, COUNT(*) FROM orders o JOIN users u ON o.user_id = u.id WHERE u.city = '北京市' GROUP BY u.city\n");
                hint.append("- ❌ 错误：直接使用 user_addresses 表（这是地址簿，不是用户归属地）\n");
                break;
                
            case "delivery_address":
                hint.append("📦 **配送维度查询**（按订单配送地址统计）\n");
                hint.append("- 必须使用 orders.shipping_address 字段\n");
                hint.append("- 由于 shipping_address 是完整地址文本，需要使用 LIKE 模糊匹配\n");
                hint.append("- 示例：SELECT COUNT(*) FROM orders WHERE shipping_address LIKE '%深圳市%'\n");
                hint.append("- ⚠️ 注意：一个用户可能有多个收货地址，每次订单的 shipping_address 可能不同\n");
                break;
                
            case "address_book":
                hint.append("📋 **地址簿查询**（查询用户的收货地址列表）\n");
                hint.append("- 必须使用 user_addresses 表\n");
                hint.append("- ⚠️ **重要**：一个用户可能有多个地址，查询时必须明确是否需要过滤\n");
                hint.append("  - 如果查询\"默认地址\"：添加 WHERE is_default = 1\n");
                hint.append("  - 如果查询\"所有地址\"：不要加 is_default 过滤\n");
                hint.append("- 示例（默认地址）：SELECT ua.city, COUNT(DISTINCT ua.user_id) FROM user_addresses ua WHERE ua.is_default = 1 GROUP BY ua.city\n");
                break;
                
            default:
                hint.append("未知地域规则类型: ").append(match.getRuleType()).append("\n");
        }
        
        hint.append("\n请严格按照上述规则生成SQL。\n");
        
        return hint.toString();
    }
    
    @Override
    public String getMetricTemplate(String metricName, Long datasourceId) {
        // 电商指标计算模板
        return switch (metricName) {
            case "复购率" -> """
                SELECT 
                    COUNT(DISTINCT CASE WHEN order_count > 1 THEN user_id END) AS repurchase_users,
                    COUNT(DISTINCT user_id) AS total_users,
                    CASE 
                        WHEN COUNT(DISTINCT user_id) > 0 
                        THEN COUNT(DISTINCT CASE WHEN order_count > 1 THEN user_id END) * 100.0 / COUNT(DISTINCT user_id)
                        ELSE 0 
                    END AS repurchase_rate
                FROM (
                    SELECT user_id, COUNT(DISTINCT id) AS order_count
                    FROM orders
                    WHERE status != 'CANCELLED'
                    GROUP BY user_id
                ) user_orders
                """;
                
            case "客单价" -> """
                SELECT 
                    SUM(total_amount) / COUNT(DISTINCT id) AS avg_order_value
                FROM orders
                WHERE status != 'CANCELLED'
                  AND created_at >= DATE_SUB(CURDATE(), INTERVAL 30 DAY)
                """;
                
            case "GMV" -> """
                SELECT 
                    SUM(total_amount) AS gmv,
                    COUNT(*) AS order_count,
                    COUNT(DISTINCT user_id) AS buyer_count
                FROM orders
                WHERE status != 'CANCELLED'
                  AND created_at >= DATE_SUB(CURDATE(), INTERVAL 30 DAY)
                """;
                
            default -> null; // 无模板
        };
    }
}
