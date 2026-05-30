package com.nl2sql.core.tracing;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface TracingService {

    boolean isEnabled();

    String getProject();

    TraceSpan startRun(String name, TraceSpan.RunType runType, Map<String, Object> inputs, UUID parentRunId);

    TraceSpan startRun(String name, TraceSpan.RunType runType, Map<String, Object> inputs, UUID parentRunId, Map<String, Object> extra, List<String> tags);

    void endRun(TraceSpan run, Map<String, Object> outputs);

    void endRun(TraceSpan run, Map<String, Object> outputs, String error);

    TraceSpan traceLlm(String name, Map<String, Object> inputs, UUID parentRunId);

    TraceSpan traceChain(String name, Map<String, Object> inputs, UUID parentRunId);

    TraceSpan traceTool(String name, Map<String, Object> inputs, UUID parentRunId);

    TraceSpan traceRetriever(String name, Map<String, Object> inputs, UUID parentRunId);

    void sendFeedback(UUID runId, String key, double score, String comment);

    void sendThumbsUp(UUID runId, String comment);

    void sendThumbsDown(UUID runId, String comment);
}
