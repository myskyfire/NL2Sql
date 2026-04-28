package com.nl2sql.web.controller;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.hankcs.hanlp.HanLP;
import com.hankcs.hanlp.seg.common.Term;
import com.nl2sql.metadata.service.TermAutoGenerateService;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 术语智能建议 API
 * 
 * 提供行业术语自动补全功能，引导用户输入标准化业务术语
 */
@Slf4j
@RestController
@RequestMapping("/api/terms")
public class TermSuggestionController {
    
    @Autowired(required = false)
    private JdbcTemplate jdbcTemplate;
    
    @Autowired(required = false)
    private RedisTemplate<String, Object> redisTemplate;
    
    @Autowired(required = false)
    private TermAutoGenerateService termAutoGenerateService;
    
    // ✅ L1缓存：Caffeine（LRU淘汰，5分钟过期，最大1000条）
    private final Cache<String, List<TermSuggestion>> localCache = Caffeine.newBuilder()
        .maximumSize(1000)
        .expireAfterWrite(5, TimeUnit.MINUTES)
        .recordStats()
        .build();
    
    /**
     * 术语智能建议
     * 
     * @param prefix 输入前缀（支持模糊匹配）
     * @param industry 行业代码（可选，如ecommerce/finance）
     * @param datasourceId 数据源ID（可选，用于过滤该数据源的术语）
     * @param limit 返回数量限制（默认10，最大50）
     * @return 术语建议列表
     */
    @GetMapping("/suggest")
    public Map<String, Object> suggestTerms(
        @RequestParam String prefix,
        @RequestParam(required = false) String industry,
        @RequestParam(required = false) Long datasourceId,
        @RequestParam(defaultValue = "10") int limit
    ) {
        // 参数校验
        if (prefix == null || prefix.trim().isEmpty()) {
            return Map.of("success", false, "message", "prefix不能为空");
        }
        
        // ✅ 使用HanLP分词，提取最后一个词
        String searchWord = extractLastWordByHanLP(prefix);
        
        // 限制最大返回数量
        limit = Math.min(Math.max(limit, 1), 50);
        
        try {
            List<TermSuggestion> suggestions = getSuggestions(searchWord, industry, datasourceId, limit);
            
            log.debug("[TermSuggestion] 原始输入='{}', 分词后='{}', 行业={}, 返回{}条建议", 
                prefix, searchWord, industry, suggestions.size());
            
            return Map.of(
                "success", true,
                "data", suggestions,
                "count", suggestions.size()
            );
            
        } catch (Exception e) {
            log.error("[TermSuggestion] 术语建议失败: prefix={}", prefix, e);
            return Map.of("success", false, "message", "查询失败: " + e.getMessage());
        }
    }
    
    /**
     * ✅ 使用HanLP分词提取最后一个词
     */
    private String extractLastWordByHanLP(String text) {
        if (text == null || text.trim().isEmpty()) {
            return text;
        }
        
        try {
            // HanLP分词
            List<Term> terms = HanLP.segment(text);
            
            if (terms.isEmpty()) {
                return text;
            }
            
            // 提取最后一个有意义的词（过滤标点符号）
            for (int i = terms.size() - 1; i >= 0; i--) {
                Term term = terms.get(i);
                String word = term.word;
                
                // 跳过标点符号和空白字符
                if (!word.matches("[\\p{Punct}\\s]+") && word.length() >= 1) {
                    return word;
                }
            }
            
            // 如果所有词都被过滤，返回原文本
            return text;
            
        } catch (Exception e) {
            log.warn("[TermSuggestion] HanLP分词失败，使用原文本: {}", e.getMessage());
            return text;
        }
    }
    
    /**
     * ✅ 新增：HanLP分词API，返回词和位置信息
     */
    @GetMapping("/segment")
    public Map<String, Object> segmentText(@RequestParam String text) {
        if (text == null || text.trim().isEmpty()) {
            return Map.of("success", false, "message", "text不能为空");
        }
        
        try {
            List<Term> terms = HanLP.segment(text);
            
            List<String> words = new ArrayList<>();
            List<Integer> positions = new ArrayList<>();
            
            int currentPosition = 0;
            for (Term term : terms) {
                String word = term.word;
                // 跳过标点符号
                if (!word.matches("[\\p{Punct}\\s]+")) {
                    words.add(word);
                    positions.add(currentPosition);
                }
                currentPosition += word.length();
            }
            
            return Map.of(
                "success", true,
                "words", words,
                "positions", positions
            );
            
        } catch (Exception e) {
            log.error("[TermSuggestion] HanLP分词失败: text={}", text, e);
            return Map.of("success", false, "message", "分词失败: " + e.getMessage());
        }
    }
    
    /**
     * ✅ 新增：获取术语关联的动作列表
     * 
     * @param term 术语名称
     * @return 动作列表
     */
    @GetMapping("/actions")
    public Map<String, Object> getTermActions(@RequestParam String term) {
        if (term == null || term.trim().isEmpty()) {
            return Map.of("success", false, "message", "term不能为空");
        }
        
        try {
            if (jdbcTemplate == null) {
                return Map.of("success", false, "message", "JdbcTemplate未配置");
            }
            
            String sql = "SELECT action, action_label, sql_template_id, priority " +
                        "FROM term_action_mapping " +
                        "WHERE term = ? AND is_active = 1 " +
                        "ORDER BY priority DESC";
            
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, term);
            
            List<TermAction> actions = rows.stream()
                .map(this::convertToTermAction)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
            
            return Map.of(
                "success", true,
                "data", actions,
                "count", actions.size()
            );
            
        } catch (Exception e) {
            log.error("[TermSuggestion] 查询术语动作失败: term={}", term, e);
            return Map.of("success", false, "message", "查询失败: " + e.getMessage());
        }
    }
    
    /**
     * 获取术语建议（核心逻辑 - 带二级缓存）
     */
    private List<TermSuggestion> getSuggestions(String prefix, String industry, Long datasourceId, int limit) {
        if (jdbcTemplate == null) {
            log.warn("[TermSuggestion] JdbcTemplate未配置，返回空列表");
            return Collections.emptyList();
        }
        
        // 1. 如果指定了datasourceId，先查询对应的industry_code
        if (datasourceId != null && industry == null) {
            industry = queryIndustryByDatasource(datasourceId);
        }
        
        // ✅ 2. 构建缓存key
        String cacheKey = String.format("term:suggest:%s:%s", 
            industry != null ? industry : "all", 
            prefix.toLowerCase());
        
        // ✅ 3. 尝试从L1缓存（Caffeine）获取
        List<TermSuggestion> cached = localCache.getIfPresent(cacheKey);
        if (cached != null) {
            log.debug("[TermSuggestion] L1缓存命中: {}", cacheKey);
            return cached.subList(0, Math.min(limit, cached.size()));
        }
        
        // ✅ 4. 尝试从L2缓存（Redis）获取
        if (redisTemplate != null) {
            try {
                @SuppressWarnings("unchecked")
                List<TermSuggestion> redisCached = (List<TermSuggestion>) redisTemplate.opsForValue().get(cacheKey);
                if (redisCached != null) {
                    log.debug("[TermSuggestion] L2缓存命中: {}", cacheKey);
                    // 回填L1缓存
                    localCache.put(cacheKey, redisCached);
                    return redisCached.subList(0, Math.min(limit, redisCached.size()));
                }
            } catch (Exception e) {
                log.warn("[TermSuggestion] Redis查询失败: {}", e.getMessage());
            }
        }
        
        // ✅ 5. 数据库查询
        String sql = buildSuggestionSQL(industry);
        List<Map<String, Object>> rows;
        
        if (industry != null && !industry.isEmpty()) {
            rows = jdbcTemplate.queryForList(sql, "%" + prefix + "%", industry, limit);
        } else {
            rows = jdbcTemplate.queryForList(sql, "%" + prefix + "%", limit);
        }
        
        List<TermSuggestion> suggestions = rows.stream()
            .map(this::convertToTermSuggestion)
            .filter(Objects::nonNull)
            .collect(Collectors.toList());
        
        // ✅ 6. 写入缓存
        if (!suggestions.isEmpty()) {
            localCache.put(cacheKey, suggestions);
            
            if (redisTemplate != null) {
                try {
                    redisTemplate.opsForValue().set(cacheKey, suggestions, 30, TimeUnit.MINUTES);
                } catch (Exception e) {
                    log.warn("[TermSuggestion] Redis写入失败: {}", e.getMessage());
                }
            }
        }
        
        return suggestions.subList(0, Math.min(limit, suggestions.size()));
    }
    
    /**
     * 根据数据源ID查询行业代码
     */
    private String queryIndustryByDatasource(Long datasourceId) {
        try {
            // 优先从 datasource_industry_mapping 表查询
            String industryCode = jdbcTemplate.queryForObject(
                "SELECT industry_code FROM datasource_industry_mapping WHERE datasource_id = ? ORDER BY priority LIMIT 1",
                String.class, datasourceId
            );
            
            if (industryCode != null) {
                return industryCode;
            }
            
            // fallback到 business_category 字段
            String businessCategory = jdbcTemplate.queryForObject(
                "SELECT business_category FROM datasource_config WHERE id = ?",
                String.class, datasourceId
            );
            
            if (businessCategory != null) {
                return matchIndustryCodeByCategory(businessCategory);
            }
            
        } catch (Exception e) {
            log.warn("[TermSuggestion] 查询数据源行业配置失败: {}", e.getMessage());
        }
        
        return null;
    }
    
    /**
     * 根据业务分类匹配行业代码
     */
    private String matchIndustryCodeByCategory(String businessCategory) {
        if (businessCategory == null) return null;
        
        String lower = businessCategory.toLowerCase();
        if (lower.contains("电商") || lower.contains("零售")) return "ecommerce";
        if (lower.contains("金融") || lower.contains("银行")) return "finance";
        if (lower.contains("医疗") || lower.contains("医院")) return "medical";
        if (lower.contains("教育") || lower.contains("学校")) return "education";
        if (lower.contains("制造") || lower.contains("工厂")) return "manufacturing";
        
        return null;
    }
    
    /**
     * 构建术语建议SQL（使用新表term_suggestion）
     */
    private String buildSuggestionSQL(String industry) {
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT ");
        sql.append("  term, ");
        sql.append("  term_type AS type, ");
        sql.append("  usage_count ");
        sql.append("FROM term_suggestion ");
        sql.append("WHERE is_active = 1 ");
        sql.append("  AND term LIKE ? ");
        
        if (industry != null && !industry.isEmpty()) {
            sql.append("  AND industry_code = ? ");
        }
        
        sql.append("ORDER BY usage_count DESC ");
        sql.append("LIMIT ?");
        
        return sql.toString();
    }
    
    /**
     * ✅ 新增：自动爬库生成术语（调用LLM）
     * 
     * @param datasourceId 数据源ID
     * @return 生成的术语数量
     */
    @PostMapping("/auto-generate")
    public Map<String, Object> autoGenerateTerms(@RequestParam Long datasourceId) {
        if (termAutoGenerateService == null) {
            return Map.of("success", false, "message", "TermAutoGenerateService未配置");
        }
        
        try {
            int count = termAutoGenerateService.autoGenerateTerms(datasourceId);
            
            // 清除缓存，确保新术语生效
            clearCache();
            
            return Map.of(
                "success", true,
                "message", String.format("成功生成%d个术语", count),
                "count", count
            );
            
        } catch (Exception e) {
            log.error("[TermSuggestion] 自动生成术语失败: datasourceId={}", datasourceId, e);
            return Map.of("success", false, "message", "生成失败: " + e.getMessage());
        }
    }
    
    /**
     * ✅ 清除缓存（管理接口）
     */
    @PostMapping("/cache/clear")
    public Map<String, Object> clearCache() {
        localCache.invalidateAll();
        
        if (redisTemplate != null) {
            try {
                Set<String> keys = redisTemplate.keys("term:suggest:*");
                if (keys != null && !keys.isEmpty()) {
                    redisTemplate.delete(keys);
                }
            } catch (Exception e) {
                log.warn("[TermSuggestion] Redis清除缓存失败: {}", e.getMessage());
            }
        }
        
        log.info("[TermSuggestion] 缓存已清除");
        return Map.of("success", true, "message", "缓存已清除");
    }
    
    /**
     * 转换数据库行为TermSuggestion对象
     */
    private TermSuggestion convertToTermSuggestion(Map<String, Object> row) {
        try {
            TermSuggestion suggestion = new TermSuggestion();
            suggestion.setTerm((String) row.get("term"));
            suggestion.setType(mapConceptType((String) row.get("type")));
            suggestion.setUsageCount(((Number) row.get("usage_count")).intValue());
            return suggestion;
        } catch (Exception e) {
            log.warn("[TermSuggestion] 转换术语建议失败: {}", row, e);
            return null;
        }
    }
    
    /**
     * ✅ 新增：转换数据库行为TermAction对象
     */
    private TermAction convertToTermAction(Map<String, Object> row) {
        try {
            TermAction action = new TermAction();
            action.setAction((String) row.get("action"));
            action.setActionLabel((String) row.get("action_label"));
            action.setSqlTemplateId((String) row.get("sql_template_id"));
            action.setPriority(((Number) row.get("priority")).intValue());
            return action;
        } catch (Exception e) {
            log.warn("[TermSuggestion] 转换术语动作失败: {}", row, e);
            return null;
        }
    }
    
    /**
     * 映射概念类型到中文显示
     */
    private String mapConceptType(String conceptType) {
        if (conceptType == null) return "未知";
        
        switch (conceptType.toLowerCase()) {
            case "entity": return "业务实体";
            case "metric": return "关键指标";
            case "dimension": return "分析维度";
            case "table_role": return "表角色";
            case "usage_rule": return "使用规则";
            default: return conceptType;
        }
    }
    
    /**
     * 术语建议数据结构
     */
    @Data
    public static class TermSuggestion {
        /** 术语名称 */
        private String term;
        
        /** 术语类型（业务实体/关键指标/分析维度等） */
        private String type;
        
        /** 使用频率（用于排序） */
        private Integer usageCount;
    }
    
    /**
     * ✅ 新增：术语动作数据结构
     */
    @Data
    public static class TermAction {
        /** 动作类型：list/detail/stats/export/compare */
        private String action;
        
        /** 动作标签（前端显示） */
        private String actionLabel;
        
        /** SQL模板ID */
        private String sqlTemplateId;
        
        /** 优先级 */
        private Integer priority;
    }
}
