package com.nl2sql.core.agent.tools;

import com.nl2sql.core.agent.tool.BaseToolAdapter;
import com.nl2sql.core.agent.tool.ToolContext;
import com.nl2sql.core.rag.RagKnowledgeBaseService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * RAG增强工具 - 检索相似问答对增强SQL生成
 */
@Slf4j
@Component
public class RagEnhancerTool extends BaseToolAdapter {
    
    @Autowired(required = false)
    private RagKnowledgeBaseService ragService;
    
    @Override
    public String getName() { return "rag_enhancer"; }
    
    @Override
    public String getDescription() { return "从RAG知识库检索相似问答对，为SQL生成提供参考示例。"; }
    
    @Override
    public Map<String, Object> getParameterSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        Map<String, Object> props = new HashMap<>();
        props.put("query", Map.of("type", "string", "description", "用户自然语言问题"));
        schema.put("properties", props);
        schema.put("required", new String[]{"query"});
        return schema;
    }
    
    @Override
    public String getApplicableScenarios() { return "需要参考历史相似问答的场景"; }
    
    @Override
    public String getInapplicableScenarios() { return "RAG服务未启用的场景"; }
    
    @Override
    protected void validateParameters(ToolContext context) {
        parameterValidator
            .required("query", context.getParameter("query"))
            .throwIfHasErrors();
    }
    
    @Override
    protected Object doExecute(ToolContext context) throws Exception {
        String query = context.getRequiredParameter("query");
        
        if (ragService == null) {
            log.debug("[RagEnhancer] RAG服务未启用");
            return Map.of("enhancement", "", "enabled", false);
        }
        
        try {
            String enhancement = ragService.buildRAGEnhancement(query);
            log.debug("[RagEnhancer] RAG增强完成: length={}", enhancement.length());
            
            return Map.of(
                "enhancement", enhancement,
                "enabled", true,
                "hasExamples", !enhancement.trim().isEmpty()
            );
        } catch (Exception e) {
            log.warn("[RagEnhancer] RAG增强失败: {}", e.getMessage());
            return Map.of("enhancement", "", "enabled", true, "error", e.getMessage());
        }
    }
}
