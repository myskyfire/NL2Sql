package com.nl2sql.core.agent.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nl2sql.core.agent.worker.Worker;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
public class WorkflowResultAssembler {

    private final ObjectMapper jsonMapper;

    public WorkflowResultAssembler(ObjectMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    public String assembleDefaultResult(Map<String, Worker.WorkerResult> results) {
        try {
            Map<String, Object> response = buildFrontendCompatibleResponse(results);
            return jsonMapper.writeValueAsString(response);
        } catch (Exception e) {
            log.error("[WorkflowResultAssembler] 默认组装失败", e);
            return "{\"success\":false,\"error\":\"结果组装失败\"}";
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> buildFrontendCompatibleResponse(Map<String, Worker.WorkerResult> results) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);

        Worker.WorkerResult sqlResult = results.get("sql");
        if (sqlResult == null) {
            for (Map.Entry<String, Worker.WorkerResult> entry : results.entrySet()) {
                if (entry.getValue() != null && entry.getValue().getData() != null
                    && entry.getValue().getData().containsKey("sql")) {
                    sqlResult = entry.getValue();
                    break;
                }
            }
        }

        if (sqlResult != null && sqlResult.isSuccess() && sqlResult.getData() != null) {
            Map<String, Object> sqlData = sqlResult.getData();

            if (sqlData.containsKey("type")) {
                String type = sqlData.get("type") != null ? sqlData.get("type").toString() : "data";
                response.put("type", type);
            } else {
                response.put("type", "data");
            }

            if (sqlData.containsKey("data")) {
                response.put("data", sqlData.get("data"));
            }
            if (sqlData.containsKey("rowCount")) {
                response.put("rowCount", sqlData.get("rowCount"));
            }
            if (sqlData.containsKey("executionTime")) {
                response.put("executionTime", sqlData.get("executionTime"));
            }
            if (sqlData.containsKey("sql")) {
                response.put("sql", sqlData.get("sql"));
            }
            if (sqlData.containsKey("datasourceId")) {
                response.put("datasourceId", sqlData.get("datasourceId"));
            }
            if (sqlData.containsKey("optimizationSuggestion")) {
                Object optSuggestion = sqlData.get("optimizationSuggestion");
                if (optSuggestion != null && !optSuggestion.toString().trim().isEmpty()) {
                    response.put("optimizationSuggestion", optSuggestion);
                }
            }
            if (sqlData.containsKey("followUpSuggestions")) {
                response.put("followUpSuggestions", sqlData.get("followUpSuggestions"));
            }
            if (sqlData.containsKey("steps")) {
                response.put("steps", sqlData.get("steps"));
            }

            if ("clarification".equals(response.get("type"))) {
                response.put("needsClarification", true);
                response.put("clarificationMessage", sqlData.get("clarificationMessage"));
            }

            if ("human_approval_required".equals(response.get("type"))) {
                response.put("approvalId", sqlData.get("approvalId"));
                response.put("riskLevel", sqlData.get("riskLevel"));
                response.put("riskReason", sqlData.get("riskReason"));
                response.put("message", sqlData.get("message"));
            }

            if ("error".equals(response.get("type"))) {
                response.put("success", false);
                response.put("error", sqlData.get("error"));
            }
        }

        Worker.WorkerResult chartResult = results.get("chart");
        if (chartResult != null) {
            for (Map.Entry<String, Worker.WorkerResult> entry : results.entrySet()) {
                if (entry.getValue() != null && entry.getValue().getData() != null
                    && entry.getValue().getData().containsKey("echartsConfig")) {
                    chartResult = entry.getValue();
                    break;
                }
            }
        }
        if (chartResult != null && chartResult.isSuccess() && chartResult.getData() != null) {
            Map<String, Object> chartData = chartResult.getData();
            if (chartData.containsKey("echartsConfig")) {
                response.put("echartsConfig", chartData.get("echartsConfig"));
                response.put("chartType", chartData.getOrDefault("chartType", "bar"));
                response.put("type", "chart");
            }
        }

        Worker.WorkerResult summaryResult = results.get("summary");
        if (summaryResult != null) {
            for (Map.Entry<String, Worker.WorkerResult> entry : results.entrySet()) {
                if (entry.getValue() != null && entry.getValue().getData() != null
                    && entry.getValue().getData().containsKey("summary")) {
                    summaryResult = entry.getValue();
                    break;
                }
            }
        }
        if (summaryResult != null && summaryResult.isSuccess() && summaryResult.getData() != null) {
            Map<String, Object> summaryData = summaryResult.getData();
            if (summaryData.containsKey("summary")) {
                response.put("summary", summaryData.get("summary"));
            }
        }

        return response;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> buildFrontendCompatibleResponseFromTools(
            Map<String, Object> toolResults, Map<String, Object> contextVars) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);

        Object execResult = null;
        for (Map.Entry<String, Object> entry : toolResults.entrySet()) {
            if (entry.getValue() instanceof Map) {
                Map<String, Object> map = (Map<String, Object>) entry.getValue();
                if (map.containsKey("data") && map.get("data") instanceof List) {
                    execResult = entry.getValue();
                    break;
                }
            }
        }

        if (execResult == null) {
            for (Map.Entry<String, Object> entry : contextVars.entrySet()) {
                if (entry.getValue() instanceof Map) {
                    Map<String, Object> map = (Map<String, Object>) entry.getValue();
                    if (map.containsKey("data") && map.get("data") instanceof List) {
                        execResult = entry.getValue();
                        break;
                    }
                }
            }
        }

        if (execResult instanceof Map) {
            Map<String, Object> execData = (Map<String, Object>) execResult;

            if (execData.containsKey("data")) {
                response.put("data", execData.get("data"));
            }
            if (execData.containsKey("rowCount")) {
                response.put("rowCount", execData.get("rowCount"));
            }
            if (execData.containsKey("executionTime")) {
                response.put("executionTime", execData.get("executionTime"));
            }
            if (execData.containsKey("sql")) {
                response.put("sql", execData.get("sql"));
            }

            Object sqlResultVar = contextVars.get("sql_result");
            if (sqlResultVar instanceof Map) {
                Map<String, Object> sqlResultMap = (Map<String, Object>) sqlResultVar;
                if (sqlResultMap.containsKey("data") && sqlResultMap.get("data") instanceof Map) {
                    Map<String, Object> sqlInner = (Map<String, Object>) sqlResultMap.get("data");
                    if (sqlInner.containsKey("sql")) {
                        response.put("sql", sqlInner.get("sql"));
                    }
                }
            }
        }

        return response;
    }
}
