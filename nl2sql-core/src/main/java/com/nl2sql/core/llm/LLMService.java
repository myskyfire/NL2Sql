package com.nl2sql.core.llm;

import com.nl2sql.core.llm.provider.LLMProvider;
import dev.langchain4j.model.chat.ChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;

@Slf4j
@Service
public class LLMService {
    
    @Autowired
    @Qualifier("reasoningProvider")
    private LLMProvider reasoningProvider;  // 推理模型
    
    @Autowired(required = false)
    @Qualifier("codeProvider")
    private LLMProvider codeProvider;       // 代码生成模型（可选）
    
    @Value("${llm.fallback.single-model-mode:auto}")
    private String singleModelMode;
    
    private ChatModel reasoningModel;
    private ChatModel codeModel;
    private boolean isSingleModelMode = false;
    
    @PostConstruct
    public void initModels() {
        this.reasoningModel = reasoningProvider.getChatModel();
        
        // 检查是否为单模型模式
        if (codeProvider == null || reasoningProvider.equals(codeProvider)) {
            this.codeModel = reasoningModel;
            this.isSingleModelMode = true;
            log.info("[LLMService] 运行模式: 单模型模式（{}）", reasoningProvider.getProviderName());
        } else {
            this.codeModel = codeProvider.getChatModel();
            this.isSingleModelMode = "true".equalsIgnoreCase(singleModelMode);
            log.info("[LLMService] 运行模式: 双模型模式");
            log.info("  - 推理模型: {}", reasoningProvider.getProviderName());
            log.info("  - 代码模型: {}", codeProvider.getProviderName());
        }
        
        // 健康检查
        checkHealth();
    }
    
    private void checkHealth() {
        boolean reasoningOk = reasoningProvider.isAvailable();
        boolean codeOk = codeProvider != null && codeProvider.isAvailable();
        
        if (!reasoningOk) {
            log.error("⚠️ 推理模型不可用！");
        }
        if (!codeOk && !isSingleModelMode) {
            log.warn("⚠️ 代码模型不可用，将使用推理模型降级");
        }
        
        log.info("[LLMService] 健康检查完成: reasoning={}, code={}", 
            reasoningOk ? "✅" : "❌", 
            (codeOk || isSingleModelMode) ? "✅" : "❌");
    }
    
    /**
     * 获取推理模型（用于Agent决策、总结等）
     */
    public ChatModel getReasoningModel() {
        return reasoningModel;
    }
    
    /**
     * 获取代码模型（用于SQL生成）
     */
    public ChatModel getCodeModel() {
        return codeModel;
    }
    
    /**
     * 兼容旧API：默认返回推理模型
     */
    public ChatModel getChatModel() {
        return reasoningModel;
    }
    
    /**
     * 智能选择模型
     */
    public ChatModel selectModel(ModelType type) {
        switch (type) {
            case REASONING:
            case SUMMARY:
            case INTENT_CLASSIFICATION:
                return reasoningModel;
            case SQL_GENERATION:
            case CODE_GENERATION:
                return codeModel;
            default:
                return reasoningModel;
        }
    }
    
    public String generateSQL(String prompt) {
        try {
            log.debug("发送提示词到代码模型: {}", prompt.substring(0, Math.min(100, prompt.length())));
            String response = codeModel.chat(prompt);
            log.debug("代码模型响应: {}", response.substring(0, Math.min(100, response.length())));
            return response.trim();
        } catch (Exception e) {
            log.error("代码模型调用失败", e);
            return "LLM调用失败: " + e.getMessage();
        }
    }
    
    public String summarizeResult(String query, Object result) {
        String prompt = String.format(
            "问题: %s\n查询结果: %s\n\n请用简洁的中文总结查询结果，不超过3句话:",
            query, result.toString()
        );
        
        try {
            return reasoningModel.chat(prompt).trim();
        } catch (Exception e) {
            log.error("总结结果失败", e);
            return "无法生成总结";
        }
    }
    
    public String clarifyQuestion(String query, String missingInfo) {
        String prompt = String.format(
            "用户问题: %s\n缺少信息: %s\n\n请生成一个友好的追问，提示用户补充缺少的信息:",
            query, missingInfo
        );
        
        try {
            return reasoningModel.chat(prompt).trim();
        } catch (Exception e) {
            log.error("生成澄清问题失败", e);
            return "请补充更多信息";
        }
    }
    
    public enum ModelType {
        REASONING,      // 推理
        SQL_GENERATION, // SQL生成
        SUMMARY,        // 总结
        INTENT_CLASSIFICATION, // 意图分类
        CODE_GENERATION // 代码生成
    }
}
