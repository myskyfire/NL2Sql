package com.nl2sql.core.agent.engine;

import com.nl2sql.core.agent.planner.QueryPlan;
import com.nl2sql.core.agent.worker.Worker;
import lombok.Data;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

@Data
public class WorkflowContext {
    QueryPlan plan;
    Long datasourceId;
    Long userId;
    String username;
    String userMessage;
    String sessionId;
    Map<String, Worker.WorkerResult> workerResults = new LinkedHashMap<>();
    Map<String, Worker.WorkerResult> previousResults = new HashMap<>();
    Map<String, Object> toolResults = new LinkedHashMap<>();
    Map<String, Object> contextVars = new LinkedHashMap<>();
    String assembleResult;
}
