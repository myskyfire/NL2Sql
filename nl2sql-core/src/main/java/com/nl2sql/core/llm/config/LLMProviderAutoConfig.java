package com.nl2sql.core.llm.config;

import com.nl2sql.core.llm.provider.LLMProviderManager;
import com.nl2sql.core.llm.provider.OllamaProvider;
import com.nl2sql.core.llm.provider.OpenAICompatibleProvider;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.annotation.PostConstruct;
import java.util.List;

/**
 * LLM提供者自动配置
 * 根据配置文件动态注册和启用LLM提供者
 */
@Slf4j
@Configuration
@ConfigurationProperties(prefix = "llm")
@Data
public class LLMProviderAutoConfig {
    
    /**
     * 活跃的提供者名称（如：ollama, chatglm, qwen）
     */
    private String activeProvider = "ollama";
    
    /**
     * 提供者优先级列表（用于自动选择）
     */
    private List<String> providerPriority;
    
    /**
     * Ollama配置
     */
    private OllamaConfig ollama = new OllamaConfig();
    
    /**
     * ChatGLM配置（预留）
     */
    private ChatGLMConfig chatglm = new ChatGLMConfig();
    
    /**
     * Qwen配置（预留）
     */
    private QwenConfig qwen = new QwenConfig();
    
    @Data
    public static class OllamaConfig {
        private boolean enabled = true;
        private String baseUrl = "http://localhost:11434";
        private String codeModel = "qwen2.5-coder:7b-instruct-q4_0";
        private String nlpModel = "qwen3:8b";
        private int timeout = 60;
    }
    
    @Data
    public static class ChatGLMConfig {
        private boolean enabled = false;
        private String baseUrl = "http://localhost:8000";
        private String model = "chatglm3-6b";
        private int timeout = 60;
    }
    
    @Data
    public static class QwenConfig {
        private boolean enabled = false;
        private String baseUrl = "http://localhost:8000";
        private String model = "qwen-7b-chat";
        private String apiKey = "";
        private int timeout = 60;
    }
    
    @PostConstruct
    public void validate() {
        log.info("[LLM配置] 活跃提供者: {}", activeProvider);
        log.info("[LLM配置] Ollama启用: {}, URL: {}", ollama.isEnabled(), ollama.getBaseUrl());
    }
    
    /**
     * 注册Ollama提供者
     */
    @Bean
    @ConditionalOnProperty(name = "llm.ollama.enabled", havingValue = "true", matchIfMissing = true)
    public OllamaProvider ollamaProvider() {
        log.info("[LLM配置] 注册Ollama提供者: model={}", ollama.getCodeModel());
        return new OllamaProvider(
            ollama.getBaseUrl(),
            ollama.getCodeModel(),
            ollama.getTimeout()
        );
    }
    
    /**
     * 注册ChatGLM提供者（预留）
     */
    @Bean
    @ConditionalOnProperty(name = "llm.chatglm.enabled", havingValue = "true")
    public OpenAICompatibleProvider chatglmProvider() {
        log.info("[LLM配置] 注册ChatGLM提供者: model={}", chatglm.getModel());
        return new OpenAICompatibleProvider(
            "chatglm",
            chatglm.getBaseUrl(),
            chatglm.getModel(),
            "",  // ChatGLM通常不需要API Key
            chatglm.getTimeout()
        );
    }
    
    /**
     * 注册Qwen提供者（预留）
     */
    @Bean
    @ConditionalOnProperty(name = "llm.qwen.enabled", havingValue = "true")
    public OpenAICompatibleProvider qwenProvider() {
        log.info("[LLM配置] 注册Qwen提供者: model={}", qwen.getModel());
        return new OpenAICompatibleProvider(
            "qwen",
            qwen.getBaseUrl(),
            qwen.getModel(),
            qwen.getApiKey(),
            qwen.getTimeout()
        );
    }
    
    /**
     * 初始化LLM提供者管理器
     */
    @Bean
    public LLMProviderManager llmProviderManager(List<com.nl2sql.core.llm.provider.LLMProvider> providers) {
        LLMProviderManager manager = new LLMProviderManager();
        
        // 注册所有可用的提供者
        for (com.nl2sql.core.llm.provider.LLMProvider provider : providers) {
            manager.registerProvider(provider);
        }
        
        // 设置活跃提供者
        try {
            if (providerPriority != null && !providerPriority.isEmpty()) {
                // 按优先级自动选择
                manager.autoSelectProvider(providerPriority);
            } else {
                // 使用配置的活跃提供者
                manager.setActiveProvider(activeProvider);
            }
        } catch (Exception e) {
            log.error("[LLM配置] 设置活跃提供者失败: {}", e.getMessage());
            throw e;
        }
        
        return manager;
    }
}
