package com.nl2sql.core.tracing;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

@Slf4j
@Service
public class LangSmithTracingService {

    private final LangSmithConfig config;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    private static final String RUNS_ENDPOINT = "/v1/runs";

    @Autowired
    public LangSmithTracingService(LangSmithConfig config) {
        this.config = config;
        this.objectMapper = new ObjectMapper();
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(config.getTimeout()))
            .build();

        if (config.isValid()) {
            log.info("[LangSmith] Tracing 已启用, project={}, baseUrl={}", config.getProject(), config.getBaseUrl());
        } else {
            log.info("[LangSmith] Tracing 未启用 (enabled={}, apiKey={})",
                config.isEnabled(), config.getApiKey() != null ? "已配置" : "未配置");
        }
    }

    public boolean isEnabled() {
        return config.isValid();
    }

    public String getProject() {
        return config.getProject();
    }

    public LangSmithRun startRun(String name, LangSmithRun.RunType runType,
                                  Map<String, Object> inputs, UUID parentRunId) {
        return startRun(name, runType, inputs, parentRunId, null, null);
    }

    public LangSmithRun startRun(String name, LangSmithRun.RunType runType,
                                  Map<String, Object> inputs, UUID parentRunId,
                                  Map<String, Object> extra, List<String> tags) {
        if (!isEnabled()) {
            return LangSmithRun.builder()
                .id(UUID.randomUUID())
                .name(name)
                .runType(runType)
                .build();
        }

        LangSmithRun run = LangSmithRun.builder()
            .name(name)
            .runType(runType)
            .inputs(inputs)
            .parentRunId(parentRunId)
            .sessionName(config.getProject())
            .startTime(java.time.Instant.now())
            .extra(extra)
            .tags(tags)
            .build();

        sendRunCreate(run);
        return run;
    }

    public void endRun(LangSmithRun run, Map<String, Object> outputs) {
        endRun(run, outputs, null);
    }

    public void endRun(LangSmithRun run, Map<String, Object> outputs, String error) {
        if (!isEnabled() || run == null) return;

        run.setOutputs(outputs);
        run.setEndTime(java.time.Instant.now());
        if (error != null) {
            run.setError(error);
        }

        sendRunEnd(run);
    }

    public LangSmithRun traceLlm(String name, Map<String, Object> inputs, UUID parentRunId) {
        return startRun(name, LangSmithRun.RunType.llm, inputs, parentRunId);
    }

    public LangSmithRun traceChain(String name, Map<String, Object> inputs, UUID parentRunId) {
        return startRun(name, LangSmithRun.RunType.chain, inputs, parentRunId);
    }

    public LangSmithRun traceTool(String name, Map<String, Object> inputs, UUID parentRunId) {
        return startRun(name, LangSmithRun.RunType.tool, inputs, parentRunId);
    }

    private void sendRunCreate(LangSmithRun run) {
        try {
            Map<String, Object> body = run.toCreateMap();
            String json = objectMapper.writeValueAsString(body);

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(config.getBaseUrl() + RUNS_ENDPOINT))
                .header("Content-Type", "application/json")
                .header("X-Api-Key", config.getApiKey())
                .timeout(Duration.ofSeconds(config.getTimeout()))
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

    private void sendRunEnd(LangSmithRun run) {
        try {
            Map<String, Object> body = run.toEndMap();
            String json = objectMapper.writeValueAsString(body);

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(config.getBaseUrl() + RUNS_ENDPOINT + "/" + run.getId()))
                .header("Content-Type", "application/json")
                .header("X-Api-Key", config.getApiKey())
                .timeout(Duration.ofSeconds(config.getTimeout()))
                .method("PATCH", HttpRequest.BodyPublishers.ofString(json))
                .build();

            httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(response -> {
                    if (response.statusCode() >= 400) {
                        log.warn("[LangSmith] 结束 Run 失败: status={}, runId={}",
                            response.statusCode(), run.getId());
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
}
