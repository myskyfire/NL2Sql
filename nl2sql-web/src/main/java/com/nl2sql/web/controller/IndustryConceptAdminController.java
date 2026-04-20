package com.nl2sql.web.controller;

import com.nl2sql.web.service.ConceptRecommendationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Stream;

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
    
    @Autowired
    private ConceptRecommendationService conceptRecommendationService;
    
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
     * ✅ 新增：获取单个概念详情
     */
    @GetMapping("/concepts/{id}")
    public Map<String, Object> getConcept(@PathVariable Long id) {
        try {
            Map<String, Object> concept = jdbcTemplate.queryForMap(
                "SELECT * FROM industry_concept WHERE id = ?",
                id
            );
            
            return Map.of("success", true, "data", concept);
        } catch (Exception e) {
            log.error("[IndustryConceptAdmin] 获取概念详情失败", e);
            return Map.of("success", false, "error", e.getMessage());
        }
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
     * ✅ 新增：批量审核概念
     */
    @PostMapping("/concepts/batch-approve")
    public Map<String, Object> batchApprove(@RequestBody Map<String, Object> request) {
        try {
            List<Long> ids = (List<Long>) request.get("ids");
            String action = (String) request.get("action"); // approve/reject
            
            if (ids == null || ids.isEmpty()) {
                return Map.of("success", false, "error", "请选择要审核的概念");
            }
            
            String newStatus = "approve".equals(action) ? "approved" : "rejected";
            String placeholders = String.join(",", ids.stream().map(id -> "?").toArray(String[]::new));
            
            int updated = jdbcTemplate.update(
                "UPDATE industry_concept SET status = ? WHERE id IN (" + placeholders + ")",
                Stream.concat(Stream.of(newStatus), ids.stream()).toArray()
            );
            
            log.info("[IndustryConceptAdmin] 批量审核: {} 条概念, 操作: {}", updated, action);
            
            return Map.of("success", true, "message", String.format("已%s %d 条概念", 
                "approve".equals(action) ? "通过" : "拒绝", updated));
            
        } catch (Exception e) {
            log.error("[IndustryConceptAdmin] 批量审核失败", e);
            return Map.of("success", false, "error", e.getMessage());
        }
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
                    industryCode = conceptRecommendationService.matchIndustryCode(businessCategory);
                }
            }
            
            if (industryCode == null) {
                return Map.of("success", false, "error", "无法确定数据源所属行业，请先配置行业");
            }
            
            // 2. 如果未指定目标概念，尝试智能匹配
            if (suggestedConcept == null || suggestedConcept.trim().isEmpty()) {
                suggestedConcept = conceptRecommendationService.suggestConceptByTerm(term, industryCode);
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
}
