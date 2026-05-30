package com.nl2sql.core.agent.tools;

import com.nl2sql.core.agent.tool.BaseToolAdapter;
import com.nl2sql.core.agent.tool.ToolContext;
import com.nl2sql.core.llm.SynonymService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 同义词扩展工具 - 扩展用户问题的语义
 */
@Slf4j
@Component
public class SynonymExpanderTool extends BaseToolAdapter {
    
    @Autowired
    private SynonymService synonymService;
    
    @Override
    public String getName() { return "synonym_expander"; }
    
    @Override
    public String getDescription() { return "对用户问题进行同义词扩展，增强语义理解能力。"; }
    
    @Override
    public Map<String, Object> getParameterSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        Map<String, Object> props = new HashMap<>();
        props.put("query", Map.of("type", "string", "description", "用户自然语言问题"));
        props.put("datasourceId", Map.of("type", "integer", "description", "数据源ID"));
        schema.put("properties", props);
        schema.put("required", new String[]{"query", "datasourceId"});
        return schema;
    }
    
    @Override
    public String getApplicableScenarios() { return "需要增强语义理解的场景"; }
    
    @Override
    public String getInapplicableScenarios() { return "问题已经很明确的场景"; }
    
    @Override
    protected void validateParameters(ToolContext context) {
        parameterValidator
            .required("query", context.getParameter("query"))
            .required("datasourceId", context.getParameter("datasourceId"))
            .throwIfHasErrors();
    }
    
    @Override
    protected Object doExecute(ToolContext context) throws Exception {
        String query = context.getRequiredParameter("query");
        Long datasourceId = context.getRequiredParameter("datasourceId");
        
        // ✅ P0优化：expandSynonyms已改为直接返回原query，此Tool仅保留接口兼容性
        String expanded = query;
        boolean hasExpansion = false;
        
        log.debug("[SynonymExpander] 同义词扩展已禁用，直接使用原始查询: {}", query);
        
        return Map.of(
            "original", query,
            "expanded", expanded,
            "hasExpansion", hasExpansion
        );
    }
}
