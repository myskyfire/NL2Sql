package com.nl2sql.core.llm;

import com.nl2sql.core.llm.provider.LLMProvider;
import com.nl2sql.core.llm.provider.LLMProviderManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;

/**
 * LLM服务 - 统一的LLM调用接口
 * 
 * 通过LLMProviderManager动态选择活跃的LLM提供者
 * 支持运行时切换不同的LLM后端
 */
@Slf4j
@Service
public class LLMService {
    
    @Autowired
    private LLMProviderManager providerManager;
    
    private LLMProvider activeProvider;
    
    @PostConstruct
    public void init() {
        this.activeProvider = providerManager.getActiveProvider();
        log.info("[LLMService] 初始化完成，活跃提供者: {}", activeProvider.getName());
        
        // 健康检查
        checkHealth();
    }
    
    /**
     * 健康检查所有提供者
     */
    private void checkHealth() {
        var healthStatus = providerManager.healthCheck();
        
        boolean allHealthy = true;
        for (var entry : healthStatus.entrySet()) {
            String status = entry.getValue() ? "✅" : "❌";
            log.info("[LLMService] 提供者 {} 状态: {}", entry.getKey(), status);
            
            if (!entry.getValue()) {
                allHealthy = false;
            }
        }
        
        if (!allHealthy) {
            log.warn("[LLMService] 部分LLM提供者不可用，请检查配置");
        } else {
            log.info("[LLMService] 所有LLM提供者健康检查通过 ✅");
        }
    }
    
    /**
     * 生成SQL查询
     */
    public String generateSQL(String prompt) {
        try {
            log.debug("发送提示词到LLM: {}", prompt);
            
            // 使用低温度以获得更确定的结果
            String response = activeProvider.generate(prompt, 0.0);
            
            log.debug("LLM响应: {}", response);
            return response.trim();
            
        } catch (Exception e) {
            log.error("[LLMService] SQL生成失败", e);
            return "LLM调用失败: " + e.getMessage();
        }
    }
    
    /**
     * 总结查询结果
     */
    public String summarizeResult(String query, Object result) {
        String systemPrompt = "你是一个数据分析助手。请用简洁的中文总结查询结果，不超过3句话。";
        String userPrompt = String.format("问题: %s\n查询结果: %s", query, result.toString());
        
        try {
            return activeProvider.generateJson(systemPrompt, userPrompt, 0.7).trim();
        } catch (Exception e) {
            log.error("[LLMService] 总结结果失败", e);
            return "无法生成总结";
        }
    }
    
    /**
     * 澄清用户问题
     */
    public String clarifyQuestion(String query, String missingInfo) {
        String systemPrompt = "你是一个友好的对话助手。请生成一个友好的追问，提示用户补充缺少的信息。";
        String userPrompt = String.format("用户问题: %s\n缺少信息: %s", query, missingInfo);
        
        try {
            return activeProvider.generate(systemPrompt + "\n\n" + userPrompt, 0.7).trim();
        } catch (Exception e) {
            log.error("[LLMService] 生成澄清问题失败", e);
            return "请补充更多信息";
        }
    }
    
    /**
     * 意图分类
     */
    public String classifyIntent(String query) {
        String systemPrompt = "请将用户问题分类为以下类型之一：QUERY（查询）、CREATE（创建）、UPDATE（更新）、DELETE（删除）、OTHER（其他）。只返回分类名称，不要其他内容。";
        String userPrompt = "用户问题: " + query;
        
        try {
            String response = activeProvider.generate(systemPrompt + "\n\n" + userPrompt, 0.0).trim();
            return response.toUpperCase();
        } catch (Exception e) {
            log.error("[LLMService] 意图分类失败", e);
            return "OTHER";
        }
    }
    
    /**
     * 生成答案（用于非SQL场景）
     */
    public String generateAnswer(String prompt) {
        try {
            log.debug("发送提示词到LLM: {}", prompt);
            
            // 使用中等温度以获得更自然的回答
            String response = activeProvider.generate(prompt, 0.7);
            
            log.debug("LLM响应: {}", response);
            return response.trim();
            
        } catch (Exception e) {
            log.error("[LLMService] 答案生成失败", e);
            return "抱歉，无法生成回答: " + e.getMessage();
        }
    }
    
    /**
     * 获取当前活跃的提供者名称
     */
    public String getActiveProviderName() {
        return activeProvider.getName();
    }
    
    /**
     * 切换活跃的提供者
     */
    public void switchProvider(String providerName) {
        providerManager.setActiveProvider(providerName);
        this.activeProvider = providerManager.getActiveProvider();
        log.info("[LLMService] 已切换LLM提供者: {}", providerName);
    }
    
    /**
     * 获取所有可用提供者的健康状态
     */
    public java.util.Map<String, Boolean> getProviderHealthStatus() {
        return providerManager.healthCheck();
    }
}
