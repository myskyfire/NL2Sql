package com.nl2sql.core.agent.tools;

import com.nl2sql.core.agent.tool.BaseToolAdapter;
import com.nl2sql.core.agent.tool.ToolContext;
import com.nl2sql.core.cache.QueryCacheService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 查询缓存检查工具 - 检查是否有缓存的SQL
 */
@Slf4j
@Component
public class QueryCacheCheckerTool extends BaseToolAdapter {
    
    @Autowired(required = false)
    private QueryCacheService cacheService;
    
    @Override
    public String getName() { return "query_cache_checker"; }
    
    @Override
    public String getDescription() { return "检查用户问题是否有缓存的SQL结果，避免重复生成。"; }
    
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
    public String getApplicableScenarios() { return "需要检查是否有缓存SQL时"; }
    
    @Override
    public String getInapplicableScenarios() { return "不需要缓存的场景"; }
    
    @Override
    protected void validateParameters(ToolContext context) {
        parameterValidator
            .required("query", context.getParameter("query"))
            .throwIfHasErrors();
    }
    
    @Override
    protected Object doExecute(ToolContext context) throws Exception {
        String query = context.getRequiredParameter("query");
        
        if (cacheService == null) {
            log.debug("[QueryCacheChecker] 缓存服务未启用");
            return Map.of("hit", false, "reason", "缓存服务未启用");
        }
        
        // 检查普通缓存
        QueryCacheService.CachedResult cached = cacheService.getFromCache(query);
        
        if (cached != null && cached.getData() != null && !cached.getData().isEmpty()) {
            String cachedSQL = (String) cached.getData().get(0).get("sql");
            if (cachedSQL != null && !cachedSQL.trim().isEmpty()) {
                log.info("[QueryCacheChecker] ✅ 缓存命中: query={}", query);
                return Map.of(
                    "hit", true,
                    "sql", cachedSQL,
                    "fromCache", true,
                    "rating", cached.getUserRating()
                );
            }
        }
        
        log.debug("[QueryCacheChecker] 缓存未命中: query={}", query);
        return Map.of("hit", false, "fromCache", false);
    }
}
