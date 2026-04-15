package com.nl2sql.core.llm.provider;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Ollama LLM提供者
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "llm.provider", havingValue = "ollama", matchIfMissing = true)
public class OllamaProvider implements LLMProvider {
    
    private final ChatModel chatModel;
    private final String baseUrl;
    private final String modelName;
    
    public OllamaProvider(
        @org.springframework.beans.factory.annotation.Value("${llm.ollama.base-url:http://localhost:11434}") String baseUrl,
        @org.springframework.beans.factory.annotation.Value("${llm.reasoning.model:qwen3:8b}") String modelName,
        @org.springframework.beans.factory.annotation.Value("${llm.reasoning.temperature:0.7}") double temperature,
        @org.springframework.beans.factory.annotation.Value("${llm.ollama.timeout:60}") int timeout
    ) {
        this.baseUrl = baseUrl;
        this.modelName = modelName;
        
        this.chatModel = OllamaChatModel.builder()
            .baseUrl(baseUrl)
            .modelName(modelName)
            .temperature(temperature)
            .timeout(java.time.Duration.ofSeconds(timeout))
            .build();
        
        log.info("[OllamaProvider] 初始化完成: model={}, url={}", modelName, baseUrl);
    }
    
    @Override
    public ChatModel getChatModel() {
        return chatModel;
    }
    
    @Override
    public boolean isAvailable() {
        try {
            // 简单健康检查：尝试调用模型
            chatModel.chat("test");
            return true;
        } catch (Exception e) {
            log.warn("[OllamaProvider] 服务不可用: {}", e.getMessage());
            return false;
        }
    }
    
    @Override
    public String getProviderName() {
        return "Ollama (" + modelName + ")";
    }
}
