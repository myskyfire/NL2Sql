package com.nl2sql.core.agent.routing;

/**
 * Routing Strategy - defines how the system executes a user request
 *
 * Three execution modes:
 * - DIRECT: SKILL.md Workflow (deterministic orchestration)
 * - PLAN_AND_EXECUTE: LLM generates plan, then execute steps sequentially
 * - REACT: Thought-Action-Observation loop for open-ended exploration
 */
public enum RoutingStrategy {
    /**
     * Direct execution via SKILL.md Workflow
     * Best for: well-defined queries with fixed steps (QUERY/CHART/SUMMARY/CLARIFY)
     * Speed: fastest (1 LLM call for SQL generation)
     */
    DIRECT,

    /**
     * Plan and Execute: LLM generates a structured plan, then execute sequentially
     * Best for: complex but enumerable steps (COMPLEX multi-table queries)
     * Speed: moderate (2 LLM calls: plan + SQL generation)
     */
    PLAN_AND_EXECUTE,

    /**
     * ReAct: Thought-Action-Observation loop
     * Best for: open-ended exploration where next step depends on previous results
     * Speed: slowest (3-5 LLM calls, each round decides next action)
     */
    REACT
}
