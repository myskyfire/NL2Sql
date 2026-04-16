package com.nl2sql.core.llm.config;

import com.nl2sql.core.llm.provider.LLMProvider;
import com.nl2sql.core.llm.provider.OpenAICompatibleProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAI兼容API提供者配置
 */
@Configuration
@ConditionalOnProperty(name = "llm.provider", havingValue = "openai-compatible")
public class OpenAICompatibleConfig {
    
    @Bean("reasoningProvider")
    public LLMProvider reasoningProvider(
        @Value("${llm.openai-compatible.base-url}") String baseUrl,
        @Value("${llm.openai-compatible.api-key:}") String apiKey,
        @Value("${llm.reasoning.model:qwen-plus}") String modelName,
        @Value("${llm.openai-compatible.timeout:30}") int timeout
    ) {
        return new OpenAICompatibleProvider("openai-compatible", baseUrl, modelName, apiKey, timeout);
    }
    
    @Bean("codeProvider")
    public LLMProvider codeProvider(
        @Value("${llm.openai-compatible.base-url}") String baseUrl,
        @Value("${llm.openai-compatible.api-key:}") String apiKey,
        @Value("${llm.code.model:qwen-coder}") String modelName,
        @Value("${llm.openai-compatible.timeout:30}") int timeout
    ) {
        return new OpenAICompatibleProvider("openai-compatible-code", baseUrl, modelName, apiKey, timeout);
    }
}
