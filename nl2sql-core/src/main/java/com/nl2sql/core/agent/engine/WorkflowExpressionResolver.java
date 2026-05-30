package com.nl2sql.core.agent.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nl2sql.core.agent.planner.QueryPlan;
import com.nl2sql.core.agent.worker.Worker;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
public class WorkflowExpressionResolver {

    private static final Pattern EXPR_PATTERN = Pattern.compile("\\$\\{(.+?)}");
    private static final Pattern MUSTACHE_PATTERN = Pattern.compile("\\{\\{(.+?)}}");

    private final ObjectMapper jsonMapper;

    public WorkflowExpressionResolver(ObjectMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    public boolean evaluateCondition(String condition, WorkflowContext ctx) {
        if (condition == null) return true;

        String resolved = resolveExpression(condition, ctx);

        if ("true".equalsIgnoreCase(resolved) || "1".equals(resolved)) return true;
        if ("false".equalsIgnoreCase(resolved) || "0".equals(resolved) || "null".equalsIgnoreCase(resolved)) return false;

        String normalizedCondition = condition.trim();
        if (normalizedCondition.startsWith("${") && normalizedCondition.endsWith("}")) {
            normalizedCondition = normalizedCondition.substring(2, normalizedCondition.length() - 1).trim();
        }

        if (normalizedCondition.contains("==")) {
            String[] parts = normalizedCondition.split("==", 2);
            if (parts.length == 2) {
                String left = resolveExpression(parts[0].trim(), ctx);
                String right = resolveExpression(parts[1].trim(), ctx);
                return Objects.equals(left, right);
            }
        }

        if (normalizedCondition.contains("!=")) {
            String[] parts = normalizedCondition.split("!=", 2);
            if (parts.length == 2) {
                String left = resolveExpression(parts[0].trim(), ctx);
                String right = resolveExpression(parts[1].trim(), ctx);
                return !Objects.equals(left, right);
            }
        }

        return true;
    }

    public String resolveExpression(String expr, WorkflowContext ctx) {
        if (expr == null) return null;

        Matcher matcher = EXPR_PATTERN.matcher(expr);
        StringBuffer sb = new StringBuffer();

        while (matcher.find()) {
            String path = matcher.group(1).trim();
            String value = resolvePath(path, ctx);
            matcher.appendReplacement(sb, value != null ? Matcher.quoteReplacement(value) : "");
        }
        matcher.appendTail(sb);

        String result = sb.toString();
        return result.isEmpty() ? expr : result;
    }

    public String resolveMustacheAndExpr(String template, WorkflowContext ctx) {
        String result = template;

        Matcher mustacheMatcher = MUSTACHE_PATTERN.matcher(result);
        StringBuffer sb = new StringBuffer();
        while (mustacheMatcher.find()) {
            String path = mustacheMatcher.group(1).trim();
            String value = resolvePath(path, ctx);
            mustacheMatcher.appendReplacement(sb, value != null ? Matcher.quoteReplacement(value) : "");
        }
        mustacheMatcher.appendTail(sb);
        result = sb.toString();

        Matcher exprMatcher = EXPR_PATTERN.matcher(result);
        sb = new StringBuffer();
        while (exprMatcher.find()) {
            String path = exprMatcher.group(1).trim();
            String value = resolvePath(path, ctx);
            exprMatcher.appendReplacement(sb, value != null ? Matcher.quoteReplacement(value) : "");
        }
        exprMatcher.appendTail(sb);

        return sb.toString();
    }

    public String resolvePath(String path, WorkflowContext ctx) {
        if (path.startsWith("queryPlan.") || path.startsWith("plan.")) {
            String fieldPath = path.startsWith("queryPlan.") ? path.substring("queryPlan.".length()) : path.substring("plan.".length());
            return resolveQueryPlanPath(fieldPath, ctx.plan);
        }

        if ("datasourceId".equals(path)) {
            return ctx.datasourceId != null ? ctx.datasourceId.toString() : "null";
        }

        if ("userId".equals(path)) {
            return ctx.userId != null ? ctx.userId.toString() : "null";
        }

        if ("username".equals(path)) {
            return ctx.username != null ? ctx.username : "null";
        }

        if ("sessionId".equals(path)) {
            return ctx.sessionId != null ? ctx.sessionId : "null";
        }

        if ("userMessage".equals(path) || "question".equals(path)) {
            return ctx.userMessage != null ? ctx.userMessage : "null";
        }

        if (ctx.contextVars.containsKey(path)) {
            Object varValue = ctx.contextVars.get(path);
            if (varValue instanceof String) return (String) varValue;
            try {
                return jsonMapper.writeValueAsString(varValue);
            } catch (Exception e) {
                return String.valueOf(varValue);
            }
        }

        if (path.contains(".")) {
            String[] parts = path.split("\\.", 2);
            String rootKey = parts[0];
            String subPath = parts.length > 1 ? parts[1] : null;

            Object rootValue = ctx.contextVars.get(rootKey);
            if (rootValue == null) {
                rootValue = ctx.toolResults.get(rootKey);
            }
            if (rootValue == null) {
                Worker.WorkerResult workerResult = ctx.previousResults.get(rootKey);
                if (workerResult != null) {
                    if (subPath != null && (subPath.startsWith("result") || subPath.startsWith("data"))) {
                        try {
                            return jsonMapper.writeValueAsString(workerResult.getData());
                        } catch (Exception e) {
                            return workerResult.getRawOutput();
                        }
                    }
                    if (subPath != null) {
                        try {
                            String json = jsonMapper.writeValueAsString(workerResult.getData());
                            Map<String, Object> data = jsonMapper.readValue(json, Map.class);
                            return resolveSubPath(data, subPath);
                        } catch (Exception e) {
                            return null;
                        }
                    }
                    try {
                        return jsonMapper.writeValueAsString(workerResult.getData());
                    } catch (Exception e) {
                        return workerResult.getRawOutput();
                    }
                }
                return null;
            }

            if (subPath == null) {
                if (rootValue instanceof String) return (String) rootValue;
                try {
                    return jsonMapper.writeValueAsString(rootValue);
                } catch (Exception e) {
                    return String.valueOf(rootValue);
                }
            }

            if (rootValue instanceof Map) {
                return resolveSubPath((Map<String, Object>) rootValue, subPath);
            }

            return null;
        }

        if (ctx.previousResults.containsKey(path)) {
            Worker.WorkerResult result = ctx.previousResults.get(path);
            try {
                return jsonMapper.writeValueAsString(result.getData());
            } catch (Exception e) {
                return result.getRawOutput();
            }
        }

        return null;
    }

    @SuppressWarnings("unchecked")
    private String resolveSubPath(Map<String, Object> data, String subPath) {
        String[] parts = subPath.split("\\.", 2);
        String key = parts[0];
        Object value = data.get(key);

        if (value == null) return null;

        if (parts.length == 1) {
            if (value instanceof String) return (String) value;
            if (value instanceof Number) return String.valueOf(value);
            if (value instanceof Boolean) return String.valueOf(value);
            try {
                return jsonMapper.writeValueAsString(value);
            } catch (Exception e) {
                return String.valueOf(value);
            }
        }

        if (value instanceof Map) {
            return resolveSubPath((Map<String, Object>) value, parts[1]);
        }

        return null;
    }

    private String resolveQueryPlanPath(String fieldPath, QueryPlan plan) {
        if (plan == null) return null;

        if (fieldPath.startsWith("chart.")) {
            String chartField = fieldPath.substring("chart.".length());
            if (plan.getChart() == null) return "false";
            switch (chartField) {
                case "needed": return String.valueOf(plan.getChart().isNeeded());
                case "type": return plan.getChart().getType() != null ? plan.getChart().getType().name() : null;
                case "xField": return plan.getChart().getXField();
                case "yField": return plan.getChart().getYField();
                case "title": return plan.getChart().getTitle();
                default: return null;
            }
        }

        if (fieldPath.startsWith("datasource.")) {
            String dsField = fieldPath.substring("datasource.".length());
            if (plan.getDatasource() == null) return null;
            switch (dsField) {
                case "id": return plan.getDatasource().getId() != null ? plan.getDatasource().getId().toString() : null;
                case "name": return plan.getDatasource().getName();
                default: return null;
            }
        }

        switch (fieldPath) {
            case "needSummary": return String.valueOf(plan.isNeedSummary());
            case "complexity": return plan.getComplexity() != null ? plan.getComplexity().name() : null;
            case "userQuestion": return plan.getUserQuestion();
            default: return null;
        }
    }

    public Object tryParseValue(String value) {
        if (value == null) return null;
        if ("true".equalsIgnoreCase(value)) return true;
        if ("false".equalsIgnoreCase(value)) return false;
        if ("null".equalsIgnoreCase(value)) return null;
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {}
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException ignored) {}
        try {
            return jsonMapper.readValue(value, Map.class);
        } catch (Exception ignored) {}
        return value;
    }
}
