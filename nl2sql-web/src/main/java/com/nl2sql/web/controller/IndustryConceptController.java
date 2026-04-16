package com.nl2sql.web.controller;

import com.nl2sql.core.llm.IndustryConceptDictionary;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 行业概念管理 API
 * 
 * 提供行业概念库的查询和管理功能
 */
@Slf4j
@RestController
@RequestMapping("/api/industry-concepts")
public class IndustryConceptController {
    
    @Autowired
    private IndustryConceptDictionary industryConceptDictionary;
    
    /**
     * 获取所有已注册的行业列表
     */
    @GetMapping("/industries")
    public Map<String, Object> getAllIndustries() {
        List<IndustryConceptDictionary.IndustryInfo> industries = 
            industryConceptDictionary.getAllIndustries();
        
        return Map.of(
            "success", true,
            "data", industries,
            "count", industries.size()
        );
    }
    
    /**
     * 获取指定数据源的行业概念详情
     */
    @GetMapping("/datasource/{datasourceId}")
    public Map<String, Object> getDatasourceConcepts(@PathVariable Long datasourceId) {
        IndustryConceptDictionary.IndustryConcepts concepts = 
            industryConceptDictionary.getConceptsByDatasource(datasourceId);
        
        return Map.of(
            "success", true,
            "datasourceId", datasourceId,
            "industry", concepts.getIndustryName(),
            "industryCode", concepts.getIndustryCode(),
            "businessEntities", concepts.getBusinessEntities(),
            "metrics", concepts.getMetrics(),
            "dimensions", concepts.getDimensions(),
            "tableRoles", concepts.getTableRoles()
        );
    }
}
