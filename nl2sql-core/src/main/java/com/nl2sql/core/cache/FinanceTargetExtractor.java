package com.nl2sql.core.cache;

import lombok.extern.slf4j.Slf4j;

/**
 * 财务行业目标提取器
 * 从查询中提取财务特定目标关键词（收入、成本、利润等）
 */
@Slf4j
public class FinanceTargetExtractor implements IndustryTargetExtractor {
    
    // 财务核心业务对象
    private static final String[] REVENUE_KEYWORDS = {"收入", "营收", "销售额", "营业额"};
    private static final String[] COST_KEYWORDS = {"成本", "支出", "费用", "开支"};
    private static final String[] PROFIT_KEYWORDS = {"利润", "毛利", "净利", "盈利", "收益"};
    private static final String[] ASSET_KEYWORDS = {"资产", "负债", "权益", "余额"};
    private static final String[] ACCOUNT_KEYWORDS = {"科目", "账户", "账目", "会计"};
    
    @Override
    public String extract(String query) {
        if (query == null || query.trim().isEmpty()) {
            return null;
        }
        
        // ✅ 优先级：利润 > 收入 > 成本 > 资产 > 科目
        // 原因：利润是最终经营结果，优先级最高
        if (containsAny(query, PROFIT_KEYWORDS)) {
            return "利润";
        }
        if (containsAny(query, REVENUE_KEYWORDS)) {
            return "收入";
        }
        if (containsAny(query, COST_KEYWORDS)) {
            return "成本";
        }
        if (containsAny(query, ASSET_KEYWORDS)) {
            return "资产";
        }
        if (containsAny(query, ACCOUNT_KEYWORDS)) {
            return "科目";
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
