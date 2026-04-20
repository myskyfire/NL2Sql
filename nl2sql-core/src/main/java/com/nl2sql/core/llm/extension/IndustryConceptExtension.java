package com.nl2sql.core.llm.extension;

import java.util.List;
import java.util.Map;

/**
 * 行业概念扩展接口
 * 
 * 允许用户自定义术语提取、概念学习等逻辑
 * 默认提供空实现，用户可选择性实现
 * 
 * 【使用示例】
 * 1. 创建实现类：public class FinanceConceptExtension implements IndustryConceptExtension
 * 2. 添加 @Component 注解
 * 3. 重写需要的方法
 */
public interface IndustryConceptExtension {
    
    // ==================== Level 1: 术语理解增强 ====================
    
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
    
    // ==================== Level 2: SQL生成干预 ====================
    
    /**
     * ✅ 新增：在LLM生成SQL前，注入行业特定的提示词
     * 
     * 适用场景：
     * - 财务：提醒"收入=主营业务收入贷方-红字冲销"
     * - 电商：提醒"GMV=成交金额，不含退款"
     * 
     * @param originalPrompt 原始提示词
     * @param question 用户问题
     * @param datasourceId 数据源ID
     * @return 增强后的提示词（返回null表示不修改）
     */
    default String enhancePromptBeforeGeneration(String originalPrompt, String question, Long datasourceId) {
        // 默认不修改
        return null;
    }
    
    /**
     * ✅ 新增：在LLM生成SQL后，进行行业特定的修正
     * 
     * 适用场景：
     * - 财务：自动添加"WHERE status != 'RED'"剔除红字
     * - 合规：自动添加"AND department_id IN (...)"限制数据范围
     * 
     * @param generatedSql LLM生成的SQL
     * @param question 用户问题
     * @param datasourceId 数据源ID
     * @return 修正后的SQL（返回null表示不修改）
     */
    default String correctSqlAfterGeneration(String generatedSql, String question, Long datasourceId) {
        // 默认不修改
        return null;
    }
    
    // ==================== Level 3: SQL执行校验 ====================
    
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
     * ✅ 新增：在执行SQL前，进行行业特定的安全检查
     * 
     * 适用场景：
     * - 财务：禁止查询明细凭证，只允许汇总
     * - 合规：禁止跨年查询、跨公司查询
     * 
     * @param sql 待执行的SQL
     * @param question 用户问题
     * @param datasourceId 数据源ID
     * @param userId 当前用户ID
     * @return 校验结果：success=true表示允许执行，message为错误提示
     */
    default Map<String, Object> validateBeforeExecution(String sql, String question, Long datasourceId, String userId) {
        Map<String, Object> result = Map.of(
            "success", true,
            "message", "允许执行"
        );
        return result;
    }
    
    // ==================== Level 4: 学习与优化 ====================
    
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
     * ✅ 新增：获取行业特定的指标计算模板
     * 
     * 适用场景：
     * - 财务：应收账款余额 = 应收科目余额 - 坏账准备 + 预收借方余额
     * - 电商：复购率 = COUNT(DISTINCT user_id HAVING COUNT(order_id) > 1) / COUNT(DISTINCT user_id)
     * 
     * @param metricName 指标名称（如："应收账款余额"、"复购率"）
     * @param datasourceId 数据源ID
     * @return 计算模板SQL（返回null表示无模板）
     */
    default String getMetricTemplate(String metricName, Long datasourceId) {
        // 默认无模板
        return null;
    }
}
