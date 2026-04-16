package com.nl2sql.core.llm.provider;

import java.util.Map;

/**
 * LLM提供者统一接口 - 支持多种企业内部部署的LLM后端
 * 
 * 借鉴LangChain的设计思路，为不同的LLM提供统一的适配层
 * 支持：Ollama、ChatGLM、Qwen、Baichuan等企业级私有化部署模型
 */
public interface LLMProvider {
    
    /**
     * 获取提供者名称（唯一标识）
     * @return 如: "ollama", "chatglm", "qwen"
     */
    String getName();
    
    /**
     * 检查服务是否可用（健康检查）
     * @return true=可用, false=不可用
     */
    boolean isAvailable();
    
    /**
     * 生成文本响应
     * @param prompt 提示词
     * @param temperature 温度参数（0.0-1.0）
     * @return LLM生成的文本
     */
    String generate(String prompt, double temperature);
    
    /**
     * 生成JSON格式的响应
     * @param systemPrompt 系统提示词
     * @param userPrompt 用户提示词
     * @param temperature 温度参数
     * @return JSON字符串
     */
    String generateJson(String systemPrompt, String userPrompt, double temperature);
    
    /**
     * 流式生成（可选实现）
     * @param prompt 提示词
     * @param callback 流式回调
     */
    default void generateStream(String prompt, StreamCallback callback) {
        // 默认实现：非流式调用
        String response = generate(prompt, 0.7);
        callback.onComplete(response);
    }
    
    /**
     * 获取配置信息（用于调试和监控）
     * @return 配置Map
     */
    Map<String, Object> getConfig();
    
    /**
     * 流式生成回调接口
     */
    @FunctionalInterface
    interface StreamCallback {
        void onToken(String token);
        
        default void onComplete(String fullResponse) {
            // 默认空实现
        }
        
        default void onError(Throwable error) {
            throw new RuntimeException(error);
        }
    }
}
