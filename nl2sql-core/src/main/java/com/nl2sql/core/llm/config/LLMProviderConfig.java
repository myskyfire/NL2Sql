package com.nl2sql.core.llm.config;

import com.nl2sql.core.llm.provider.LLMProvider;
import com.nl2sql.core.llm.provider.OllamaProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * LLM提供者配置 - 支持双模型架构
 */
@Configuration
@ConditionalOnProperty(name = "llm.provider", havingValue = "ollama", matchIfMissing = true)
public class LLMProviderConfig {
    
    /**
     * 推理模型提供者
     */
    @Bean("reasoningProvider")
    public LLMProvider reasoningProvider(
        @Value("${llm.ollama.base-url:http://localhost:11434}") String baseUrl,
        @Value("${llm.reasoning.model:qwen3:8b}") String modelName,
        @Value("${llm.reasoning.temperature:0.7}") double temperature,
        @Value("${llm.ollama.timeout:60}") int timeout
    ) {
        return new OllamaProvider(baseUrl, modelName, temperature, timeout);
    }
    
    /**
     * 代码模型提供者（可与推理模型相同）
     */
    @Bean("codeProvider")
    public LLMProvider codeProvider(
        @Value("${llm.ollama.base-url:http://localhost:11434}") String baseUrl,
        @Value("${llm.code.model:qwen2.5-coder:7b}") String modelName,
        @Value("${llm.code.temperature:0.0}") double temperature,
        @Value("${llm.ollama.timeout:60}") int timeout
    ) {
        return new OllamaProvider(baseUrl, modelName, temperature, timeout);
    }
}
