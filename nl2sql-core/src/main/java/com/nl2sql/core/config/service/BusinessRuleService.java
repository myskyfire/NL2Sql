package com.nl2sql.core.config.service;

import com.nl2sql.core.config.BusinessRuleConfig;
import com.nl2sql.core.config.BusinessRuleManager;
import com.nl2sql.core.config.mapper.BusinessRuleMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 业务规则服务
 * 
 * 支持规则的CRUD和热加载
 */
@Slf4j
@Service
public class BusinessRuleService {
    
    @Autowired
    private BusinessRuleMapper businessRuleMapper;
    
    @Autowired
    private BusinessRuleManager ruleManager;
    
    /**
     * 初始化：从数据库加载所有启用的规则
     */
    public void initialize() {
        log.info("[BusinessRuleService] 开始初始化业务规则...");
        
        List<BusinessRuleConfig> rules = businessRuleMapper.findAllEnabled();
        if (rules != null && !rules.isEmpty()) {
            // 解析JSON内容
            rules.forEach(rule -> {
                if (rule.getRuleContent() == null) {
                    rule.setRuleContentFromJson(rule.getRuleContentJson());
                }
            });
            
            ruleManager.addRules(rules);
            log.info("[BusinessRuleService] 已加载 {} 条业务规则", rules.size());
        } else {
            log.info("[BusinessRuleService] 未找到业务规则");
        }
    }
    
    /**
     * 添加规则
     */
    public void addRule(BusinessRuleConfig rule, String operator) {
        rule.setCreatedBy(operator);
        rule.setUpdatedBy(operator);
        
        // 保存到数据库
        businessRuleMapper.insert(rule);
        
        // 加载到内存
        ruleManager.addRule(rule);
        
        log.info("[BusinessRuleService] 添加规则: {} by {}", rule.getRuleId(), operator);
    }
    
    /**
     * 更新规则
     */
    public void updateRule(BusinessRuleConfig rule, String operator) {
        rule.setUpdatedBy(operator);
        
        // 更新数据库
        businessRuleMapper.update(rule);
        
        // 更新内存（先删除再添加）
        ruleManager.removeRule(rule.getRuleId());
        ruleManager.addRule(rule);
        
        log.info("[BusinessRuleService] 更新规则: {} by {}", rule.getRuleId(), operator);
    }
    
    /**
     * 删除规则
     */
    public void deleteRule(String ruleId, String operator) {
        // 从数据库删除
        businessRuleMapper.delete(ruleId);
        
        // 从内存删除
        ruleManager.removeRule(ruleId);
        
        log.info("[BusinessRuleService] 删除规则: {} by {}", ruleId, operator);
    }
    
    /**
     * 启用/禁用规则
     */
    public void toggleRule(String ruleId, boolean enabled, String operator) {
        BusinessRuleConfig rule = businessRuleMapper.findByRuleId(ruleId);
        if (rule != null) {
            rule.setEnabled(enabled);
            rule.setUpdatedBy(operator);
            businessRuleMapper.update(rule);
            
            if (enabled) {
                ruleManager.addRule(rule);
            } else {
                ruleManager.removeRule(ruleId);
            }
            
            log.info("[BusinessRuleService] {}规则: {} by {}", enabled ? "启用" : "禁用", ruleId, operator);
        }
    }
    
    /**
     * 定时刷新：每5分钟从数据库重新加载规则（热加载）
     */
    @Scheduled(fixedRate = 300000) // 5分钟
    public void refreshRules() {
        log.debug("[BusinessRuleService] 开始定时刷新业务规则...");
        
        try {
            // 清空现有规则
            ruleManager.clearAll();
            
            // 重新加载
            List<BusinessRuleConfig> rules = businessRuleMapper.findAllEnabled();
            if (rules != null && !rules.isEmpty()) {
                rules.forEach(rule -> {
                    if (rule.getRuleContent() == null) {
                        rule.setRuleContentFromJson(rule.getRuleContentJson());
                    }
                });
                
                ruleManager.addRules(rules);
                log.info("[BusinessRuleService] 定时刷新完成，已加载 {} 条规则", rules.size());
            }
        } catch (Exception e) {
            log.error("[BusinessRuleService] 定时刷新失败", e);
        }
    }
    
    /**
     * 手动触发刷新
     */
    public void manualRefresh() {
        log.info("[BusinessRuleService] 手动触发规则刷新...");
        refreshRules();
    }
}
