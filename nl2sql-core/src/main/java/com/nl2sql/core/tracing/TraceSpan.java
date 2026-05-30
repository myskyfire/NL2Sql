package com.nl2sql.core.tracing;

import lombok.Data;

import java.time.Instant;
import java.util.*;

@Data
public class TraceSpan {

    private UUID id;
    private String name;
    private RunType runType;
    private Map<String, Object> inputs;
    private Map<String, Object> outputs;
    private UUID parentRunId;
    private String sessionName;
    private Instant startTime;
    private Instant endTime;
    private String error;
    private Map<String, Object> extra;
    private List<String> tags;

    public enum RunType {
        chain, llm, tool, retriever
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final TraceSpan span = new TraceSpan();

        public Builder id(UUID id) {
            span.id = id;
            return this;
        }

        public Builder name(String name) {
            span.name = name;
            return this;
        }

        public Builder runType(RunType runType) {
            span.runType = runType;
            return this;
        }

        public Builder inputs(Map<String, Object> inputs) {
            span.inputs = inputs;
            return this;
        }

        public Builder outputs(Map<String, Object> outputs) {
            span.outputs = outputs;
            return this;
        }

        public Builder parentRunId(UUID parentRunId) {
            span.parentRunId = parentRunId;
            return this;
        }

        public Builder sessionName(String sessionName) {
            span.sessionName = sessionName;
            return this;
        }

        public Builder startTime(Instant startTime) {
            span.startTime = startTime;
            return this;
        }

        public Builder endTime(Instant endTime) {
            span.endTime = endTime;
            return this;
        }

        public Builder error(String error) {
            span.error = error;
            return this;
        }

        public Builder extra(Map<String, Object> extra) {
            span.extra = extra;
            return this;
        }

        public Builder tags(List<String> tags) {
            span.tags = tags;
            return this;
        }

        public TraceSpan build() {
            if (span.id == null) {
                span.id = UUID.randomUUID();
            }
            if (span.startTime == null) {
                span.startTime = Instant.now();
            }
            return span;
        }
    }

    public Map<String, Object> toCreateMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id.toString());
        map.put("name", name);
        map.put("run_type", runType.name());
        if (inputs != null) map.put("inputs", inputs);
        if (parentRunId != null) map.put("parent_run_id", parentRunId.toString());
        if (sessionName != null) map.put("session_name", sessionName);
        if (startTime != null) map.put("start_time", startTime.toString());
        if (extra != null) map.put("extra", extra);
        if (tags != null) map.put("tags", tags);
        return map;
    }

    public Map<String, Object> toEndMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id.toString());
        if (outputs != null) map.put("outputs", outputs);
        if (endTime != null) map.put("end_time", endTime.toString());
        if (error != null) map.put("error", error);
        return map;
    }
}
