package com.nl2sql.web.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 行业概念管理后台 API
 * 
 * 提供行业概念的CRUD、审核、学习记录管理等功能
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/industry-concepts")
public class IndustryConceptAdminController {
    
    @Autowired
    private JdbcTemplate jdbcTemplate;
    
    /**
     * 获取所有行业模板
     */
    @GetMapping("/templates")
    public Map<String, Object> getTemplates() {
        List<Map<String, Object>> templates = jdbcTemplate.queryForList(
            "SELECT * FROM industry_template ORDER BY id"
        );
        
        return Map.of("success", true, "data", templates);
    }
    
    /**
     * 获取指定行业的所有概念
     */
    @GetMapping("/{industryCode}/concepts")
    public Map<String, Object> getConcepts(@PathVariable String industryCode,
                                           @RequestParam(required = false) String type,
                                           @RequestParam(required = false) String status) {
        StringBuilder sql = new StringBuilder(
            "SELECT * FROM industry_concept WHERE industry_code = ?"
        );
        List<Object> params = new ArrayList<>();
        params.add(industryCode);
        
        if (type != null && !type.isEmpty()) {
            sql.append(" AND concept_type = ?");
            params.add(type);
        }
        
        if (status != null && !status.isEmpty()) {
            sql.append(" AND status = ?");
            params.add(status);
        }
        
        sql.append(" ORDER BY usage_count DESC, created_at DESC");
        
        List<Map<String, Object>> concepts = jdbcTemplate.queryForList(sql.toString(), params.toArray());
        
        return Map.of("success", true, "data", concepts, "count", concepts.size());
    }
    
    /**
     * 添加新概念
     */
    @PostMapping("/{industryCode}/concepts")
    public Map<String, Object> addConcept(@PathVariable String industryCode,
                                          @RequestBody Map<String, Object> request) {
        try {
            String type = (String) request.get("conceptType");
            String key = (String) request.get("conceptKey");
            List<String> aliases = (List<String>) request.get("aliases");
            String description = (String) request.get("description");
            
            // 将别名列表转为JSON
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            String aliasesJson = mapper.writeValueAsString(aliases);
            
            jdbcTemplate.update(
                "INSERT INTO industry_concept (industry_code, concept_type, concept_key, concept_aliases, description, source, status) " +
                "VALUES (?, ?, ?, ?, ?, 'manual', 'approved')",
                industryCode, type, key, aliasesJson, description
            );
            
            log.info("[IndustryConceptAdmin] 添加概念: {}.{} = {}", industryCode, key, aliases);
            
            return Map.of("success", true, "message", "概念添加成功");
            
        } catch (Exception e) {
            log.error("[IndustryConceptAdmin] 添加概念失败", e);
            return Map.of("success", false, "error", e.getMessage());
        }
    }
    
    /**
     * 更新概念
     */
    @PutMapping("/concepts/{id}")
    public Map<String, Object> updateConcept(@PathVariable Long id,
                                             @RequestBody Map<String, Object> request) {
        try {
            List<String> aliases = (List<String>) request.get("aliases");
            String description = (String) request.get("description");
            
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            String aliasesJson = mapper.writeValueAsString(aliases);
            
            jdbcTemplate.update(
                "UPDATE industry_concept SET concept_aliases = ?, description = ? WHERE id = ?",
                aliasesJson, description, id
            );
            
            return Map.of("success", true, "message", "概念更新成功");
            
        } catch (Exception e) {
            log.error("[IndustryConceptAdmin] 更新概念失败", e);
            return Map.of("success", false, "error", e.getMessage());
        }
    }
    
    /**
     * 删除概念
     */
    @DeleteMapping("/concepts/{id}")
    public Map<String, Object> deleteConcept(@PathVariable Long id) {
        jdbcTemplate.update("DELETE FROM industry_concept WHERE id = ?", id);
        return Map.of("success", true, "message", "概念删除成功");
    }
    
    /**
     * 关联数据源到行业
     */
    @PostMapping("/datasource/{datasourceId}/link")
    public Map<String, Object> linkDatasource(@PathVariable Long datasourceId,
                                              @RequestBody Map<String, Object> request) {
        String industryCode = (String) request.get("industryCode");
        Integer priority = (Integer) request.getOrDefault("priority", 1);
        
        jdbcTemplate.update(
            "INSERT INTO datasource_industry_mapping (datasource_id, industry_code, priority) " +
            "VALUES (?, ?, ?) " +
            "ON DUPLICATE KEY UPDATE industry_code = VALUES(industry_code), priority = VALUES(priority)",
            datasourceId, industryCode, priority
        );
        
        log.info("[IndustryConceptAdmin] 数据源{}关联到行业{}", datasourceId, industryCode);
        
        return Map.of("success", true, "message", "关联成功");
    }
    
    /**
     * ✅ 从用户反馈中学习新术语（简单高效）
     * 
     * 用户场景：
     * 1. 用户查询"统计各地区GMV"
     * 2. 系统生成SQL并执行成功
     * 3. 用户评分5星
     * 4. 前端提示："是否将'GMV'添加到行业词典？"
     * 5. 用户点击"是"，调用此接口
     */
    @PostMapping("/learn-from-feedback")
    public Map<String, Object> learnFromFeedback(@RequestBody Map<String, Object> request) {
        try {
            String term = (String) request.get("term");           // 如：GMV
            String question = (String) request.get("question");   // 原始问题
            Long datasourceId = ((Number) request.get("datasourceId")).longValue();
            String suggestedConcept = (String) request.get("suggestedConcept"); // 建议映射到的概念，如：revenue
            
            if (term == null || term.trim().isEmpty()) {
                return Map.of("success", false, "error", "术语不能为空");
            }
            
            // 1. 获取数据源的行业代码
            String industryCode = jdbcTemplate.queryForObject(
                "SELECT industry_code FROM datasource_industry_mapping WHERE datasource_id = ? LIMIT 1",
                String.class, datasourceId
            );
            
            if (industryCode == null) {
                // fallback：从 business_category 推断
                String businessCategory = jdbcTemplate.queryForObject(
                    "SELECT business_category FROM datasource_config WHERE id = ?",
                    String.class, datasourceId
                );
                
                if (businessCategory != null) {
                    industryCode = matchIndustryCode(businessCategory);
                }
            }
            
            if (industryCode == null) {
                return Map.of("success", false, "error", "无法确定数据源所属行业，请先配置行业");
            }
            
            // 2. 如果未指定目标概念，尝试智能匹配
            if (suggestedConcept == null || suggestedConcept.trim().isEmpty()) {
                suggestedConcept = suggestConceptByTerm(term, industryCode);
            }
            
            // 3. 插入同义词关系
            if (suggestedConcept != null) {
                jdbcTemplate.update(
                    "INSERT IGNORE INTO concept_relation (industry_code, source_concept_key, target_concept_key) VALUES (?, ?, ?)",
                    industryCode, suggestedConcept, term.toLowerCase()
                );
                
                log.info("[IndustryConceptAdmin] 从反馈学习: {} -> {} (行业: {})", 
                    suggestedConcept, term, industryCode);
                
                return Map.of(
                    "success", true, 
                    "message", String.format("已添加 '%s' 作为 '%s' 的同义词", term, suggestedConcept),
                    "industryCode", industryCode,
                    "conceptKey", suggestedConcept,
                    "synonym", term.toLowerCase()
                );
            } else {
                // 无法自动匹配，记录待审核
                jdbcTemplate.update(
                    "INSERT INTO industry_concept (industry_code, concept_type, concept_key, concept_aliases, description, source, status) " +
                    "VALUES (?, 'metric', ?, ?, ?, 'user_feedback', 'pending') " +
                    "ON DUPLICATE KEY UPDATE concept_aliases = CONCAT(IFNULL(concept_aliases, ''), '/', ?)",
                    industryCode, term.toLowerCase(), "[\"" + term + "\"]", 
                    "从用户反馈学习: " + question, term
                );
                
                log.info("[IndustryConceptAdmin] 新术语待审核: {} (行业: {})", term, industryCode);
                
                return Map.of(
                    "success", true,
                    "message", String.format("'%s' 已提交审核，管理员确认后将生效", term),
                    "status", "pending_review"
                );
            }
            
        } catch (Exception e) {
            log.error("[IndustryConceptAdmin] 从反馈学习失败", e);
            return Map.of("success", false, "error", e.getMessage());
        }
    }
    
    /**
     * 根据业务类别匹配行业代码
     */
    private String matchIndustryCode(String businessCategory) {
        String category = businessCategory.toLowerCase();
        if (category.contains("order") || category.contains("交易") || category.contains("订单")) {
            return "ecommerce";
        } else if (category.contains("finance") || category.contains("财务")) {
            return "finance";
        } else if (category.contains("medical") || category.contains("医疗")) {
            return "medical";
        } else if (category.contains("education") || category.contains("教育")) {
            return "education";
        } else if (category.contains("manufacturing") || category.contains("制造")) {
            return "manufacturing";
        }
        return null;
    }
    
    /**
     * 根据术语智能推荐概念
     */
    private String suggestConceptByTerm(String term, String industryCode) {
        String lowerTerm = term.toLowerCase();
        
        // 电商行业常见术语映射
        if ("ecommerce".equals(industryCode)) {
            if (lowerTerm.contains("gmv") || lowerTerm.contains("成交") || lowerTerm.contains("营业额")) {
                return "revenue";
            } else if (lowerTerm.contains("uv") || lowerTerm.contains("访客")) {
                return "customer";
            } else if (lowerTerm.contains("pv") || lowerTerm.contains("浏览")) {
                return "product";
            }
        }
        
        // 金融行业
        if ("finance".equals(industryCode)) {
            if (lowerTerm.contains("aUM") || lowerTerm.contains("资产规模")) {
                return "balance";
            }
        }
        
        // 无法匹配，返回null
        return null;
    }
}
