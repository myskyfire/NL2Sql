package com.nl2sql.core.agent.planner;

import com.nl2sql.core.agent.tools.ToolRegistry;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

@Slf4j
public class PlanValidator {

    private final ToolRegistry toolRegistry;

    public PlanValidator(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    @Data
    public static class ValidationResult {
        private boolean valid;
        private boolean modified;
        private List<String> warnings = new ArrayList<>();
        private List<String> corrections = new ArrayList<>();
        private String rejectReason;

        public static ValidationResult pass() {
            ValidationResult r = new ValidationResult();
            r.valid = true;
            return r;
        }

        public static ValidationResult passWithCorrections(List<String> corrections) {
            ValidationResult r = new ValidationResult();
            r.valid = true;
            r.modified = true;
            r.corrections = corrections;
            return r;
        }

        public static ValidationResult reject(String reason) {
            ValidationResult r = new ValidationResult();
            r.valid = false;
            r.rejectReason = reason;
            return r;
        }
    }

    public ValidationResult validate(QueryPlan plan, Long datasourceId) {
        if (plan == null) {
            return ValidationResult.reject("QueryPlan is null");
        }

        List<String> warnings = new ArrayList<>();
        List<String> corrections = new ArrayList<>();
        boolean modified = false;

        ValidationResult stepsResult = validateSqlSteps(plan, warnings, corrections);
        if (!stepsResult.isValid()) {
            return stepsResult;
        }
        if (stepsResult.isModified()) {
            modified = true;
        }

        ValidationResult tablesResult = validateTables(plan, datasourceId, warnings, corrections);
        if (tablesResult.isModified()) {
            modified = true;
        }

        ValidationResult chartResult = validateChart(plan, warnings, corrections);
        if (chartResult.isModified()) {
            modified = true;
        }

        ValidationResult complexityResult = validateComplexity(plan, warnings, corrections);
        if (complexityResult.isModified()) {
            modified = true;
        }

        if (!warnings.isEmpty()) {
            log.warn("[PlanValidator] warnings: {}", warnings);
        }
        if (!corrections.isEmpty()) {
            log.info("[PlanValidator] corrections applied: {}", corrections);
        }

        if (modified) {
            ValidationResult result = ValidationResult.passWithCorrections(corrections);
            result.setWarnings(warnings);
            return result;
        }

        ValidationResult pass = ValidationResult.pass();
        pass.setWarnings(warnings);
        return pass;
    }

    private ValidationResult validateSqlSteps(QueryPlan plan, List<String> warnings, List<String> corrections) {
        List<QueryPlan.SqlStep> steps = plan.getSqlSteps();

        if (steps == null || steps.isEmpty()) {
            warnings.add("sqlSteps is empty, creating default single step");
            QueryPlan.SqlStep defaultStep = new QueryPlan.SqlStep();
            defaultStep.setOrder(1);
            defaultStep.setDescription(plan.getUserQuestion());
            plan.setSqlSteps(List.of(defaultStep));
            corrections.add("Added default single step with userQuestion");
            return ValidationResult.passWithCorrections(corrections);
        }

        if (steps.size() > 5) {
            warnings.add("sqlSteps count=" + steps.size() + " > 5, likely over-decomposed");
            QueryPlan.SqlStep merged = new QueryPlan.SqlStep();
            merged.setOrder(1);
            merged.setDescription(plan.getUserQuestion());
            plan.setSqlSteps(List.of(merged));
            corrections.add("Merged " + steps.size() + " steps into 1 (userQuestion as description)");
            return ValidationResult.passWithCorrections(corrections);
        }

        for (int i = 0; i < steps.size(); i++) {
            QueryPlan.SqlStep step = steps.get(i);
            if (step.getDescription() == null || step.getDescription().trim().isEmpty()) {
                warnings.add("Step " + (i + 1) + " has empty description, using userQuestion");
                step.setDescription(plan.getUserQuestion());
                corrections.add("Step " + (i + 1) + " description set to userQuestion");
            }
            if (step.getOrder() != i + 1) {
                step.setOrder(i + 1);
            }
        }

        if (!corrections.isEmpty()) {
            return ValidationResult.passWithCorrections(corrections);
        }
        return ValidationResult.pass();
    }

    private ValidationResult validateTables(QueryPlan plan, Long datasourceId,
                                             List<String> warnings, List<String> corrections) {
        List<QueryPlan.TablePlan> tables = plan.getTables();

        if (tables == null || tables.isEmpty()) {
            return ValidationResult.pass();
        }

        Set<String> validTables = retrieveValidTableNames(datasourceId);
        if (validTables == null || validTables.isEmpty()) {
            warnings.add("Cannot retrieve valid table names for datasourceId=" + datasourceId + ", skipping table validation");
            return ValidationResult.pass();
        }

        List<QueryPlan.TablePlan> validTablePlans = new ArrayList<>();
        boolean hasInvalid = false;

        for (QueryPlan.TablePlan tp : tables) {
            String tableName = tp.getTableName();
            if (tableName == null || tableName.trim().isEmpty()) {
                warnings.add("TablePlan has empty tableName, removing");
                hasInvalid = true;
                continue;
            }

            if (validTables.contains(tableName.toLowerCase())) {
                validTablePlans.add(tp);
            } else if (validTables.contains(tableName)) {
                validTablePlans.add(tp);
            } else {
                String matched = findSimilarTable(tableName, validTables);
                if (matched != null) {
                    warnings.add("Table '" + tableName + "' not found, corrected to '" + matched + "'");
                    tp.setTableName(matched);
                    validTablePlans.add(tp);
                    corrections.add("Table '" + tableName + "' → '" + matched + "'");
                    hasInvalid = true;
                } else {
                    warnings.add("Table '" + tableName + "' not found in datasource and no similar match, removing");
                    hasInvalid = true;
                }
            }
        }

        if (hasInvalid) {
            plan.setTables(validTablePlans);
            if (validTablePlans.isEmpty()) {
                warnings.add("All tables removed after validation, clearing tables list");
            }
        }

        if (!corrections.isEmpty()) {
            return ValidationResult.passWithCorrections(corrections);
        }
        return ValidationResult.pass();
    }

    private ValidationResult validateChart(QueryPlan plan, List<String> warnings, List<String> corrections) {
        QueryPlan.ChartPlan chart = plan.getChart();
        if (chart == null) {
            return ValidationResult.pass();
        }

        if (chart.isNeeded() && (chart.getType() == null || chart.getType() == QueryPlan.ChartPlan.ChartType.UNKNOWN)) {
            warnings.add("Chart needed but type is UNKNOWN, defaulting to BAR");
            chart.setType(QueryPlan.ChartPlan.ChartType.BAR);
            corrections.add("Chart type UNKNOWN → BAR");
            return ValidationResult.passWithCorrections(corrections);
        }

        if (!chart.isNeeded() && chart.getType() != null && chart.getType() != QueryPlan.ChartPlan.ChartType.UNKNOWN) {
            warnings.add("Chart not needed but type is set, clearing type");
            chart.setType(null);
            corrections.add("Cleared chart type (not needed)");
            return ValidationResult.passWithCorrections(corrections);
        }

        return ValidationResult.pass();
    }

    private ValidationResult validateComplexity(QueryPlan plan, List<String> warnings, List<String> corrections) {
        if (plan.getComplexity() == null) {
            warnings.add("Complexity is null, defaulting to MODERATE");
            plan.setComplexity(QueryPlan.ComplexityLevel.MODERATE);
            corrections.add("Complexity null → MODERATE");
            return ValidationResult.passWithCorrections(corrections);
        }

        List<QueryPlan.TablePlan> tables = plan.getTables();
        int tableCount = tables != null ? tables.size() : 0;
        boolean hasJoin = false;
        if (tables != null) {
            for (QueryPlan.TablePlan t : tables) {
                if (t.getJoinType() != null && !t.getJoinType().isEmpty()) {
                    hasJoin = true;
                    break;
                }
            }
        }

        QueryPlan.ComplexityLevel expected;
        if (tableCount <= 1 && !hasJoin) {
            expected = QueryPlan.ComplexityLevel.SIMPLE;
        } else if (tableCount >= 2 && hasJoin) {
            boolean needsChart = plan.getChart() != null && plan.getChart().isNeeded();
            boolean needsSummary = plan.isNeedSummary();
            expected = (needsChart || needsSummary)
                ? QueryPlan.ComplexityLevel.COMPLEX
                : QueryPlan.ComplexityLevel.MODERATE;
        } else {
            expected = QueryPlan.ComplexityLevel.MODERATE;
        }

        if (plan.getComplexity() != expected) {
            warnings.add("Complexity " + plan.getComplexity() + " → " + expected + " (tables=" + tableCount + ", hasJoin=" + hasJoin + ")");
            plan.setComplexity(expected);
            corrections.add("Complexity " + plan.getComplexity() + " → " + expected);
            return ValidationResult.passWithCorrections(corrections);
        }

        return ValidationResult.pass();
    }

    @SuppressWarnings("unchecked")
    private Set<String> retrieveValidTableNames(Long datasourceId) {
        if (datasourceId == null || toolRegistry == null) {
            return Collections.emptySet();
        }

        try {
            if (!toolRegistry.hasTool("retrieveSchema")) {
                return Collections.emptySet();
            }

            Map<String, Object> args = new HashMap<>();
            args.put("question", "SHOW ALL TABLES");
            args.put("datasourceId", datasourceId);

            Object result = toolRegistry.callTool("retrieveSchema", args);
            if (result instanceof String) {
                return extractTableNamesFromSchemaText((String) result);
            } else if (result instanceof Map) {
                Map<String, Object> resultMap = (Map<String, Object>) result;
                Object schema = resultMap.get("schema");
                if (schema instanceof String) {
                    return extractTableNamesFromSchemaText((String) schema);
                }
            }
        } catch (Exception e) {
            log.warn("[PlanValidator] Failed to retrieve valid table names: {}", e.getMessage());
        }

        return Collections.emptySet();
    }

    Set<String> extractTableNamesFromSchemaText(String schemaText) {
        Set<String> tables = new HashSet<>();
        if (schemaText == null || schemaText.isEmpty()) {
            return tables;
        }

        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
            "表名\\s*:\\s*(\\w+)", java.util.regex.Pattern.CASE_INSENSITIVE
        );
        java.util.regex.Matcher matcher = pattern.matcher(schemaText);
        while (matcher.find()) {
            tables.add(matcher.group(1).toLowerCase());
        }

        return tables;
    }

    private String findSimilarTable(String tableName, Set<String> validTables) {
        String lower = tableName.toLowerCase();

        if (validTables.contains(lower + "s")) {
            return lower + "s";
        }
        if (validTables.contains(lower + "es")) {
            return lower + "es";
        }

        if (lower.endsWith("s") && validTables.contains(lower.substring(0, lower.length() - 1))) {
            return lower.substring(0, lower.length() - 1);
        }
        if (lower.endsWith("es") && validTables.contains(lower.substring(0, lower.length() - 2))) {
            return lower.substring(0, lower.length() - 2);
        }

        for (String valid : validTables) {
            if (valid.contains(lower) || lower.contains(valid)) {
                return valid;
            }
        }

        return null;
    }
}
