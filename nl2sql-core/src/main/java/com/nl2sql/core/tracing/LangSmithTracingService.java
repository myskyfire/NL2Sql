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
@ConditionalOnProperty(name = "tracing.provider", havingValue = "langsmith", matchIfMissing = true)
public class LangSmithTracingService implements TracingService {

    private final TracingConfig config;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    private static final String RUNS_ENDPOINT = "/runs";
    private static final String FEEDBACK_ENDPOINT = "/v1/feedback";

    @Autowired
    public LangSmithTracingService(TracingConfig config) {
        this.config = config;
        this.objectMapper = new ObjectMapper();
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(config.getLangsmith().getTimeout()))
            .build();

        if (config.isEnabled() && "langsmith".equalsIgnoreCase(config.getProvider())) {
            log.info("[LangSmith] Tracing 已启用, project={}, baseUrl={}", config.getProject(), config.getLangsmith().getBaseUrl());
        } else {
            log.info("[LangSmith] Tracing 未启用");
        }
    }

    @Override
    public boolean isEnabled() {
        return config.isEnabled() && "langsmith".equalsIgnoreCase(config.getProvider());
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

        sendRunCreate(span);
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

        sendRunEnd(run);
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

    private void sendRunCreate(TraceSpan span) {
        try {
            Map<String, Object> body = span.toCreateMap();
            String json = objectMapper.writeValueAsString(body);

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(config.getLangsmith().getBaseUrl() + RUNS_ENDPOINT))
                .header("Content-Type", "application/json")
                .header("X-Api-Key", config.getLangsmith().getApiKey())
                .timeout(Duration.ofSeconds(config.getLangsmith().getTimeout()))
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();

            httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(response -> {
                    if (response.statusCode() >= 400) {
                        log.warn("[LangSmith] 创建 Run 失败: status={}, body={}",
                            response.statusCode(),
                            response.body().substring(0, Math.min(200, response.body().length())));
                    }
                })
                .exceptionally(e -> {
                    log.warn("[LangSmith] 创建 Run 异步请求失败: {}", e.getMessage());
                    return null;
                });

        } catch (Exception e) {
            log.warn("[LangSmith] 创建 Run 失败: {}", e.getMessage());
        }
    }

    private void sendRunEnd(TraceSpan span) {
        try {
            Map<String, Object> body = span.toEndMap();
            String json = objectMapper.writeValueAsString(body);

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(config.getLangsmith().getBaseUrl() + RUNS_ENDPOINT + "/" + span.getId()))
                .header("Content-Type", "application/json")
                .header("X-Api-Key", config.getLangsmith().getApiKey())
                .timeout(Duration.ofSeconds(config.getLangsmith().getTimeout()))
                .method("PATCH", HttpRequest.BodyPublishers.ofString(json))
                .build();

            httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(response -> {
                    if (response.statusCode() >= 400) {
                        log.warn("[LangSmith] 结束 Run 失败: status={}, runId={}",
                            response.statusCode(), span.getId());
                    }
                })
                .exceptionally(e -> {
                    log.warn("[LangSmith] 结束 Run 异步请求失败: {}", e.getMessage());
                    return null;
                });

        } catch (Exception e) {
            log.warn("[LangSmith] 结束 Run 失败: {}", e.getMessage());
        }
    }

    @Override
    public void sendFeedback(UUID runId, String key, double score, String comment) {
        if (!isEnabled() || runId == null) return;
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("run_id", runId.toString());
            body.put("key", key);
            body.put("score", score);
            if (comment != null && !comment.isEmpty()) {
                body.put("comment", comment);
            }
            
            String json = objectMapper.writeValueAsString(body);
            
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(config.getLangsmith().getBaseUrl() + FEEDBACK_ENDPOINT))
                .header("Content-Type", "application/json")
                .header("X-Api-Key", config.getLangsmith().getApiKey())
                .timeout(Duration.ofSeconds(config.getLangsmith().getTimeout()))
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();

            httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(response -> {
                    if (response.statusCode() >= 400) {
                        log.warn("[LangSmith] 发送反馈失败: status={}, body={}",
                            response.statusCode(),
                            response.body().substring(0, Math.min(200, response.body().length())));
                    } else {
                        log.info("[LangSmith] 反馈已发送: runId={}, key={}, score={}", runId, key, score);
                    }
                })
                .exceptionally(e -> {
                    log.warn("[LangSmith] 发送反馈异步请求失败: {}", e.getMessage());
                    return null;
                });

        } catch (Exception e) {
            log.warn("[LangSmith] 发送反馈失败: {}", e.getMessage());
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
}
