package com.nl2sql.core.llm;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * ✅ 地域语义解析服务
 * 
 * 根据用户问题中的关键词，识别地域查询意图并返回对应的SQL模板
 */
@Slf4j
@Service
public class LocationSemanticService {
    
    @Autowired
    private IndustryConceptDictionary industryConceptDictionary;
    
    /**
     * 解析地域查询语义
     * 
     * @param question 用户问题
     * @param datasourceId 数据源ID
     * @return 匹配的地域规则，未匹配返回null
     */
    public LocationMatchResult resolve(String question, Long datasourceId) {
        if (question == null || question.isEmpty()) {
            return null;
        }
        
        // 获取当前行业的配置
        IndustryConceptDictionary.IndustryConcepts concepts = 
            industryConceptDictionary.getConceptsByDatasource(datasourceId);
        
        if (concepts == null) {
            log.debug("[LocationSemantic] 未找到行业配置，datasourceId={}", datasourceId);
            return null;
        }
        
        IndustryConceptDictionary.LocationSemantics semantics = concepts.getLocationSemantics();
        if (semantics == null) {
            log.debug("[LocationSemantic] 行业{}未配置地域语义规则", concepts.getIndustryCode());
            return null;
        }
        
        // 依次匹配三种规则
        LocationMatchResult result = matchRule(question, "user_registration", semantics.getUserRegistration());
        if (result != null) return result;
        
        result = matchRule(question, "delivery_address", semantics.getDeliveryAddress());
        if (result != null) return result;
        
        result = matchRule(question, "address_book", semantics.getAddressBook());
        if (result != null) return result;
        
        return null;
    }
    
    /**
     * 匹配单个规则
     */
    private LocationMatchResult matchRule(String question, String ruleType, 
                                         IndustryConceptDictionary.LocationRule rule) {
        if (rule == null || rule.getKeywords() == null || rule.getKeywords().isEmpty()) {
            return null;
        }
        
        // 检查是否包含任一关键词
        for (String keyword : rule.getKeywords()) {
            if (question.contains(keyword)) {
                LocationMatchResult result = new LocationMatchResult();
                result.setRuleType(ruleType);
                result.setTargetTable(rule.getTargetTable());
                result.setTargetFields(rule.getTargetFields());
                result.setSqlTemplate(rule.getSqlTemplate());
                result.setDefaultFilter(rule.getDefaultFilter());
                
                log.debug("[LocationSemantic] 匹配到{}规则: keyword={}, table={}", 
                    ruleType, keyword, rule.getTargetTable());
                
                return result;
            }
        }
        
        return null;
    }
    
    /**
     * ✅ 地域匹配结果
     */
    @Data
    public static class LocationMatchResult {
        // 规则类型：user_registration / delivery_address / address_book
        private String ruleType;
        
        // 目标表名
        private String targetTable;
        
        // 目标字段列表
        private List<String> targetFields;
        
        // SQL模板
        private String sqlTemplate;
        
        // 默认过滤条件
        private String defaultFilter;
    }
}
