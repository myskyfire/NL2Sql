package com.nl2sql.core.llm;

import com.nl2sql.core.rag.RagKnowledgeBaseService;
import dev.langchain4j.model.chat.ChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
public class MultiModelService {
    
    private final RagKnowledgeBaseService ragService;
    
    @Autowired
    private LLMService llmService;
    
    public MultiModelService(RagKnowledgeBaseService ragService) {
        this.ragService = ragService;
    }
    
    /**
     * 获取LLMService实例（用于元数据增强等场景）
     */
    public LLMService getLlmService() {
        return llmService;
    }
    
    /**
     * 使用代码模型生成SQL（RAG增强版）
     */
    public String generateSQLWithRAG(String prompt, String userQuery) {
        log.info("[LLM调用] 开始生成SQL, 用户查询: {}", userQuery);
        
        try {
            // 1. RAG检索相似问题
            List<RagKnowledgeBaseService.KnowledgeItem> similarItems = 
                ragService.searchSimilarQuestions(userQuery, 3);
            log.info("[LLM调用] RAG检索到{}个示例", similarItems.size());
            
            // 2. 构建增强Prompt
            String enhancedPrompt = buildRagEnhancedPrompt(prompt, similarItems);
            
            // 3. 调用代码模型
            long startTime = System.currentTimeMillis();
            String response = llmService.generateSQL(enhancedPrompt);
            long elapsed = System.currentTimeMillis() - startTime;
            
            log.info("[LLM调用] 响应时间: {}ms", elapsed);
            
            if (response == null || response.trim().isEmpty()) {
                log.warn("[LLM调用] LLM返回空响应");
                return "SELECT 1 as error -- LLM返回空响应";
            }
            
            // 4. 记录使用
            if (!similarItems.isEmpty()) {
                for (RagKnowledgeBaseService.KnowledgeItem item : similarItems) {
                    ragService.recordUsage(item.getId());
                }
            }
            
            return response.trim();
        } catch (Exception e) {
            log.error("[LLM调用] SQL生成异常", e);
            return "SELECT 1 as error -- SQL生成异常: " + e.getMessage();
        }
    }
    
    /**
     * 使用推理模型生成SQL（RAG增强版）- 用于复杂查询
     */
    public String generateSQLWithRAGUsingNLPModel(String prompt, String userQuery) {
        log.info("[LLM调用-推理模型] 开始生成SQL, 用户查询: {}", userQuery);
        
        try {
            // 1. RAG检索相似问题
            List<RagKnowledgeBaseService.KnowledgeItem> similarItems = 
                ragService.searchSimilarQuestions(userQuery, 5);
            log.info("[LLM调用-推理模型] RAG检索到{}个示例", similarItems.size());
            
            // 2. 构建增强Prompt
            String enhancedPrompt = buildRagEnhancedPrompt(prompt, similarItems);
            
            // 3. 调用推理模型
            long startTime = System.currentTimeMillis();
            String response = llmService.summarizeResult(userQuery, enhancedPrompt);
            long elapsed = System.currentTimeMillis() - startTime;
            
            log.info("[LLM调用-推理模型] 响应时间: {}ms", elapsed);
            
            if (response == null || response.trim().isEmpty()) {
                log.warn("[LLM调用-推理模型] LLM返回空响应");
                return "SELECT 1 as error -- LLM返回空响应";
            }
            
            // 4. 记录使用
            if (!similarItems.isEmpty()) {
                for (RagKnowledgeBaseService.KnowledgeItem item : similarItems) {
                    ragService.recordUsage(item.getId());
                }
            }
            
            return response.trim();
        } catch (Exception e) {
            log.error("[LLM调用-推理模型] SQL生成异常", e);
            // 降级：使用代码模型
            return generateSQLWithRAG(prompt, userQuery);
        }
    }
    
    /**
     * 普通SQL生成（无RAG）
     */
    public String generateSQL(String prompt) {
        try {
            return llmService.generateSQL(prompt);
        } catch (Exception e) {
            log.error("[LLM调用] SQL生成失败", e);
            return "SELECT 1 as error -- " + e.getMessage();
        }
    }
    
    /**
     * 构建RAG增强的Prompt
     */
    private String buildRagEnhancedPrompt(String basePrompt, List<RagKnowledgeBaseService.KnowledgeItem> examples) {
        if (examples == null || examples.isEmpty()) {
            return basePrompt;
        }
        
        StringBuilder enhancedPrompt = new StringBuilder(basePrompt);
        enhancedPrompt.append("\n\n参考示例（类似问题的SQL写法）:\n");
        
        for (int i = 0; i < examples.size(); i++) {
            RagKnowledgeBaseService.KnowledgeItem item = examples.get(i);
            enhancedPrompt.append(String.format(
                "\n示例%d:\n  问题: %s\n  SQL: %s\n",
                i + 1,
                item.getQuestion(),
                item.getSqlExample()
            ));
        }
        
        enhancedPrompt.append("\n请根据上述示例的风格和模式，生成当前问题的SQL。\n");
        
        return enhancedPrompt.toString();
    }
    
    /**
     * 总结结果（使用推理模型）
     */
    public String summarizeResult(String prompt) {
        try {
            // 使用generateAnswer方法
            return llmService.generateAnswer(prompt);
        } catch (Exception e) {
            log.error("[LLM调用] 总结失败", e);
            return "无法生成总结";
        }
    }
    
    /**
     * 直接生成答案（用于数据总结等非SQL场景）
     */
    public String generateAnswer(String question) {
        String prompt = String.format(
            "请回答以下问题，要求:\n" +
            "1. 用简洁清晰的中文回答\n" +
            "2. 如果有数据，突出关键数值和趋势\n" +
            "3. 不超过200字\n\n" +
            "问题: %s\n\n" +
            "回答:",
            question
        );
        
        try {
            return llmService.generateAnswer(prompt);
        } catch (Exception e) {
            log.error("[LLM调用] 答案生成失败", e);
            return "抱歉，无法生成回答: " + e.getMessage();
        }
    }
}
