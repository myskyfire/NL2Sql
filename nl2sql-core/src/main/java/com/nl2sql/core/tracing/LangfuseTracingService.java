package com.nl2sql.core.tracing;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

@Slf4j
@Service
@ConditionalOnProperty(name = "tracing.provider", havingValue = "langfuse")
public class LangfuseTracingService implements TracingService {

    private final TracingConfig config;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    private static final String API_INGEST_ENDPOINT = "/api/public/ingestion";
    private static final String API_SCORE_ENDPOINT = "/api/public/scores";

    @Autowired
    public LangfuseTracingService(TracingConfig config) {
        this.config = config;
        this.objectMapper = new ObjectMapper();
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(config.getLangfuse().getTimeout()))
            .build();

        if (config.isEnabled() && "langfuse".equalsIgnoreCase(config.getProvider())) {
            log.info("[Langfuse] Tracing 已启用, project={}, baseUrl={}", config.getProject(), config.getLangfuse().getBaseUrl());
        } else {
            log.info("[Langfuse] Tracing 未启用");
        }
    }

    @Override
    public boolean isEnabled() {
        return config.isEnabled() && "langfuse".equalsIgnoreCase(config.getProvider());
    }

    @Override
    public String getProject() {
        return config.getProject();
    }

    @Override
    public TraceSpan startRun(String name, TraceSpan.RunType runType,
                               Map<String, Object> inputs, UUID parentRunId) {
        return startRun(name, runType, inputs, parentRunId, null, null);
    }

    @Override
    public TraceSpan startRun(String name, TraceSpan.RunType runType,
                               Map<String, Object> inputs, UUID parentRunId,
                               Map<String, Object> extra, List<String> tags) {
        if (!isEnabled()) {
            return TraceSpan.builder()
                .id(UUID.randomUUID())
                .name(name)
                .runType(runType)
                .build();
        }

        TraceSpan span = TraceSpan.builder()
            .name(name)
            .runType(runType)
            .inputs(inputs)
            .parentRunId(parentRunId)
            .sessionName(config.getProject())
            .startTime(java.time.Instant.now())
            .extra(extra)
            .tags(tags)
            .build();

        sendCreateEvent(span);
        return span;
    }

    @Override
    public void endRun(TraceSpan run, Map<String, Object> outputs) {
        endRun(run, outputs, null);
    }

    @Override
    public void endRun(TraceSpan run, Map<String, Object> outputs, String error) {
        if (!isEnabled() || run == null) return;

        run.setOutputs(outputs);
        run.setEndTime(java.time.Instant.now());
        if (error != null) {
            run.setError(error);
        }

        sendUpdateEvent(run);
    }

    @Override
    public TraceSpan traceLlm(String name, Map<String, Object> inputs, UUID parentRunId) {
        return startRun(name, TraceSpan.RunType.llm, inputs, parentRunId);
    }

    @Override
    public TraceSpan traceChain(String name, Map<String, Object> inputs, UUID parentRunId) {
        return startRun(name, TraceSpan.RunType.chain, inputs, parentRunId);
    }

    @Override
    public TraceSpan traceTool(String name, Map<String, Object> inputs, UUID parentRunId) {
        return startRun(name, TraceSpan.RunType.tool, inputs, parentRunId);
    }

    @Override
    public TraceSpan traceRetriever(String name, Map<String, Object> inputs, UUID parentRunId) {
        return startRun(name, TraceSpan.RunType.retriever, inputs, parentRunId);
    }

    private void sendCreateEvent(TraceSpan span) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("id", span.getId().toString());
            body.put("traceId", span.getId().toString());
            body.put("name", span.getName());
            body.put("type", "span");
            if (span.getParentRunId() != null) {
                body.put("parentObservationId", span.getParentRunId().toString());
            }
            body.put("startTime", span.getStartTime().toString());
            if (span.getInputs() != null) {
                body.put("input", span.getInputs());
            }

            String json = objectMapper.writeValueAsString(body);

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(config.getLangfuse().getBaseUrl() + API_INGEST_ENDPOINT))
                .header("Content-Type", "application/json")
                .header("Authorization", buildBasicAuth())
                .timeout(Duration.ofSeconds(config.getLangfuse().getTimeout()))
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();

            httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(response -> {
                    if (response.statusCode() >= 400) {
                        log.warn("[Langfuse] 创建 Span 失败: status={}, body={}",
                            response.statusCode(),
                            response.body().substring(0, Math.min(200, response.body().length())));
                    }
                })
                .exceptionally(e -> {
                    log.warn("[Langfuse] 创建 Span 异步请求失败: {}", e.getMessage());
                    return null;
                });

        } catch (Exception e) {
            log.warn("[Langfuse] 创建 Span 失败: {}", e.getMessage());
        }
    }

    private void sendUpdateEvent(TraceSpan span) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("id", span.getId().toString());
            body.put("traceId", span.getId().toString());
            body.put("type", "span");
            if (span.getOutputs() != null) {
                body.put("output", span.getOutputs());
            }
            body.put("endTime", span.getEndTime().toString());
            if (span.getError() != null) {
                body.put("level", "ERROR");
                body.put("statusMessage", span.getError());
            }

            String json = objectMapper.writeValueAsString(body);

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(config.getLangfuse().getBaseUrl() + API_INGEST_ENDPOINT))
                .header("Content-Type", "application/json")
                .header("Authorization", buildBasicAuth())
                .timeout(Duration.ofSeconds(config.getLangfuse().getTimeout()))
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();

            httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(response -> {
                    if (response.statusCode() >= 400) {
                        log.warn("[Langfuse] 更新 Span 失败: status={}, runId={}",
                            response.statusCode(), span.getId());
                    }
                })
                .exceptionally(e -> {
                    log.warn("[Langfuse] 更新 Span 异步请求失败: {}", e.getMessage());
                    return null;
                });

        } catch (Exception e) {
            log.warn("[Langfuse] 更新 Span 失败: {}", e.getMessage());
        }
    }

    @Override
    public void sendFeedback(UUID runId, String key, double score, String comment) {
        if (!isEnabled() || runId == null) return;
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("traceId", runId.toString());
            body.put("name", key);
            body.put("value", score);
            if (comment != null && !comment.isEmpty()) {
                body.put("comment", comment);
            }
            
            String json = objectMapper.writeValueAsString(body);
            
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(config.getLangfuse().getBaseUrl() + API_SCORE_ENDPOINT))
                .header("Content-Type", "application/json")
                .header("Authorization", buildBasicAuth())
                .timeout(Duration.ofSeconds(config.getLangfuse().getTimeout()))
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();

            httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(response -> {
                    if (response.statusCode() >= 400) {
                        log.warn("[Langfuse] 发送反馈失败: status={}, body={}",
                            response.statusCode(),
                            response.body().substring(0, Math.min(200, response.body().length())));
                    } else {
                        log.info("[Langfuse] 反馈已发送: traceId={}, name={}, value={}", runId, key, score);
                    }
                })
                .exceptionally(e -> {
                    log.warn("[Langfuse] 发送反馈异步请求失败: {}", e.getMessage());
                    return null;
                });

        } catch (Exception e) {
            log.warn("[Langfuse] 发送反馈失败: {}", e.getMessage());
        }
    }

    @Override
    public void sendThumbsUp(UUID runId, String comment) {
        sendFeedback(runId, "user_score", 1.0, comment);
    }

    @Override
    public void sendThumbsDown(UUID runId, String comment) {
        sendFeedback(runId, "user_score", 0.0, comment);
    }

    private String buildBasicAuth() {
        String credentials = config.getLangfuse().getPublicKey() + ":" + config.getLangfuse().getSecretKey();
        return "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes());
    }
}
