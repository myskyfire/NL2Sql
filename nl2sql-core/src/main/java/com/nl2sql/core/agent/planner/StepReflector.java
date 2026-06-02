package com.nl2sql.core.agent.planner;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

@Slf4j
public class StepReflector {

    public enum Decision {
        CONTINUE,
        REPLAN,
        TERMINATE
    }

    @Data
    public static class ReflectionResult {
        private Decision decision;
        private String reason;
        private List<String> suggestions;

        public static ReflectionResult cont(String reason) {
            ReflectionResult r = new ReflectionResult();
            r.decision = Decision.CONTINUE;
            r.reason = reason;
            return r;
        }

        public static ReflectionResult replan(String reason, List<String> suggestions) {
            ReflectionResult r = new ReflectionResult();
            r.decision = Decision.REPLAN;
            r.reason = reason;
            r.suggestions = suggestions != null ? suggestions : Collections.emptyList();
            return r;
        }

        public static ReflectionResult terminate(String reason) {
            ReflectionResult r = new ReflectionResult();
            r.decision = Decision.TERMINATE;
            r.reason = reason;
            return r;
        }
    }

    private static final int MAX_REPLAN_COUNT = 2;

    private int replanCount = 0;

    public ReflectionResult reflect(
            QueryPlan.SqlStep step,
            int stepIndex,
            int totalSteps,
            Map<String, Object> execResult,
            QueryPlan plan) {

        if (execResult == null) {
            log.warn("[StepReflector] step {} execResult is null, REPLAN", stepIndex + 1);
            return ReflectionResult.replan(
                "Step " + (stepIndex + 1) + " returned null result",
                List.of("Check if SQL generation is working correctly")
            );
        }

        boolean execSuccess = isTrue(execResult, "data.success");
        int rowCount = getInt(execResult, "data.rowCount");
        double executionTime = getDouble(execResult, "data.executionTime");
        String error = getString(execResult, "data.error");

        if (!execSuccess) {
            return handleExecutionFailure(step, stepIndex, error, plan);
        }

        if (rowCount == 0) {
            return handleEmptyResult(step, stepIndex, plan);
        }

        if (executionTime > 30000) {
            log.warn("[StepReflector] step {} took {}ms (>30s), suggesting optimization", stepIndex + 1, (long) executionTime);
        }

        if (rowCount > 50000 && isChartPie(plan)) {
            return ReflectionResult.replan(
                "Result has " + rowCount + " rows, PIE chart is not suitable",
                List.of("Change chart type from PIE to BAR or LINE", "Consider adding LIMIT or GROUP BY")
            );
        }

        if (stepIndex == 0 && totalSteps > 1) {
            log.info("[StepReflector] step 1/{} succeeded with {} rows, continuing", totalSteps, rowCount);
        }

        return ReflectionResult.cont("Step " + (stepIndex + 1) + " executed successfully, " + rowCount + " rows returned");
    }

    private ReflectionResult handleExecutionFailure(
            QueryPlan.SqlStep step, int stepIndex, String error, QueryPlan plan) {

        log.warn("[StepReflector] step {} execution failed: {}", stepIndex + 1, error);

        if (stepIndex == 0) {
            if (replanCount < MAX_REPLAN_COUNT) {
                replanCount++;
                return ReflectionResult.replan(
                    "First step failed: " + (error != null ? error : "Unknown error"),
                    List.of("Simplify the query", "Check if the table and columns exist")
                );
            }
            return ReflectionResult.terminate("First step failed after " + replanCount + " replan attempts");
        }

        if (replanCount < MAX_REPLAN_COUNT) {
            replanCount++;
            return ReflectionResult.replan(
                "Step " + (stepIndex + 1) + " failed: " + (error != null ? error : "Unknown error"),
                List.of("Skip this step and replan remaining steps")
            );
        }

        return ReflectionResult.terminate("Step " + (stepIndex + 1) + " failed, max replan attempts reached");
    }

    private ReflectionResult handleEmptyResult(
            QueryPlan.SqlStep step, int stepIndex, QueryPlan plan) {

        log.warn("[StepReflector] step {} returned 0 rows", stepIndex + 1);

        if (stepIndex == 0 && replanCount < MAX_REPLAN_COUNT) {
            replanCount++;
            return ReflectionResult.replan(
                "First step returned 0 rows, query conditions may be too strict",
                List.of("Widen the time range", "Remove or relax WHERE conditions", "Check if the table has data")
            );
        }

        if (stepIndex > 0) {
            return ReflectionResult.cont("Step " + (stepIndex + 1) + " returned 0 rows, but previous steps succeeded, continuing");
        }

        return ReflectionResult.terminate("Query returned 0 rows after " + replanCount + " replan attempts");
    }

    private boolean isChartPie(QueryPlan plan) {
        return plan.getChart() != null
            && plan.getChart().isNeeded()
            && plan.getChart().getType() == QueryPlan.ChartPlan.ChartType.PIE;
    }

    public int getReplanCount() {
        return replanCount;
    }

    public void resetReplanCount() {
        this.replanCount = 0;
    }

    @SuppressWarnings("unchecked")
    private Object getNestedValue(Map<String, Object> map, String path) {
        if (map == null || path == null) return null;
        String[] parts = path.split("\\.");
        Object current = map;
        for (String part : parts) {
            if (current instanceof Map) {
                current = ((Map<String, Object>) current).get(part);
            } else {
                return null;
            }
        }
        return current;
    }

    private boolean isTrue(Map<String, Object> map, String path) {
        Object val = getNestedValue(map, path);
        if (val == null) return false;
        if (val instanceof Boolean) return (Boolean) val;
        if (val instanceof String) return "true".equalsIgnoreCase((String) val);
        return false;
    }

    private String getString(Map<String, Object> map, String path) {
        Object val = getNestedValue(map, path);
        return val != null ? val.toString() : null;
    }

    private int getInt(Map<String, Object> map, String path) {
        Object val = getNestedValue(map, path);
        if (val instanceof Number) return ((Number) val).intValue();
        if (val instanceof String) {
            try { return Integer.parseInt((String) val); } catch (Exception e) { return 0; }
        }
        return 0;
    }

    private double getDouble(Map<String, Object> map, String path) {
        Object val = getNestedValue(map, path);
        if (val instanceof Number) return ((Number) val).doubleValue();
        if (val instanceof String) {
            try { return Double.parseDouble((String) val); } catch (Exception e) { return 0.0; }
        }
        return 0.0;
    }
}
