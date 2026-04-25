package com.nl2sql.common.util;

import com.hankcs.hanlp.HanLP;
import com.hankcs.hanlp.seg.common.Term;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 中文命名实体识别工具
 * 使用HanLP识别人名、地名、机构名等实体
 */
@Slf4j
public class EntityExtractor {
    
    /**
     * 从查询文本中提取人名
     * @param query 用户查询
     * @return 人名列表
     */
    public static List<String> extractPersons(String query) {
        if (query == null || query.isEmpty()) {
            return Collections.emptyList();
        }
        
        try {
            List<Term> terms = HanLP.segment(query);
            return terms.stream()
                .filter(term -> "nr".equals(term.nature.toString())) // nr = 人名
                .map(term -> term.word)
                .filter(name -> name.length() >= 2 && name.length() <= 4) // 过滤2-4字姓名
                .distinct()
                .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("[EntityExtractor] 人名提取失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }
    
    /**
     * 从查询文本中提取地名
     * @param query 用户查询
     * @return 地名列表
     */
    public static List<String> extractLocations(String query) {
        if (query == null || query.isEmpty()) {
            return Collections.emptyList();
        }
        
        try {
            List<Term> terms = HanLP.segment(query);
            return terms.stream()
                .filter(term -> "ns".equals(term.nature.toString())) // ns = 地名
                .map(term -> term.word)
                .distinct()
                .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("[EntityExtractor] 地名提取失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }
    
    /**
     * 从查询文本中提取机构名
     * @param query 用户查询
     * @return 机构名列表
     */
    public static List<String> extractOrganizations(String query) {
        if (query == null || query.isEmpty()) {
            return Collections.emptyList();
        }
        
        try {
            List<Term> terms = HanLP.segment(query);
            return terms.stream()
                .filter(term -> "nt".equals(term.nature.toString())) // nt = 机构名
                .map(term -> term.word)
                .distinct()
                .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("[EntityExtractor] 机构名提取失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }
    
    /**
     * 提取所有类型的实体
     * @param query 用户查询
     * @return Map<实体类型, 实体列表>
     */
    public static Map<String, List<String>> extractAllEntities(String query) {
        Map<String, List<String>> entities = new HashMap<>();
        entities.put("persons", extractPersons(query));
        entities.put("locations", extractLocations(query));
        entities.put("organizations", extractOrganizations(query));
        return entities;
    }
}
