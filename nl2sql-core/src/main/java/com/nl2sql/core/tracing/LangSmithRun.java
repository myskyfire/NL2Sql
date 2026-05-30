package com.nl2sql.core.tracing;

import lombok.Data;

import java.time.Instant;
import java.util.*;

@Data
public class LangSmithRun {

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
        private final LangSmithRun run = new LangSmithRun();

        public Builder id(UUID id) {
            run.id = id;
            return this;
        }

        public Builder name(String name) {
            run.name = name;
            return this;
        }

        public Builder runType(RunType runType) {
            run.runType = runType;
            return this;
        }

        public Builder inputs(Map<String, Object> inputs) {
            run.inputs = inputs;
            return this;
        }

        public Builder outputs(Map<String, Object> outputs) {
            run.outputs = outputs;
            return this;
        }

        public Builder parentRunId(UUID parentRunId) {
            run.parentRunId = parentRunId;
            return this;
        }

        public Builder sessionName(String sessionName) {
            run.sessionName = sessionName;
            return this;
        }

        public Builder startTime(Instant startTime) {
            run.startTime = startTime;
            return this;
        }

        public Builder endTime(Instant endTime) {
            run.endTime = endTime;
            return this;
        }

        public Builder error(String error) {
            run.error = error;
            return this;
        }

        public Builder extra(Map<String, Object> extra) {
            run.extra = extra;
            return this;
        }

        public Builder tags(List<String> tags) {
            run.tags = tags;
            return this;
        }

        public LangSmithRun build() {
            if (run.id == null) {
                run.id = UUID.randomUUID();
            }
            if (run.startTime == null) {
                run.startTime = Instant.now();
            }
            return run;
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
