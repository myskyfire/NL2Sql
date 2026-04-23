package com.nl2sql.core.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 业务规则管理器
 * 
 * 管理所有业务规则配置，支持动态加载和查询
 */
@Slf4j
@Component
public class BusinessRuleManager {
    
    /**
     * 规则缓存（ruleId -> BusinessRuleConfig）
     */
    private final Map<String, BusinessRuleConfig> ruleCache = new ConcurrentHashMap<>();
    
    /**
     * 按类型索引的规则（ruleType -> List<BusinessRuleConfig>）
     */
    private final Map<String, List<BusinessRuleConfig>> rulesByType = new ConcurrentHashMap<>();
    
    /**
     * 添加规则
     */
    public void addRule(BusinessRuleConfig rule) {
        if (rule == null || !rule.getEnabled()) {
            return;
        }
        
        ruleCache.put(rule.getRuleId(), rule);
        
        // 更新类型索引
        rulesByType.computeIfAbsent(rule.getRuleType(), k -> new ArrayList<>())
                   .add(rule);
        
        log.info("[BusinessRuleManager] 添加规则: {} (类型: {})", rule.getRuleId(), rule.getRuleType());
    }
    
    /**
     * 批量添加规则
     */
    public void addRules(List<BusinessRuleConfig> rules) {
        if (rules == null) {
            return;
        }
        
        rules.forEach(this::addRule);
        log.info("[BusinessRuleManager] 批量添加{}条规则", rules.size());
    }
    
    /**
     * 删除规则
     */
    public void removeRule(String ruleId) {
        BusinessRuleConfig rule = ruleCache.remove(ruleId);
        if (rule != null) {
            // 从类型索引中移除
            List<BusinessRuleConfig> typeRules = rulesByType.get(rule.getRuleType());
            if (typeRules != null) {
                typeRules.removeIf(r -> r.getRuleId().equals(ruleId));
            }
            log.info("[BusinessRuleManager] 删除规则: {}", ruleId);
        }
    }
    
    /**
     * 根据ID获取规则
     */
    public BusinessRuleConfig getRule(String ruleId) {
        return ruleCache.get(ruleId);
    }
    
    /**
     * 根据类型获取规则列表
     */
    public List<BusinessRuleConfig> getRulesByType(String ruleType) {
        return rulesByType.getOrDefault(ruleType, Collections.emptyList());
    }
    
    /**
     * 根据类型和数据源获取规则列表
     */
    public List<BusinessRuleConfig> getRulesByTypeAndDatasource(String ruleType, Long datasourceId) {
        List<BusinessRuleConfig> allRules = getRulesByType(ruleType);
        
        return allRules.stream()
            .filter(rule -> rule.getDatasourceId() == null || rule.getDatasourceId().equals(datasourceId))
            .sorted(Comparator.comparingInt(BusinessRuleConfig::getPriority).reversed())
            .collect(Collectors.toList());
    }
    
    /**
     * 查找表映射规则
     */
    public Optional<String> findTableMapping(String naturalName, Long datasourceId) {
        List<BusinessRuleConfig> rules = getRulesByTypeAndDatasource("table_mapping", datasourceId);
        
        return rules.stream()
            .filter(rule -> {
                String mappedNaturalName = (String) rule.getRuleContent().get("naturalName");
                return naturalName.equalsIgnoreCase(mappedNaturalName);
            })
            .map(rule -> (String) rule.getRuleContent().get("tableName"))
            .findFirst();
    }
    
    /**
     * 查找时间解析规则
     */
    public Optional<String> findTimeParsing(String pattern, Long datasourceId) {
        List<BusinessRuleConfig> rules = getRulesByTypeAndDatasource("time_parsing", datasourceId);
        
        return rules.stream()
            .filter(rule -> {
                String rulePattern = (String) rule.getRuleContent().get("pattern");
                return pattern.equalsIgnoreCase(rulePattern);
            })
            .map(rule -> (String) rule.getRuleContent().get("sqlFormat"))
            .findFirst();
    }
    
    /**
     * 查找同义词规则
     */
    public Optional<String> findSynonym(String word, Long datasourceId) {
        List<BusinessRuleConfig> rules = getRulesByTypeAndDatasource("synonym", datasourceId);
        
        return rules.stream()
            .filter(rule -> {
                String ruleWord = (String) rule.getRuleContent().get("word");
                return word.equalsIgnoreCase(ruleWord);
            })
            .map(rule -> (String) rule.getRuleContent().get("standardWord"))
            .findFirst();
    }
    
    /**
     * 查找字段映射规则
     */
    public Optional<String> findFieldMapping(String tableName, String naturalName, Long datasourceId) {
        List<BusinessRuleConfig> rules = getRulesByTypeAndDatasource("field_mapping", datasourceId);
        
        return rules.stream()
            .filter(rule -> {
                String ruleTable = (String) rule.getRuleContent().get("tableName");
                String ruleNaturalName = (String) rule.getRuleContent().get("naturalName");
                return tableName.equalsIgnoreCase(ruleTable) && naturalName.equalsIgnoreCase(ruleNaturalName);
            })
            .map(rule -> (String) rule.getRuleContent().get("fieldName"))
            .findFirst();
    }
    
    /**
     * 清空所有规则
     */
    public void clearAll() {
        ruleCache.clear();
        rulesByType.clear();
        log.info("[BusinessRuleManager] 已清空所有规则");
    }
    
    /**
     * 获取规则数量
     */
    public int getRuleCount() {
        return ruleCache.size();
    }
    
    /**
     * 获取所有规则类型
     */
    public Set<String> getAllRuleTypes() {
        return rulesByType.keySet();
    }
}
