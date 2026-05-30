package com.nl2sql.core.tracing;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "langsmith")
public class LangSmithConfig {

    private boolean enabled = false;

    private String apiKey;

    private String baseUrl = "https://api.smith.langchain.com";

    private String project = "nl2sql";

    private int timeout = 10;

    public boolean isValid() {
        return enabled && apiKey != null && !apiKey.trim().isEmpty();
    }
}
