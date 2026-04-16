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
     * 获取待审核的学习记录
     */
    @GetMapping("/learning/pending")
    public Map<String, Object> getPendingLearnings() {
        List<Map<String, Object>> learnings = jdbcTemplate.queryForList(
            "SELECT * FROM concept_learning_log WHERE status = 'pending' ORDER BY created_at DESC LIMIT 50"
        );
        
        return Map.of("success", true, "data", learnings, "count", learnings.size());
    }
    
    /**
     * 审核学习记录
     */
    @PostMapping("/learning/{id}/review")
    public Map<String, Object> reviewLearning(@PathVariable Long id,
                                              @RequestBody Map<String, Object> request) {
        String action = (String) request.get("action"); // approve/reject
        String reviewer = (String) request.get("reviewer");
        
        String newStatus = "approve".equals(action) ? "approved" : "rejected";
        
        jdbcTemplate.update(
            "UPDATE concept_learning_log SET status = ?, reviewed_by = ?, reviewed_at = NOW() WHERE id = ?",
            newStatus, reviewer, id
        );
        
        // 如果通过，插入到正式概念表
        if ("approve".equals(action)) {
            Map<String, Object> learning = jdbcTemplate.queryForMap(
                "SELECT * FROM concept_learning_log WHERE id = ?", id
            );
            
            jdbcTemplate.update(
                "INSERT INTO industry_concept (industry_code, concept_type, concept_key, concept_aliases, description, source, status) " +
                "VALUES (?, ?, ?, ?, ?, 'rag_learned', 'approved') " +
                "ON DUPLICATE KEY UPDATE concept_aliases = VALUES(concept_aliases)",
                learning.get("industry_code"),
                learning.get("concept_type"),
                learning.get("concept_key"),
                learning.get("extracted_aliases"),
                learning.get("source_question")
            );
        }
        
        return Map.of("success", true, "message", "审核完成");
    }
}
