package com.nl2sql.core.agent.routing;

import com.nl2sql.core.agent.intent.IntentClassifier.IntentClassification;
import lombok.Data;

import java.util.List;

/**
 * Routing Result - encapsulates the routing decision from SkillRouter
 *
 * Three strategies:
 * - DIRECT: SKILL.md Workflow, 1 skill, no LLM routing needed
 * - PLAN_AND_EXECUTE: PlannerAgent generates QueryPlan, WorkflowEngine executes
 * - REACT: ReActAgent handles open-ended exploration
 */
@Data
public class RoutingResult {

    private RoutingStrategy strategy;

    private List<String> recommendedSkills;

    private String reason;

    private IntentClassification intent;

    private double confidence;

    public static RoutingResult direct(String skill, String reason, IntentClassification intent) {
        RoutingResult result = new RoutingResult();
        result.strategy = RoutingStrategy.DIRECT;
        result.recommendedSkills = List.of(skill);
        result.reason = reason;
        result.intent = intent;
        result.confidence = intent != null ? intent.getConfidence() : 0.0;
        return result;
    }

    public static RoutingResult planAndExecute(List<String> skills, String reason, IntentClassification intent) {
        RoutingResult result = new RoutingResult();
        result.strategy = RoutingStrategy.PLAN_AND_EXECUTE;
        result.recommendedSkills = skills;
        result.reason = reason;
        result.intent = intent;
        result.confidence = intent != null ? intent.getConfidence() : 0.0;
        return result;
    }

    public static RoutingResult react(String reason, IntentClassification intent) {
        RoutingResult result = new RoutingResult();
        result.strategy = RoutingStrategy.REACT;
        result.recommendedSkills = List.of("data_exploration");
        result.reason = reason;
        result.intent = intent;
        result.confidence = intent != null ? intent.getConfidence() : 0.0;
        return result;
    }

    /**
     * @Deprecated Use planAndExecute() instead
     */
    @Deprecated
    public static RoutingResult llmAssisted(List<String> skills, String reason, IntentClassification intent) {
        return planAndExecute(skills, reason, intent);
    }

    /**
     * @Deprecated Use planAndExecute() or react() instead
     */
    @Deprecated
    public static RoutingResult fallback(String reason, IntentClassification intent) {
        RoutingResult result = new RoutingResult();
        result.strategy = RoutingStrategy.PLAN_AND_EXECUTE;
        result.recommendedSkills = List.of("execute_standard_query");
        result.reason = reason;
        result.intent = intent;
        result.confidence = 0.0;
        return result;
    }
}
