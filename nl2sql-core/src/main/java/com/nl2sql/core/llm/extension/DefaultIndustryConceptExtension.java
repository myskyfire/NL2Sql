package com.nl2sql.core.llm.extension;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 行业概念扩展示例实现
 * 
 * 这是一个参考实现，展示如何实现扩展接口
 * 实际使用时可以删除或修改此类
 */
@Slf4j
@Component
public class DefaultIndustryConceptExtension implements IndustryConceptExtension {
    
    @Override
    public List<Map<String, Object>> extractTerms(String question, Long datasourceId) {
        log.debug("[DefaultExtension] 术语提取: question={}", question);
        // TODO: 实现NLP术语提取逻辑
        // 示例：使用正则表达式提取大写英文缩写
        return List.of();
    }
    
    @Override
    public boolean validateSemanticConsistency(String question, String sql, Long datasourceId) {
        log.debug("[DefaultExtension] 语义验证: question={}, sql={}", question, sql);
        // TODO: 实现语义一致性验证
        return true;
    }
    
    @Override
    public void learnFromSuccess(String question, String sql, Double rating, Long datasourceId) {
        log.info("[DefaultExtension] 学习成功查询: question={}, rating={}", question, rating);
        // TODO: 实现自动学习逻辑
        // 1. 提取问题中的新术语
        // 2. 分析SQL中的表/字段映射
        // 3. 插入concept_learning_log表（status=pending）
    }
    
    @Override
    public List<String> suggestSynonyms(String conceptKey, String industryCode) {
        log.debug("[DefaultExtension] 推荐同义词: conceptKey={}, industry={}", conceptKey, industryCode);
        // TODO: 基于LLM或词典推荐同义词
        return List.of();
    }
}
