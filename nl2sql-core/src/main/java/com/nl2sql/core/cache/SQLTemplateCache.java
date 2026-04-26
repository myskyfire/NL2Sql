package com.nl2sql.core.cache;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;

/**
 * SQL模板缓存服务
 * 存储带占位符的SQL模板，支持L2归一化匹配后的模板填充
 */
@Slf4j
public class SQLTemplateCache {
    
    private final Map<String, SQLTemplateEntry> cache = new HashMap<>();
    
    /**
     * 缓存SQL模板
     * 
     * @param normalizedJson 归一化JSON（作为Key）
     * @param sqlTemplate SQL模板（含占位符）
     * @param structure 查询结构（用于提取占位符映射）
     */
    public void put(String normalizedJson, String sqlTemplate, QueryStructureExtractor.QueryStructure structure) {
        if (normalizedJson == null || sqlTemplate == null) {
            return;
        }
        
        SQLTemplateEntry entry = new SQLTemplateEntry();
        entry.setNormalizedJson(normalizedJson);
        entry.setSqlTemplate(sqlTemplate);
        entry.setPlaceholders(extractPlaceholders(sqlTemplate, structure));
        entry.setCreatedAt(System.currentTimeMillis());
        
        cache.put(normalizedJson, entry);
        log.debug("[SQLTemplateCache] 缓存模板: key={}, placeholders={}", normalizedJson, entry.getPlaceholders());
    }
    
    /**
     * 获取SQL模板
     * 
     * @param normalizedJson 归一化JSON
     * @return SQL模板条目，未命中返回null
     */
    public SQLTemplateEntry get(String normalizedJson) {
        return cache.get(normalizedJson);
    }
    
    /**
     * 检查是否存在
     */
    public boolean containsKey(String normalizedJson) {
        return cache.containsKey(normalizedJson);
    }
    
    /**
     * 清除缓存
     */
    public void clear() {
        cache.clear();
    }
    
    /**
     * 获取缓存大小
     */
    public int size() {
        return cache.size();
    }
    
    /**
     * 从SQL和查询结构中提取占位符映射
     */
    private Map<String, Object> extractPlaceholders(String sqlTemplate, QueryStructureExtractor.QueryStructure structure) {
        Map<String, Object> placeholders = new HashMap<>();
        
        // 提取人名占位符
        if (structure.getPerson() != null && sqlTemplate.contains("{PERSON_NAME}")) {
            placeholders.put("PERSON_NAME", structure.getPerson());
        }
        
        // 提取时间占位符
        if (structure.getTime() != null) {
            if (sqlTemplate.contains("{OFFSET}")) {
                Integer offset = convertTimeToOffset(structure.getTime());
                if (offset != null) {
                    placeholders.put("OFFSET", offset);
                }
            }
            if (sqlTemplate.contains("{DATE_VALUE}")) {
                placeholders.put("DATE_VALUE", structure.getTime().getValue());
            }
        }
        
        // 提取地点占位符
        if (structure.getLocation() != null && sqlTemplate.contains("{LOCATION}")) {
            placeholders.put("LOCATION", structure.getLocation());
        }
        
        // 提取金额占位符
        if (structure.getAmount() != null && sqlTemplate.contains("{AMOUNT_VALUE}")) {
            placeholders.put("AMOUNT_VALUE", structure.getAmount().getValue());
        }
        
        // 提取ID占位符
        if (structure.getId() != null && sqlTemplate.contains("{ID_VALUE}")) {
            placeholders.put("ID_VALUE", structure.getId().getValue());
        }
        
        return placeholders;
    }
    
    /**
     * 将时间表达式转换为SQL偏移量
     */
    private Integer convertTimeToOffset(QueryStructureExtractor.TimeExpression time) {
        if ("RELATIVE".equals(time.getType())) {
            switch (time.getValue()) {
                case "今天": return 0;
                case "昨天": return 1;
                case "前天": return 2;
                case "明天": return -1;
                case "后天": return -2;
                default: return null;
            }
        }
        return null;
    }
    
    /**
     * SQL模板条目
     */
    @Data
    public static class SQLTemplateEntry {
        private String normalizedJson;      // 归一化JSON
        private String sqlTemplate;         // SQL模板（含占位符）
        private Map<String, Object> placeholders; // 占位符映射
        private Long createdAt;             // 创建时间
    }
}
