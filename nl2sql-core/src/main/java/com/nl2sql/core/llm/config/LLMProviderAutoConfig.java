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
    
    /**
     * vLLM配置（生产环境 - 高性能GPU）
     */
    private VllmConfig vllm = new VllmConfig();
    
    /**
     * TGI配置（生产环境 - HuggingFace官方）
     */
    private TgiConfig tgi = new TgiConfig();
    
    /**
     * TensorRT-LLM配置（生产环境 - NVIDIA优化）
     */
    private TensorRTConfig tensorrt = new TensorRTConfig();
    
    /**
     * llama.cpp配置（生产环境 - CPU/GPU混合）
     */
    private LlamaCppConfig llamacpp = new LlamaCppConfig();
    
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
    
    @Data
    public static class VllmConfig {
        private boolean enabled = false;
        private String baseUrl = "http://localhost:8000/v1";
        private String model = "Qwen/Qwen3-8B";
        private String apiKey = "${VLLM_API_KEY:}";
        private int timeout = 60;
    }
    
    @Data
    public static class TgiConfig {
        private boolean enabled = false;
        private String baseUrl = "http://localhost:8080/v1";
        private String model = "Qwen/Qwen3-8B";
        private String apiKey = "";
        private int timeout = 60;
    }
    
    @Data
    public static class TensorRTConfig {
        private boolean enabled = false;
        private String baseUrl = "http://localhost:8000/v1";
        private String model = "qwen3-8b-trt";
        private String apiKey = "${TRITON_API_KEY:}";
        private int timeout = 60;
    }
    
    @Data
    public static class LlamaCppConfig {
        private boolean enabled = false;
        private String baseUrl = "http://localhost:8080/v1";
        private String model = "/models/qwen3-8b-q4_k_m.gguf";
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
     * 注册vLLM提供者（生产环境 - 高性能GPU）
     */
    @Bean
    @ConditionalOnProperty(name = "llm.vllm.enabled", havingValue = "true")
    public OpenAICompatibleProvider vllmProvider() {
        log.info("[LLM配置] 注册vLLM提供者: model={}, url={}", vllm.getModel(), vllm.getBaseUrl());
        return new OpenAICompatibleProvider(
            "vllm",
            vllm.getBaseUrl(),
            vllm.getModel(),
            resolveApiKey(vllm.getApiKey()),
            vllm.getTimeout()
        );
    }
    
    /**
     * 注册TGI提供者（生产环境 - HuggingFace官方）
     */
    @Bean
    @ConditionalOnProperty(name = "llm.tgi.enabled", havingValue = "true")
    public OpenAICompatibleProvider tgiProvider() {
        log.info("[LLM配置] 注册TGI提供者: model={}, url={}", tgi.getModel(), tgi.getBaseUrl());
        return new OpenAICompatibleProvider(
            "tgi",
            tgi.getBaseUrl(),
            tgi.getModel(),
            tgi.getApiKey(),
            tgi.getTimeout()
        );
    }
    
    /**
     * 注册TensorRT-LLM提供者（生产环境 - NVIDIA优化）
     */
    @Bean
    @ConditionalOnProperty(name = "llm.tensorrt.enabled", havingValue = "true")
    public OpenAICompatibleProvider tensorRTProvider() {
        log.info("[LLM配置] 注册TensorRT-LLM提供者: model={}, url={}", tensorrt.getModel(), tensorrt.getBaseUrl());
        return new OpenAICompatibleProvider(
            "tensorrt",
            tensorrt.getBaseUrl(),
            tensorrt.getModel(),
            resolveApiKey(tensorrt.getApiKey()),
            tensorrt.getTimeout()
        );
    }
    
    /**
     * 注册llama.cpp提供者（生产环境 - CPU/GPU混合）
     */
    @Bean
    @ConditionalOnProperty(name = "llm.llamacpp.enabled", havingValue = "true")
    public OpenAICompatibleProvider llamaCppProvider() {
        log.info("[LLM配置] 注册llama.cpp提供者: model={}, url={}", llamacpp.getModel(), llamacpp.getBaseUrl());
        return new OpenAICompatibleProvider(
            "llamacpp",
            llamacpp.getBaseUrl(),
            llamacpp.getModel(),
            llamacpp.getApiKey(),
            llamacpp.getTimeout()
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
    
    /**
     * 解析API Key（支持环境变量）
     */
    private String resolveApiKey(String apiKey) {
        if (apiKey != null && apiKey.startsWith("${") && apiKey.endsWith("}")) {
            String envVar = apiKey.substring(2, apiKey.length() - 1).split(":")[0];
            String value = System.getenv(envVar);
            if (value != null && !value.isEmpty()) {
                log.debug("[LLM配置] 从环境变量读取 API Key: {}", envVar);
                return value;
            }
            log.warn("[LLM配置] 环境变量 {} 未设置，使用空值", envVar);
        }
        return apiKey;
    }
}
