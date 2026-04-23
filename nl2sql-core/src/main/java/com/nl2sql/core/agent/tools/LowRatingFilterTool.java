package com.nl2sql.core.agent.tools;

import com.nl2sql.core.agent.tool.BaseToolAdapter;
import com.nl2sql.core.agent.tool.ToolContext;
import com.nl2sql.core.rag.LowRatingExampleService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 低分示例过滤工具 - 获取低分SQL示例避免重复错误
 */
@Slf4j
@Component
public class LowRatingFilterTool extends BaseToolAdapter {
    
    @Autowired(required = false)
    private LowRatingExampleService lowRatingService;
    
    @Override
    public String getName() { return "low_rating_filter"; }
    
    @Override
    public String getDescription() { return "检索历史低分SQL示例，避免生成相似的错误SQL。"; }
    
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
    public String getApplicableScenarios() { return "需要避免历史错误的场景"; }
    
    @Override
    public String getInapplicableScenarios() { return "低分服务未启用的场景"; }
    
    @Override
    protected void validateParameters(ToolContext context) {
        parameterValidator
            .required("query", context.getParameter("query"))
            .throwIfHasErrors();
    }
    
    @Override
    protected Object doExecute(ToolContext context) throws Exception {
        String query = context.getRequiredParameter("query");
        
        if (lowRatingService == null) {
            log.debug("[LowRatingFilter] 低分服务未启用");
            return Map.of("negativeExamples", List.of(), "enabled", false);
        }
        
        try {
            String negativeExamples = lowRatingService.getNegativeExamples(query);
            
            // 计算示例数量
            int count = negativeExamples.isEmpty() ? 0 : 
                negativeExamples.split("\\*\\*反例 \\d+:\\*\\*").length - 1;
            
            log.debug("[LowRatingFilter] 获取到低分示例: count={}", count);
            
            return Map.of(
                "negativeExamples", negativeExamples,
                "enabled", true,
                "count", count
            );
        } catch (Exception e) {
            log.warn("[LowRatingFilter] 获取低分示例失败: {}", e.getMessage());
            return Map.of("negativeExamples", List.of(), "enabled", true, "error", e.getMessage());
        }
    }
}
