package com.nl2sql.core.agent.routing;

import com.nl2sql.core.agent.intent.IntentClassifier.IntentClassification;
import lombok.Data;

import java.util.List;

/**
 * 路由结果
 * 
 * 封装 SkillRouter 的路由决策结果，包含：
 * - 路由策略（DIRECT/LLM_ASSISTED/FALLBACK）
 * - 推荐的 Skills 列表
 * - 路由原因（用于日志和调试）
 * - 原始意图分类和置信度
 */
@Data
public class RoutingResult {
    
    /**
     * 路由策略
     */
    private RoutingStrategy strategy;
    
    /**
     * 推荐的 Skills 列表
     * - DIRECT: 包含 1 个 Skill，直接调用
     * - LLM_ASSISTED: 包含 2-5 个 Skills，供 LLM 选择
     * - FALLBACK: 空列表，使用所有 Tools
     */
    private List<String> recommendedSkills;
    
    /**
     * 路由原因（用于日志和调试）
     */
    private String reason;
    
    /**
     * 原始意图分类
     */
    private IntentClassification intent;
    
    /**
     * 置信度（0-1）
     */
    private double confidence;
    
    // ==================== 便捷工厂方法 ====================
    
    /**
     * 创建直接路由结果
     * 
     * @param skill 推荐的 Skill 名称
     * @param reason 路由原因
     * @param intent 原始意图分类
     * @return 路由结果
     */
    public static RoutingResult direct(String skill, String reason, IntentClassification intent) {
        RoutingResult result = new RoutingResult();
        result.strategy = RoutingStrategy.DIRECT;
        result.recommendedSkills = List.of(skill);
        result.reason = reason;
        result.intent = intent;
        result.confidence = intent != null ? intent.getConfidence() : 0.0;
        return result;
    }
    
    /**
     * 创建 LLM 辅助决策路由结果
     * 
     * @param skills 推荐的 Skills 列表
     * @param reason 路由原因
     * @param intent 原始意图分类
     * @return 路由结果
     */
    public static RoutingResult llmAssisted(List<String> skills, String reason, IntentClassification intent) {
        RoutingResult result = new RoutingResult();
        result.strategy = RoutingStrategy.LLM_ASSISTED;
        result.recommendedSkills = skills;
        result.reason = reason;
        result.intent = intent;
        result.confidence = intent != null ? intent.getConfidence() : 0.0;
        return result;
    }
    
    /**
     * 创建降级路由结果
     * 
     * @param reason 路由原因
     * @param intent 原始意图分类
     * @return 路由结果
     */
    public static RoutingResult fallback(String reason, IntentClassification intent) {
        RoutingResult result = new RoutingResult();
        result.strategy = RoutingStrategy.FALLBACK;
        result.recommendedSkills = List.of();
        result.reason = reason;
        result.intent = intent;
        result.confidence = 0.0;
        return result;
    }
}
