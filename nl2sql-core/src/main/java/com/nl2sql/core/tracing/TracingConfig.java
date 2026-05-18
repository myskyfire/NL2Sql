package com.nl2sql.core.tracing;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "tracing")
public class TracingConfig {

    private String provider = "langsmith";

    private Langsmith langsmith = new Langsmith();

    private Langfuse langfuse = new Langfuse();

    public boolean isEnabled() {
        if ("langsmith".equalsIgnoreCase(provider)) {
            return langsmith.isEnabled() && langsmith.getApiKey() != null && !langsmith.getApiKey().trim().isEmpty();
        } else if ("langfuse".equalsIgnoreCase(provider)) {
            return langfuse.isEnabled() && langfuse.getPublicKey() != null && !langfuse.getPublicKey().trim().isEmpty();
        }
        return false;
    }

    public String getProject() {
        if ("langsmith".equalsIgnoreCase(provider)) {
            return langsmith.getProject();
        }
        return langfuse.getProject();
    }

    @Data
    public static class Langsmith {
        private boolean enabled = false;
        private String apiKey;
        private String baseUrl = "https://api.smith.langchain.com";
        private String project = "nl2sql";
        private int timeout = 10;
    }

    @Data
    public static class Langfuse {
        private boolean enabled = false;
        private String publicKey;
        private String secretKey;
        private String baseUrl = "http://localhost:3000";
        private String project = "nl2sql";
        private int timeout = 10;
    }
}
