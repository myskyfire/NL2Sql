package com.nl2sql.core.llm.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Ollama LLM提供者实现
 * 
 * 支持企业内部部署的Ollama服务
 * 模型示例：qwen2.5-coder, qwen3, llama3, chatglm等
 */
@Slf4j
public class OllamaProvider implements LLMProvider {
    
    private final String baseUrl;
    private final String modelName;
    private final int timeout;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    
    public OllamaProvider(String baseUrl, String modelName, int timeout) {
        this.baseUrl = baseUrl;
        this.modelName = modelName;
        this.timeout = timeout;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(timeout))
            .build();
        this.objectMapper = new ObjectMapper();
        
        log.info("[OllamaProvider] 初始化完成: model={}, url={}, timeout={}s", 
            modelName, baseUrl, timeout);
    }
    
    @Override
    public String getName() {
        return "ollama";
    }
    
    @Override
    public boolean isAvailable() {
        try {
            // 调用Ollama的健康检查API
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/tags"))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
            
            HttpResponse<String> response = httpClient.send(request, 
                HttpResponse.BodyHandlers.ofString());
            
            return response.statusCode() == 200;
        } catch (Exception e) {
            log.warn("[OllamaProvider] 健康检查失败: {}", e.getMessage());
            return false;
        }
    }
    
    @Override
    public String generate(String prompt, double temperature) {
        try {
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", modelName);
            requestBody.put("prompt", prompt);
            requestBody.put("temperature", temperature);
            requestBody.put("stream", false);
            
            String jsonBody = objectMapper.writeValueAsString(requestBody);
            
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/generate"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(timeout))
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();
            
            HttpResponse<String> response = httpClient.send(request, 
                HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() != 200) {
                throw new RuntimeException("Ollama API返回错误: " + response.body());
            }
            
            return extractResponse(response.body());
            
        } catch (Exception e) {
            log.error("[OllamaProvider] 生成文本失败", e);
            throw new RuntimeException("Ollama调用失败: " + e.getMessage(), e);
        }
    }
    
    @Override
    public String generateJson(String systemPrompt, String userPrompt, double temperature) {
        // Ollama支持format参数来强制JSON输出
        try {
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", modelName);
            requestBody.put("prompt", systemPrompt + "\n\n" + userPrompt);
            requestBody.put("temperature", temperature);
            requestBody.put("stream", false);
            requestBody.put("format", "json");  // 强制JSON格式
            
            String jsonBody = objectMapper.writeValueAsString(requestBody);
            
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/generate"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(timeout))
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();
            
            HttpResponse<String> response = httpClient.send(request, 
                HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() != 200) {
                throw new RuntimeException("Ollama API返回错误: " + response.body());
            }
            
            return extractResponse(response.body());
            
        } catch (Exception e) {
            log.error("[OllamaProvider] 生成JSON失败", e);
            throw new RuntimeException("Ollama JSON生成失败: " + e.getMessage(), e);
        }
    }
    
    @Override
    public Map<String, Object> getConfig() {
        Map<String, Object> config = new HashMap<>();
        config.put("type", "ollama");
        config.put("baseUrl", baseUrl);
        config.put("modelName", modelName);
        config.put("timeout", timeout);
        return config;
    }
    
    /**
     * 从Ollama响应中提取文本
     */
    private String extractResponse(String responseBody) throws IOException {
        Map<String, Object> responseMap = objectMapper.readValue(responseBody, Map.class);
        return (String) responseMap.getOrDefault("response", "");
    }
}
