package com.nl2sql.core.agent.routing;

import com.nl2sql.core.agent.intent.IntentClassifier;
import com.nl2sql.core.agent.intent.IntentClassifier.IntentClassification;
import com.nl2sql.core.agent.intent.IntentClassifier.IntentType;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 技能路由器
 * 
 * 根据用户意图自动路由到对应的 Skill，减少 LLM 决策负担：
 * - 高置信度意图 → 直接调用（DIRECT）
 * - 中等置信度 → LLM 辅助决策（LLM_ASSISTED）
 * - 低置信度 → 降级到完整 ReAct（FALLBACK）
 */
@Slf4j
@Component
public class SkillRouter {
    
    private final IntentClassifier intentClassifier;
    
    /**
     * 意图 → Skills 映射表
     * Key: 意图类型
     * Value: 推荐的 Skills 列表
     */
    private Map<IntentType, List<String>> intentToSkillsMap;
    
    public SkillRouter(IntentClassifier intentClassifier) {
        this.intentClassifier = intentClassifier;
    }
    
    @PostConstruct
    public void init() {
        // 初始化路由规则
        intentToSkillsMap = new HashMap<>();
        
        // QUERY 意图 → 标准查询 Skill
        intentToSkillsMap.put(IntentType.QUERY, List.of(
            "execute_standard_query"
        ));
        
        // SUMMARY 意图 → AI 总结 Skill
        intentToSkillsMap.put(IntentType.SUMMARY, List.of(
            "summarize_result"
        ));
        
        // CHART 意图 → 图表生成 Skill
        intentToSkillsMap.put(IntentType.CHART, List.of(
            "generate_chart"
        ));
        
        // CLARIFY 意图 → 数据源澄清 Tool
        intentToSkillsMap.put(IntentType.CLARIFY, List.of(
            "clarify_datasource"
        ));
        
        // UNKNOWN 意图 → 所有 Skills（LLM 辅助决策）
        intentToSkillsMap.put(IntentType.UNKNOWN, List.of(
            "execute_standard_query",
            "summarize_result",
            "generate_chart",
            "clarify_datasource"
        ));
        
        log.info("[SkillRouter] 路由规则初始化完成，共 {} 条规则", intentToSkillsMap.size());
    }
    
    /**
     * 根据用户消息路由到对应的 Skill
     * 
     * @param userMessage 用户原始消息
     * @param datasourceId 数据源 ID（可选）
     * @return 路由结果
     */
    public RoutingResult route(String userMessage, Long datasourceId) {
        if (userMessage == null || userMessage.trim().isEmpty()) {
            return RoutingResult.fallback("消息为空", null);
        }
        
        // 步骤 1: 意图分类
        IntentClassification intent = intentClassifier.classify(userMessage);
        log.debug("[SkillRouter] 意图分类结果: type={}, confidence={}", 
            intent.getType(), intent.getConfidence());
        
        // 步骤 2: 根据意图和置信度决定路由策略
        return decideRoutingStrategy(intent, datasourceId);
    }
    
    /**
     * 决定路由策略
     */
    private RoutingResult decideRoutingStrategy(IntentClassification intent, Long datasourceId) {
        IntentType intentType = intent.getType();
        double confidence = intent.getConfidence();
        
        // 规则 1: 高置信度（>= 0.8）且非 UNKNOWN → 直接路由
        if (confidence >= 0.8 && intentType != IntentType.UNKNOWN) {
            List<String> skills = intentToSkillsMap.get(intentType);
            if (skills != null && !skills.isEmpty()) {
                String skill = skills.get(0);
                
                // 特殊处理：如果缺少 datasourceId，需要澄清
                if ("execute_standard_query".equals(skill) && datasourceId == null) {
                    log.info("[SkillRouter] 检测到 QUERY 意图但缺少 datasourceId，转为澄清");
                    return RoutingResult.direct(
                        "clarify_datasource",
                        "QUERY 意图但缺少数据源ID，先澄清",
                        intent
                    );
                }
                
                return RoutingResult.direct(
                    skill,
                    String.format("高置信度 %s 意图 (confidence=%.2f)", intentType, confidence),
                    intent
                );
            }
        }
        
        // 规则 2: 中等置信度（0.5-0.8）→ LLM 辅助决策
        if (confidence >= 0.5) {
            List<String> skills = intentToSkillsMap.getOrDefault(
                intentType, 
                intentToSkillsMap.get(IntentType.UNKNOWN)
            );
            
            return RoutingResult.llmAssisted(
                skills,
                String.format("中等置信度 %s 意图 (confidence=%.2f)，LLM 辅助决策", intentType, confidence),
                intent
            );
        }
        
        // 规则 3: 低置信度（< 0.5）→ 降级到完整 ReAct
        return RoutingResult.fallback(
            String.format("低置信度意图 (confidence=%.2f)，降级到完整 ReAct", confidence),
            intent
        );
    }
    
    /**
     * 获取所有已注册的 Skills（用于 FALLBACK 场景）
     */
    public List<String> getAllSkills() {
        Set<String> allSkills = new HashSet<>();
        for (List<String> skills : intentToSkillsMap.values()) {
            allSkills.addAll(skills);
        }
        return new ArrayList<>(allSkills);
    }
}
