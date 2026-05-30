package com.nl2sql.core.tracing;

import java.util.UUID;

public class TracingContext {

    private static final ThreadLocal<UUID> CURRENT_RUN_ID = new ThreadLocal<>();

    public static void setRunId(UUID runId) {
        CURRENT_RUN_ID.set(runId);
    }

    public static UUID currentRunId() {
        return CURRENT_RUN_ID.get();
    }

    public static void clear() {
        CURRENT_RUN_ID.remove();
    }
}
