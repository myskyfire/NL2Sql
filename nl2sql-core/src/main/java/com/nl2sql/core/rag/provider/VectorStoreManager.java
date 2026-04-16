package com.nl2sql.core.rag.provider;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 向量数据库提供者管理器
 * 负责注册、管理多个向量数据库提供者，实现优先级选择和故障转移
 */
@Slf4j
@Component
public class VectorStoreManager {
    
    private final List<VectorStoreProvider> providers = new CopyOnWriteArrayList<>();
    private String activeProviderName;
    
    /**
     * 注册向量数据库提供者
     */
    public void registerProvider(VectorStoreProvider provider) {
        if (provider == null) {
            return;
        }
        
        providers.add(provider);
        log.info("注册向量数据库提供者: {}", provider.getName());
        
        // 如果这是第一个可用的提供者，设为活跃提供者
        if (activeProviderName == null && provider.isAvailable()) {
            activeProviderName = provider.getName();
            log.info("设置活跃向量数据库: {}", activeProviderName);
        }
    }
    
    /**
     * 获取所有已注册的提供者
     */
    public List<VectorStoreProvider> getAllProviders() {
        return new ArrayList<>(providers);
    }
    
    /**
     * 获取指定名称的提供者
     */
    public VectorStoreProvider getProvider(String name) {
        return providers.stream()
            .filter(p -> p.getName().equals(name))
            .findFirst()
            .orElse(null);
    }
    
    /**
     * 获取活跃的提供者
     */
    public VectorStoreProvider getActiveProvider() {
        if (activeProviderName == null) {
            return null;
        }
        return getProvider(activeProviderName);
    }
    
    /**
     * 切换活跃的向量数据库提供者
     */
    public boolean setActiveProvider(String providerName) {
        VectorStoreProvider provider = getProvider(providerName);
        if (provider == null) {
            log.warn("向量数据库提供者不存在: {}", providerName);
            return false;
        }
        
        if (!provider.isAvailable()) {
            log.warn("向量数据库提供者不可用: {}", providerName);
            return false;
        }
        
        this.activeProviderName = providerName;
        log.info("切换活跃向量数据库: {}", providerName);
        return true;
    }
    
    /**
     * 自动选择最优的可用提供者（按优先级）
     * 优先级顺序: chroma > milvus > qdrant > mysql
     */
    public void autoSelectProvider() {
        List<String> priority = List.of("chroma", "milvus", "qdrant", "mysql");
        
        for (String providerName : priority) {
            VectorStoreProvider provider = getProvider(providerName);
            if (provider != null && provider.isAvailable()) {
                this.activeProviderName = providerName;
                log.info("自动选择向量数据库: {}", providerName);
                return;
            }
        }
        
        log.warn("没有可用的向量数据库提供者");
    }
    
    /**
     * 健康检查所有提供者
     */
    public Map<String, Boolean> healthCheck() {
        Map<String, Boolean> status = new HashMap<>();
        for (VectorStoreProvider provider : providers) {
            status.put(provider.getName(), provider.isAvailable());
        }
        return status;
    }
    
    /**
     * 获取所有提供者的配置信息
     */
    public Map<String, Object> getAllConfig() {
        Map<String, Object> allConfig = new HashMap<>();
        allConfig.put("activeProvider", activeProviderName);
        
        Map<String, Object> providersConfig = new HashMap<>();
        for (VectorStoreProvider provider : providers) {
            providersConfig.put(provider.getName(), provider.getConfig());
        }
        allConfig.put("providers", providersConfig);
        
        return allConfig;
    }
}
