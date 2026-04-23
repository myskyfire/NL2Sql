package com.nl2sql.core.agent.tools;

import com.nl2sql.core.agent.tool.BaseToolAdapter;
import com.nl2sql.core.agent.tool.ToolContext;
import com.nl2sql.core.llm.IndustryConceptDictionary;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 行业概念注入工具 - 生成行业特定的指标和维度描述
 */
@Slf4j
@Component
public class IndustryConceptInjectorTool extends BaseToolAdapter {
    
    @Autowired
    private IndustryConceptDictionary dictionary;
    
    @Override
    public String getName() { return "industry_concept_injector"; }
    
    @Override
    public String getDescription() { return "根据数据源生成行业特定的指标、维度和表角色描述，辅助SQL生成。"; }
    
    @Override
    public Map<String, Object> getParameterSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        Map<String, Object> props = new HashMap<>();
        props.put("datasourceId", Map.of("type", "integer", "description", "数据源ID"));
        schema.put("properties", props);
        schema.put("required", new String[]{"datasourceId"});
        return schema;
    }
    
    @Override
    public String getApplicableScenarios() { return "需要行业领域知识的场景"; }
    
    @Override
    public String getInapplicableScenarios() { return "通用查询场景"; }
    
    @Override
    protected void validateParameters(ToolContext context) {
        parameterValidator
            .required("datasourceId", context.getParameter("datasourceId"))
            .throwIfHasErrors();
    }
    
    @Override
    protected Object doExecute(ToolContext context) throws Exception {
        Long datasourceId = context.getRequiredParameter("datasourceId");
        
        try {
            String metrics = dictionary.generateMetricDescription(datasourceId);
            String dimensions = dictionary.generateDimensionDescription(datasourceId);
            String tableRoles = dictionary.generateTableRoleDescription(datasourceId);
            
            log.debug("[IndustryConceptInjector] 行业概念注入完成: datasourceId={}", datasourceId);
            
            return Map.of(
                "metrics", metrics != null ? metrics : "",
                "dimensions", dimensions != null ? dimensions : "",
                "tableRoles", tableRoles != null ? tableRoles : "",
                "enabled", true
            );
        } catch (Exception e) {
            log.warn("[IndustryConceptInjector] 行业概念注入失败: {}", e.getMessage());
            return Map.of("metrics", "", "dimensions", "", "tableRoles", "", "enabled", false);
        }
    }
}
