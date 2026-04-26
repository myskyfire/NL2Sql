package com.nl2sql.core.cache;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 行业目标提取器接口
 * 由各行业插件实现，用于提取行业特定的目标关键词
 */
@FunctionalInterface
public interface IndustryTargetExtractor {
    
    /**
     * 从查询中提取行业特定目标
     * 
     * @param query 用户查询
     * @return 目标关键词（如"订单"、"GMV"、"库存"等），无法识别返回null
     */
    String extract(String query);
    
    // ==================== 默认实现：基于配置的通用提取逻辑 ====================
    
    /**
     * 默认实现：从 industry_concept 表加载 entity/metric 概念，按优先级匹配
     * 
     * @param query 用户查询
     * @param jdbcTemplate 数据库连接
     * @param industryCode 行业代码
     * @return 匹配的目标关键词
     */
    static String extractFromConfig(String query, JdbcTemplate jdbcTemplate, String industryCode) {
        if (query == null || query.trim().isEmpty()) {
            return null;
        }
        
        try {
            // 1. 从行业表加载所有 entity 和 metric 概念（按 concept_key 分组）
            List<Map<String, Object>> concepts = jdbcTemplate.queryForList(
                "SELECT concept_type, concept_key, concept_aliases " +
                "FROM industry_concept " +
                "WHERE industry_code = ? AND status = 'approved' " +
                "AND concept_type IN ('entity', 'metric') " +
                "ORDER BY FIELD(concept_type, 'metric', 'entity'), concept_key",
                industryCode
            );
            
            if (concepts.isEmpty()) {
                return null;
            }
            
            // 2. 构建优先级列表：metric 优先于 entity
            List<ConceptGroup> priorityGroups = new ArrayList<>();
            for (Map<String, Object> concept : concepts) {
                String type = (String) concept.get("concept_type");
                String key = (String) concept.get("concept_key");
                String aliasesJson = (String) concept.get("concept_aliases");
                
                // 解析别名 JSON 数组
                List<String> aliases = parseAliases(aliasesJson);
                
                // 添加到对应类型的组
                ConceptGroup group = priorityGroups.stream()
                    .filter(g -> g.type.equals(type))
                    .findFirst()
                    .orElseGet(() -> {
                        ConceptGroup newGroup = new ConceptGroup(type);
                        priorityGroups.add(newGroup);
                        return newGroup;
                    });
                
                group.addConcept(key, aliases);
            }
            
            // 3. 按优先级匹配：先 metric，后 entity
            for (ConceptGroup group : priorityGroups) {
                String matchedKey = group.match(query);
                if (matchedKey != null) {
                    return matchedKey;
                }
            }
            
            return null;
            
        } catch (Exception e) {
            // 降级：配置加载失败时返回 null，使用通用提取器
            return null;
        }
    }
    
    /**
     * 解析别名 JSON 数组
     */
    private static List<String> parseAliases(String aliasesJson) {
        if (aliasesJson == null || aliasesJson.trim().isEmpty()) {
            return Collections.emptyList();
        }
        
        try {
            // 简单解析：["别名1","别名2"] → List
            String cleaned = aliasesJson.trim();
            if (cleaned.startsWith("[") && cleaned.endsWith("]")) {
                cleaned = cleaned.substring(1, cleaned.length() - 1);
                return Arrays.stream(cleaned.split(","))
                    .map(s -> s.trim().replaceAll("^\"|\"$", ""))
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toList());
            }
        } catch (Exception e) {
            // 解析失败返回空列表
        }
        
        return Collections.emptyList();
    }
    
    /**
     * 概念组（同一类型的所有概念）
     */
    class ConceptGroup {
        final String type;
        final List<Concept> concepts = new ArrayList<>();
        
        ConceptGroup(String type) {
            this.type = type;
        }
        
        void addConcept(String key, List<String> aliases) {
            concepts.add(new Concept(key, aliases));
        }
        
        /**
         * 匹配查询，返回第一个匹配的 concept_key
         */
        String match(String query) {
            for (Concept concept : concepts) {
                if (concept.matches(query)) {
                    return concept.key;
                }
            }
            return null;
        }
    }
    
    /**
     * 单个概念（key + 别名列表）
     */
    class Concept {
        final String key;
        final List<String> aliases;
        
        Concept(String key, List<String> aliases) {
            this.key = key;
            this.aliases = aliases;
        }
        
        boolean matches(String query) {
            return aliases.stream().anyMatch(query::contains);
        }
    }
}
