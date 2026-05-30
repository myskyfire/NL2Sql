package com.nl2sql.core.agent.engine;

import lombok.Data;

import java.util.List;

@Data
public class RetryConfig {
    private int maxAttempts;
    private List<String> onErrors;
    private String tool;
}
