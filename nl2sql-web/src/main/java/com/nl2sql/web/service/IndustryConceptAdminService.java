package com.nl2sql.web.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nl2sql.metadata.mapper.IndustryConceptAdminMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 行业概念管理服务
 * 
 * 职责：封装行业概念的业务逻辑，提供统一的接口给 Controller 调用
 */
@Slf4j
@Service
public class IndustryConceptAdminService {
    
    @Autowired
    private JdbcTemplate jdbcTemplate;
    
    @Autowired
    private IndustryConceptAdminMapper adminMapper;
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    /**
     * 获取所有行业模板
     */
    public List<Map<String, Object>> getTemplates() {
        return jdbcTemplate.queryForList("SELECT * FROM industry_template ORDER BY id");
    }
    
    /**
     * 获取指定行业的概念列表
     */
    public List<Map<String, Object>> getConcepts(String industryCode, String type, String status) {
        StringBuilder sql = new StringBuilder("SELECT * FROM industry_concept WHERE industry_code = ?");
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
        
        return jdbcTemplate.queryForList(sql.toString(), params.toArray());
    }
    
    /**
     * 获取单个概念详情
     */
    public Map<String, Object> getConceptById(Long id) {
        return jdbcTemplate.queryForMap("SELECT * FROM industry_concept WHERE id = ?", id);
    }
    
    /**
     * 添加新概念
     */
    public void addConcept(String industryCode, String type, String key, 
                          List<String> aliases, String description) throws Exception {
        String aliasesJson = objectMapper.writeValueAsString(aliases);
        
        jdbcTemplate.update(
            "INSERT INTO industry_concept (industry_code, concept_type, concept_key, concept_aliases, description, source, status) " +
            "VALUES (?, ?, ?, ?, ?, 'manual', 'approved')",
            industryCode, type, key, aliasesJson, description
        );
        
        log.info("[IndustryConceptAdmin] 添加概念: {}.{} = {}", industryCode, key, aliases);
    }
    
    /**
     * 更新概念
     */
    public void updateConcept(Long id, List<String> aliases, String description) throws Exception {
        String aliasesJson = objectMapper.writeValueAsString(aliases);
        
        jdbcTemplate.update(
            "UPDATE industry_concept SET concept_aliases = ?, description = ? WHERE id = ?",
            aliasesJson, description, id
        );
    }
    
    /**
     * 删除概念
     */
    public void deleteConcept(Long id) {
        adminMapper.deleteConceptById(id);
        log.info("[IndustryConceptAdmin] 删除概念: id={}", id);
    }
    
    /**
     * 批量审核概念
     */
    public int batchApprove(List<Long> ids, String action) {
        String newStatus = "approve".equals(action) ? "approved" : "rejected";
        int updated = adminMapper.batchUpdateStatus(newStatus, ids);
        log.info("[IndustryConceptAdmin] 批量审核: {} 条概念, 操作: {}", updated, action);
        return updated;
    }
    
    /**
     * 关联数据源到行业
     */
    public void linkDatasource(Long datasourceId, String industryCode, Integer priority) {
        adminMapper.upsertDatasourceIndustry(datasourceId, industryCode, priority);
        log.info("[IndustryConceptAdmin] 数据源{}关联到行业{}", datasourceId, industryCode);
    }
    
    /**
     * 查询数据源的行业代码
     */
    public String getIndustryCodeByDatasourceId(Long datasourceId) {
        return adminMapper.selectIndustryCodeByDatasourceId(datasourceId);
    }
    
    /**
     * 查询数据源的业务类别
     */
    public String getBusinessCategoryByDatasourceId(Long datasourceId) {
        return adminMapper.selectBusinessCategoryByDatasourceId(datasourceId);
    }
    
    /**
     * 从反馈学习 - 插入同义词关系或待审核概念
     */
    public void learnFromFeedback(String industryCode, String term, String suggestedConcept, String question) {
        if (suggestedConcept != null) {
            // 插入同义词关系
            adminMapper.insertConceptRelationIgnore(industryCode, suggestedConcept, term.toLowerCase());
            log.info("[IndustryConceptAdmin] 学习同义词: {} -> {}", term, suggestedConcept);
        } else {
            // 插入待审核概念
            adminMapper.insertPendingConcept(
                industryCode, 
                term.toLowerCase(), 
                "[\"" + term + "\"]",
                "从用户反馈学习: " + question,
                term
            );
            log.info("[IndustryConceptAdmin] 添加待审核概念: {}", term);
        }
    }
    
    /**
     * 查询已审核的行业概念（entity/metric）
     */
    public List<Map<String, Object>> getApprovedConcepts(String industryCode) {
        return adminMapper.selectApprovedConceptsByType(industryCode);
    }
}
