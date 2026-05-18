package com.nl2sql.core.agent.engine;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class WorkflowStep {
    private String id;
    private String type;
    private String action;
    private String tool;
    private String skill;
    private Map<String, Object> toolInput;
    private String outputVar;
    private String worker;
    private String condition;
    private String input;
    private String onNext;
    private String onTrue;
    private String onFalse;
    private String onConditionTrue;
    private String onConditionFalse;
    private List<String> branches;
    private Map<String, Object> output;
    private RetryConfig retry;
    private boolean onErrorFail;

    public int getRetryMaxAttempts() {
        return retry != null ? retry.getMaxAttempts() : 0;
    }
}
