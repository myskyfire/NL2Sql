package com.nl2sql.core.llm.provider;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LLM提供者管理器
 * 负责注册、管理和路由到不同的LLM提供者
 */
@Slf4j
public class LLMProviderManager {
    
    private final Map<String, LLMProvider> providers = new ConcurrentHashMap<>();
    private LLMProvider activeProvider;
    private String activeProviderName;
    
    @PostConstruct
    public void init() {
        log.info("[LLMProviderManager] 初始化完成");
    }
    
    /**
     * 注册LLM提供者
     */
    public void registerProvider(LLMProvider provider) {
        providers.put(provider.getName(), provider);
        log.info("[LLMProviderManager] 注册提供者: {}", provider.getName());
    }
    
    /**
     * 设置活跃的提供者
     */
    public void setActiveProvider(String providerName) {
        LLMProvider provider = providers.get(providerName);
        if (provider == null) {
            throw new IllegalArgumentException("未知的LLM提供者: " + providerName);
        }
        
        if (!provider.isAvailable()) {
            log.warn("[LLMProviderManager] 提供者 {} 不可用", providerName);
        }
        
        this.activeProvider = provider;
        this.activeProviderName = providerName;
        log.info("[LLMProviderManager] 切换活跃提供者: {}", providerName);
    }
    
    /**
     * 获取活跃的提供者
     */
    public LLMProvider getActiveProvider() {
        if (activeProvider == null) {
            throw new IllegalStateException("未配置活跃的LLM提供者，请检查配置");
        }
        return activeProvider;
    }
    
    /**
     * 获取指定名称的提供者
     */
    public LLMProvider getProvider(String name) {
        LLMProvider provider = providers.get(name);
        if (provider == null) {
            throw new IllegalArgumentException("未知的LLM提供者: " + name);
        }
        return provider;
    }
    
    /**
     * 获取所有已注册的提供者
     */
    public Set<String> getRegisteredProviders() {
        return providers.keySet();
    }
    
    /**
     * 获取活跃提供者名称
     */
    public String getActiveProviderName() {
        return activeProviderName;
    }
    
    /**
     * 健康检查所有提供者
     */
    public Map<String, Boolean> healthCheck() {
        Map<String, Boolean> status = new HashMap<>();
        for (Map.Entry<String, LLMProvider> entry : providers.entrySet()) {
            status.put(entry.getKey(), entry.getValue().isAvailable());
        }
        return status;
    }
    
    /**
     * 自动选择可用的提供者（按优先级）
     */
    public void autoSelectProvider(List<String> priorityList) {
        for (String providerName : priorityList) {
            LLMProvider provider = providers.get(providerName);
            if (provider != null && provider.isAvailable()) {
                setActiveProvider(providerName);
                log.info("[LLMProviderManager] 自动选择提供者: {}", providerName);
                return;
            }
        }
        
        throw new IllegalStateException("没有可用的LLM提供者，请检查配置");
    }
}
