package com.nl2sql.core.agent.tool;

import com.nl2sql.core.cache.QueryCacheService;
import com.nl2sql.core.llm.IndustryConceptDictionary;
import com.nl2sql.core.llm.ModelRouterService;
import com.nl2sql.core.llm.SynonymService;
import com.nl2sql.core.metadata.MetadataService;
import com.nl2sql.core.rag.RagKnowledgeBaseService;
import com.nl2sql.core.retriever.VectorRetriever;
import com.nl2sql.metadata.service.TableRelationshipService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * NL2SQL工具 - 基于新框架重构版本（试点）
 * 
 * 演示如何使用统一的Tool接口、错误处理和参数校验框架
 */
@Slf4j
@Component("nl2sqlToolV2")
public class NL2SQLToolV2 implements BaseTool {
    
    @Autowired
    private JdbcTemplate jdbcTemplate;
    
    @Autowired
    private VectorRetriever vectorRetriever;
    
    @Autowired
    private TableRelationshipService relationshipService;
    
    @Autowired
    private ModelRouterService modelRouter;
    
    @Autowired
    private SynonymService synonymService;
    
    @Autowired
    private IndustryConceptDictionary industryConceptDictionary;
    
    @Autowired(required = false)
    private ApplicationEventPublisher eventPublisher;
    
    @Autowired(required = false)
    private QueryCacheService queryCacheService;
    
    @Autowired
    private MetadataService metadataService;
    
    @Autowired(required = false)
    private RagKnowledgeBaseService ragService;
    
    @Override
    public String getName() {
        return "nl2sql";
    }
    
    @Override
    public String getDescription() {
        return "将自然语言问题转换为SQL查询语句。适用于数据查询场景，不支持增删改操作。";
    }
    
    @Override
    public Map<String, Object> getParameterSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        
        Map<String, Object> properties = new HashMap<>();
        
        // query 参数
        Map<String, Object> queryParam = new HashMap<>();
        queryParam.put("type", "string");
        queryParam.put("description", "用户的自然语言问题，例如：'查询上个月的销售额'");
        properties.put("query", queryParam);
        
        // datasourceId 参数
        Map<String, Object> datasourceParam = new HashMap<>();
        datasourceParam.put("type", "integer");
        datasourceParam.put("description", "数据源ID，必须是正整数");
        properties.put("datasourceId", datasourceParam);
        
        schema.put("properties", properties);
        schema.put("required", Arrays.asList("query", "datasourceId"));
        
        return schema;
    }
    
    @Override
    public String getApplicableScenarios() {
        return "适用于以下场景：\n" +
               "1. 数据统计查询（如：统计订单数量、计算平均价格）\n" +
               "2. 数据筛选（如：查找特定条件的记录）\n" +
               "3. 数据聚合分析（如：按月份分组统计）\n" +
               "4. 多表关联查询";
    }
    
    @Override
    public String getInapplicableScenarios() {
        return "不适用于以下场景：\n" +
               "1. 数据修改操作（INSERT/UPDATE/DELETE）\n" +
               "2. 数据库结构变更（CREATE/DROP/ALTER）\n" +
               "3. 系统管理操作\n" +
               "4. 非数据库相关的通用问答";
    }
    
    @Override
    public ToolResult execute(ToolContext context) {
        long startTime = System.currentTimeMillis();
        
        try {
            // 1. 参数校验
            validateParameters(context);
            
            String query = context.getRequiredParameter("query");
            Long datasourceId = context.getRequiredParameter("datasourceId");
            
            log.info("[NL2SQLToolV2] 开始执行: query={}, datasourceId={}", query, datasourceId);
            
            // 2. 缓存检查
            String cachedSQL = checkCache(query);
            if (cachedSQL != null) {
                Map<String, Object> metadata = new HashMap<>();
                metadata.put("cacheHit", true);
                metadata.put("executionTimeMs", System.currentTimeMillis() - startTime);
                return ToolResult.success(cachedSQL, metadata);
            }
            
            // 3. 同义词扩展
            String expandedQuery = expandSynonyms(query, datasourceId);
            
            // 4. 向量检索相关表
            List<String> tables = retrieveTables(expandedQuery, datasourceId);
            if (tables.isEmpty()) {
                return ToolResult.error("未找到相关表，请检查元数据是否已加载");
            }
            
            // 5. 生成SQL（简化版，实际逻辑需要完整实现）
            String sql = generateSQLInternal(expandedQuery, tables, datasourceId);
            
            // 6. 构建结果
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("cacheHit", false);
            metadata.put("tablesUsed", tables);
            metadata.put("executionTimeMs", System.currentTimeMillis() - startTime);
            metadata.put("expandedQuery", expandedQuery);
            
            return ToolResult.success(sql, metadata);
            
        } catch (IllegalArgumentException e) {
            // 参数校验错误
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("executionTimeMs", System.currentTimeMillis() - startTime);
            return ToolResult.error(e.getMessage(), metadata);
            
        } catch (Exception e) {
            // 其他异常
            log.error("[NL2SQLToolV2] 执行失败", e);
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("executionTimeMs", System.currentTimeMillis() - startTime);
            metadata.put("errorType", e.getClass().getSimpleName());
            return ToolResult.error("SQL生成失败: " + e.getMessage(), metadata);
        }
    }
    
    /**
     * 参数校验
     */
    private void validateParameters(ToolContext context) {
        String query = context.getParameter("query");
        Long datasourceId = context.getParameter("datasourceId");
        
        if (query == null || query.trim().isEmpty()) {
            throw new IllegalArgumentException("查询问题不能为空");
        }
        
        if (datasourceId == null || datasourceId <= 0) {
            throw new IllegalArgumentException("数据源ID必须为正整数");
        }
        
        if (query.length() > 1000) {
            throw new IllegalArgumentException("查询问题长度不能超过1000字符");
        }
    }
    
    /**
     * 检查缓存
     */
    private String checkCache(String query) {
        if (queryCacheService == null) {
            return null;
        }
        
        try {
            QueryCacheService.CachedResult cached = queryCacheService.getFromCache(query);
            if (cached != null && cached.getUserRating() != null && cached.getUserRating() == 5) {
                log.info("[NL2SQLToolV2] ⚡ 5分SQL模板命中");
                return cached.getSql();
            }
        } catch (Exception e) {
            log.debug("[NL2SQLToolV2] 缓存检查失败", e);
        }
        
        return null;
    }
    
    /**
     * 同义词扩展
     */
    private String expandSynonyms(String query, Long datasourceId) {
        try {
            return synonymService.expandSynonyms(query, datasourceId);
        } catch (Exception e) {
            log.warn("[NL2SQLToolV2] 同义词扩展失败，使用原始查询", e);
            return query;
        }
    }
    
    /**
     * 向量检索相关表
     */
    private List<String> retrieveTables(String query, Long datasourceId) {
        try {
            return vectorRetriever.retrieveTopTables(query, datasourceId, 15);
        } catch (Exception e) {
            log.error("[NL2SQLToolV2] 表检索失败", e);
            return Collections.emptyList();
        }
    }
    
    /**
     * 内部SQL生成逻辑（简化版）
     */
    private String generateSQLInternal(String query, List<String> tables, Long datasourceId) {
        // TODO: 这里需要完整的SQL生成逻辑
        // 为了演示框架，暂时返回占位符
        return "-- SQL生成逻辑待实现\nSELECT * FROM " + String.join(", ", tables) + " LIMIT 10";
    }
}
