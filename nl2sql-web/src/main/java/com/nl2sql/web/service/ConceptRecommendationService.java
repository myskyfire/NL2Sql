package com.nl2sql.web.service;

import org.springframework.stereotype.Service;

/**
 * 行业概念智能推荐服务
 * 
 * 职责：根据术语和行业代码智能推荐概念
 */
@Service
public class ConceptRecommendationService {
    
    /**
     * 根据业务类别匹配行业代码
     */
    public String matchIndustryCode(String businessCategory) {
        if (businessCategory == null) {
            return null;
        }
        
        String category = businessCategory.toLowerCase();
        if (category.contains("order") || category.contains("交易") || category.contains("订单")) {
            return "ecommerce";
        } else if (category.contains("finance") || category.contains("财务")) {
            return "finance";
        } else if (category.contains("medical") || category.contains("医疗")) {
            return "medical";
        } else if (category.contains("education") || category.contains("教育")) {
            return "education";
        } else if (category.contains("manufacturing") || category.contains("制造")) {
            return "manufacturing";
        }
        return null;
    }
    
    /**
     * 根据术语智能推荐概念
     */
    public String suggestConceptByTerm(String term, String industryCode) {
        if (term == null || industryCode == null) {
            return null;
        }
        
        String lowerTerm = term.toLowerCase();
        
        // 电商行业常见术语映射
        if ("ecommerce".equals(industryCode)) {
            if (lowerTerm.contains("gmv") || lowerTerm.contains("成交") || lowerTerm.contains("营业额")) {
                return "revenue";
            } else if (lowerTerm.contains("uv") || lowerTerm.contains("访客")) {
                return "customer";
            } else if (lowerTerm.contains("pv") || lowerTerm.contains("浏览")) {
                return "product";
            }
        }
        
        // 金融行业
        if ("finance".equals(industryCode)) {
            if (lowerTerm.contains("aum") || lowerTerm.contains("资产规模")) {
                return "balance";
            }
        }
        
        // 无法匹配，返回null
        return null;
    }
}
