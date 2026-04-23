package com.nl2sql.core.llm;

import com.nl2sql.core.llm.extension.IndustryConceptExtension;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class SynonymService {
    
    // 同义词映射表（从 industry_concept 表加载）
    private final Map<String, List<String>> synonymMap = new ConcurrentHashMap<>();
    
    @Autowired(required = false)
    private JdbcTemplate jdbcTemplate;
    
    @Autowired(required = false)
    private IndustryConceptDictionary industryConceptDictionary;
    
    @Autowired(required = false)
    private List<IndustryConceptExtension> conceptExtensions;
    
    @PostConstruct
    public void init() {
        // 从 industry_concept 表加载同义词（供未来 Prompt 注入使用）
        loadSynonymsFromDatabase();
        
        log.info("[SynonymService] 同义词服务初始化完成: {}个概念", synonymMap.size());
    }
    
    /**
     * 从 industry_concept 表加载同义词
     */
    private void loadSynonymsFromDatabase() {
        if (jdbcTemplate == null) {
            log.warn("[SynonymService] JdbcTemplate未配置，跳过数据库加载");
            return;
        }
        
        try {
            List<Map<String, Object>> concepts = jdbcTemplate.queryForList(
                "SELECT concept_key, concept_aliases FROM industry_concept WHERE status = 'approved'"
            );
            
            for (Map<String, Object> concept : concepts) {
                String conceptKey = (String) concept.get("concept_key");
                String aliasesJson = (String) concept.get("concept_aliases");
                
                if (conceptKey != null && aliasesJson != null) {
                    try {
                        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                        List<String> aliases = mapper.readValue(aliasesJson, List.class);
                        
                        if (aliases != null && !aliases.isEmpty()) {
                            synonymMap.put(conceptKey, aliases);
                            log.debug("[SynonymService] 加载概念: {} -> {}", conceptKey, aliases);
                        }
                    } catch (Exception e) {
                        log.warn("[SynonymService] 解析别名JSON失败: {}", aliasesJson, e);
                    }
                }
            }
            
            log.info("[SynonymService] 从数据库加载 {} 个概念", synonymMap.size());
            
        } catch (Exception e) {
            log.error("[SynonymService] 从数据库加载同义词失败", e);
        }
    }
    
    /**
     * 扩展查询中的同义词
     * 
     * @param query 原始查询
     * @param datasourceId 数据源ID
     * @return 扩展后的查询（直接返回原query，不做任何替换）
     */
    public String expandSynonyms(String query, Long datasourceId) {
        // ✅ 不再做任何同义词替换，LLM会通过Schema注释和Prompt约束自行理解语义
        return query;
    }
    
    /**
     * 兼容旧版本API（无datasourceId）
     */
    public String expandSynonyms(String query) {
        return expandSynonyms(query, null);
    }
    
    /**
     * 获取所有已加载的概念（供 Prompt 注入使用）
     */
    public Map<String, List<String>> getAllConcepts() {
        return new HashMap<>(synonymMap);
    }
}
