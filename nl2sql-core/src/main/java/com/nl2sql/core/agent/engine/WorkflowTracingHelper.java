package com.nl2sql.core.agent.engine;

import com.nl2sql.core.tracing.TraceSpan;
import com.nl2sql.core.tracing.TracingContext;
import com.nl2sql.core.tracing.TracingService;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Slf4j
public class WorkflowTracingHelper {

    private final TracingService tracingService;

    public WorkflowTracingHelper(TracingService tracingService) {
        this.tracingService = tracingService;
    }

    public boolean isEnabled() {
        return tracingService != null && tracingService.isEnabled();
    }

    public TraceSpan startWorkflowTrace(String name, Map<String, Object> inputs, UUID parentRunId) {
        if (!isEnabled()) return null;
        try {
            return tracingService.traceChain(name, inputs, parentRunId);
        } catch (Exception e) {
            log.debug("[WorkflowTracingHelper] startWorkflowTrace 失败: {}", e.getMessage());
            return null;
        }
    }

    public String endWorkflowTrace(TraceSpan run, String result) {
        if (tracingService != null && run != null) {
            Map<String, Object> outputs = new LinkedHashMap<>();
            outputs.put("resultLength", result != null ? result.length() : 0);
            tracingService.endRun(run, outputs, null);
        }
        return result;
    }

    public void endWorkflowTraceWithError(TraceSpan run, String error) {
        if (tracingService != null && run != null) {
            tracingService.endRun(run, null, error);
        }
    }

    public TraceSpan startStepTrace(WorkflowStep step) {
        if (!isEnabled()) return null;
        try {
            Map<String, Object> inputs = new LinkedHashMap<>();
            inputs.put("stepId", step.getId());
            inputs.put("action", step.getAction());
            inputs.put("type", step.getType());
            if (step.getTool() != null) inputs.put("tool", step.getTool());
            return tracingService.traceChain("step:" + step.getId(), inputs, TracingContext.currentRunId());
        } catch (Exception e) {
            log.debug("[WorkflowTracingHelper] startStepTrace 失败: {}", e.getMessage());
            return null;
        }
    }

    public void endStepTrace(TraceSpan run, String result, String error) {
        if (tracingService == null || run == null) return;
        try {
            Map<String, Object> outputs = new LinkedHashMap<>();
            outputs.put("resultLength", result != null ? result.length() : 0);
            if (result != null && result.length() > 500) {
                outputs.put("resultPreview", result.substring(0, 500) + "...");
            }
            tracingService.endRun(run, outputs, error);
        } catch (Exception e) {
            log.debug("[WorkflowTracingHelper] endStepTrace 失败: {}", e.getMessage());
        }
    }
}
