package com.nl2sql.core.agent.routing;

/**
 * 路由策略枚举
 * 
 * 定义 Skill 路由的三种策略：
 * - DIRECT: 直接调用，无需 LLM
 * - LLM_ASSISTED: LLM 辅助决策（缩小范围）
 * - FALLBACK: 降级到完整 ReAct 流程
 */
public enum RoutingStrategy {
    /**
     * 直接调用：无需 LLM，直接执行指定 Skill
     * 适用场景：意图明确（QUERY/SUMMARY/CHART/CLARIFY）且置信度高
     * 优势：响应速度快（<100ms），Token 消耗为 0
     */
    DIRECT,
    
    /**
     * LLM 辅助决策：缩小工具范围，由 LLM 最终选择
     * 适用场景：意图不明确，但可缩小范围（如 UNKNOWN 意图）
     * 优势：减少 LLM 决策空间，降低 Token 消耗 50-70%
     */
    LLM_ASSISTED,
    
    /**
     * 降级到完整 ReAct 流程
     * 适用场景：未知意图、路由失败或低置信度
     * 优势：保证系统鲁棒性，避免错误路由
     */
    FALLBACK
}
