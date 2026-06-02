package com.nl2sql.core.agent.routing;

import com.nl2sql.core.agent.intent.IntentClassifier;
import com.nl2sql.core.agent.intent.IntentClassifier.IntentClassification;
import com.nl2sql.core.agent.intent.IntentClassifier.IntentType;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

@Slf4j
@Component
public class SkillRouter {

    private final IntentClassifier intentClassifier;

    private Map<IntentType, List<String>> intentToSkillsMap;

    public SkillRouter(IntentClassifier intentClassifier) {
        this.intentClassifier = intentClassifier;
    }

    @PostConstruct
    public void init() {
        intentToSkillsMap = new HashMap<>();

        intentToSkillsMap.put(IntentType.QUERY, List.of("execute_standard_query"));
        intentToSkillsMap.put(IntentType.SUMMARY, List.of("summarize_result"));
        intentToSkillsMap.put(IntentType.CHART, List.of("generate_chart"));
        intentToSkillsMap.put(IntentType.CLARIFY, List.of("clarify_datasource"));
        intentToSkillsMap.put(IntentType.COMPLEX, List.of(
            "execute_standard_query", "summarize_result", "generate_chart"
        ));
        intentToSkillsMap.put(IntentType.EXPLORE, List.of("data_exploration"));
        intentToSkillsMap.put(IntentType.UNKNOWN, List.of(
            "execute_standard_query", "summarize_result", "generate_chart", "clarify_datasource"
        ));

        log.info("[SkillRouter] routing rules initialized, {} rules", intentToSkillsMap.size());
    }

    public RoutingResult route(String userMessage, Long datasourceId) {
        if (userMessage == null || userMessage.trim().isEmpty()) {
            return RoutingResult.planAndExecute(
                List.of("execute_standard_query"),
                "empty message, fallback to plan-and-execute",
                null
            );
        }

        IntentClassification intent = intentClassifier.classify(userMessage);
        log.debug("[SkillRouter] intent classification: type={}, confidence={}",
            intent.getType(), intent.getConfidence());

        return decideRoutingStrategy(intent, datasourceId);
    }

    private RoutingResult decideRoutingStrategy(IntentClassification intent, Long datasourceId) {
        IntentType intentType = intent.getType();
        double confidence = intent.getConfidence();

        // Rule 1: EXPLORE intent -> REACT mode (ReAct loop)
        if (intentType == IntentType.EXPLORE) {
            return RoutingResult.react(
                String.format("EXPLORE intent (confidence=%.2f), using ReAct loop", confidence),
                intent
            );
        }

        // Rule 2: High confidence (>= 0.8) and not UNKNOWN/COMPLEX -> DIRECT (SKILL.md Workflow)
        if (confidence >= 0.8 && intentType != IntentType.UNKNOWN && intentType != IntentType.COMPLEX) {
            List<String> skills = intentToSkillsMap.get(intentType);
            if (skills != null && !skills.isEmpty()) {
                String skill = skills.get(0);

                if ("execute_standard_query".equals(skill) && datasourceId == null) {
                    return RoutingResult.direct(
                        "clarify_datasource",
                        "QUERY intent but missing datasourceId",
                        intent
                    );
                }

                return RoutingResult.direct(
                    skill,
                    String.format("high confidence %s intent (confidence=%.2f)", intentType, confidence),
                    intent
                );
            }
        }

        // Rule 3: COMPLEX intent or medium confidence (0.5-0.8) -> PLAN_AND_EXECUTE
        if (intentType == IntentType.COMPLEX || confidence >= 0.5) {
            List<String> skills = intentToSkillsMap.getOrDefault(
                intentType,
                intentToSkillsMap.get(IntentType.UNKNOWN)
            );

            return RoutingResult.planAndExecute(
                skills,
                String.format("%s intent (confidence=%.2f), using Plan-and-Execute", intentType, confidence),
                intent
            );
        }

        // Rule 4: Low confidence (< 0.5) -> PLAN_AND_EXECUTE as fallback
        return RoutingResult.planAndExecute(
            List.of("execute_standard_query"),
            String.format("low confidence (confidence=%.2f), fallback to Plan-and-Execute", confidence),
            intent
        );
    }

    public List<String> getAllSkills() {
        Set<String> allSkills = new HashSet<>();
        for (List<String> skills : intentToSkillsMap.values()) {
            allSkills.addAll(skills);
        }
        return new ArrayList<>(allSkills);
    }
}
