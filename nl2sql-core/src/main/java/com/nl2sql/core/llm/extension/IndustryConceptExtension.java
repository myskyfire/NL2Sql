package com.nl2sql.core.llm.extension;

import java.util.List;
import java.util.Map;

/**
 * 行业概念扩展接口
 * 
 * 允许用户自定义术语提取、概念学习等逻辑
 * 默认提供空实现，用户可选择性实现
 */
public interface IndustryConceptExtension {
    
    /**
     * 从用户问题中提取潜在的行业术语
     * 
     * @param question 用户自然语言问题
     * @param datasourceId 数据源ID
     * @return 提取的术语列表，每个术语包含: term, type(entity/metric/dimension), confidence
     */
    default List<Map<String, Object>> extractTerms(String question, Long datasourceId) {
        // 默认空实现，不提取
        return List.of();
    }
    
    /**
     * 验证SQL与问题的语义一致性
     * 
     * @param question 用户问题
     * @param sql 生成的SQL
     * @param datasourceId 数据源ID
     * @return 是否一致
     */
    default boolean validateSemanticConsistency(String question, String sql, Long datasourceId) {
        // 默认认为一致
        return true;
    }
    
    /**
     * 学习成功的查询（高评分时触发）
     * 
     * @param question 用户问题
     * @param sql 执行的SQL
     * @param rating 用户评分
     * @param datasourceId 数据源ID
     */
    default void learnFromSuccess(String question, String sql, Double rating, Long datasourceId) {
        // 默认空实现，不学习
    }
    
    /**
     * 获取概念的推荐同义词
     * 
     * @param conceptKey 概念键（如：revenue）
     * @param industryCode 行业代码
     * @return 推荐的同义词列表
     */
    default List<String> suggestSynonyms(String conceptKey, String industryCode) {
        // 默认空实现
        return List.of();
    }
}
