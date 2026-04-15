package com.nl2sql.core.llm.provider;

import dev.langchain4j.model.chat.ChatModel;

/**
 * LLM提供者接口 - 支持多种LLM后端
 */
public interface LLMProvider {
    
    /**
     * 获取ChatModel实例
     */
    ChatModel getChatModel();
    
    /**
     * 检查服务是否可用
     */
    boolean isAvailable();
    
    /**
     * 提供者名称（用于日志）
     */
    String getProviderName();
}
