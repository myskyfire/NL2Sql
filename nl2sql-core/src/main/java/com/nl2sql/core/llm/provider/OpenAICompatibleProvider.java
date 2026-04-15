package com.nl2sql.core.llm.provider;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * OpenAI兼容API提供者（支持企业内部部署的兼容OpenAI API的LLM）
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "llm.provider", havingValue = "openai-compatible")
public class OpenAICompatibleProvider implements LLMProvider {
    
    private final ChatModel chatModel;
    private final String baseUrl;
    private final String modelName;
    
    public OpenAICompatibleProvider(
        @org.springframework.beans.factory.annotation.Value("${llm.openai-compatible.base-url}") String baseUrl,
        @org.springframework.beans.factory.annotation.Value("${llm.openai-compatible.api-key:}") String apiKey,
        @org.springframework.beans.factory.annotation.Value("${llm.reasoning.model:qwen-plus}") String modelName,
        @org.springframework.beans.factory.annotation.Value("${llm.reasoning.temperature:0.7}") double temperature,
        @org.springframework.beans.factory.annotation.Value("${llm.openai-compatible.timeout:30}") int timeout
    ) {
        this.baseUrl = baseUrl;
        this.modelName = modelName;
        
        var builder = OpenAiChatModel.builder()
            .baseUrl(baseUrl)
            .modelName(modelName)
            .temperature(temperature)
            .timeout(java.time.Duration.ofSeconds(timeout));
        
        if (!apiKey.isEmpty()) {
            builder.apiKey(apiKey);
        }
        
        this.chatModel = builder.build();
        
        log.info("[OpenAICompatibleProvider] 初始化完成: model={}, url={}", modelName, baseUrl);
    }
    
    @Override
    public ChatModel getChatModel() {
        return chatModel;
    }
    
    @Override
    public boolean isAvailable() {
        try {
            chatModel.chat("test");
            return true;
        } catch (Exception e) {
            log.warn("[OpenAICompatibleProvider] 服务不可用: {}", e.getMessage());
            return false;
        }
    }
    
    @Override
    public String getProviderName() {
        return "OpenAI-Compatible (" + modelName + ")";
    }
}
