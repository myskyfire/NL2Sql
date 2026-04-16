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
import java.util.List;
import java.util.Map;

/**
 * OpenAI兼容API提供者
 * 
 * 支持企业内部部署的兼容OpenAI API的LLM服务
 * 如：ChatGLM、Qwen、Baichuan等私有化部署版本
 */
@Slf4j
public class OpenAICompatibleProvider implements LLMProvider {
    
    private final String name;
    private final String baseUrl;
    private final String modelName;
    private final String apiKey;
    private final int timeout;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    
    public OpenAICompatibleProvider(String name, String baseUrl, String modelName, 
                                   String apiKey, int timeout) {
        this.name = name;
        this.baseUrl = baseUrl;
        this.modelName = modelName;
        this.apiKey = apiKey;
        this.timeout = timeout;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(timeout))
            .build();
        this.objectMapper = new ObjectMapper();
        
        log.info("[OpenAICompatibleProvider] 初始化完成: name={}, model={}, url={}", 
            name, modelName, baseUrl);
    }
    
    @Override
    public String getName() {
        return name;
    }
    
    @Override
    public boolean isAvailable() {
        try {
            // 调用OpenAI兼容的健康检查端点
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/models"))
                .timeout(Duration.ofSeconds(5))
                .GET();
            
            if (!apiKey.isEmpty()) {
                builder.header("Authorization", "Bearer " + apiKey);
            }
            
            HttpResponse<String> response = httpClient.send(builder.build(), 
                HttpResponse.BodyHandlers.ofString());
            
            return response.statusCode() == 200;
        } catch (Exception e) {
            log.warn("[OpenAICompatibleProvider] 健康检查失败: {}", e.getMessage());
            return false;
        }
    }
    
    @Override
    public String generate(String prompt, double temperature) {
        try {
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", modelName);
            requestBody.put("messages", List.of(
                Map.of("role", "user", "content", prompt)
            ));
            requestBody.put("temperature", temperature);
            
            String jsonBody = objectMapper.writeValueAsString(requestBody);
            
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/chat/completions"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(timeout))
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody));
            
            if (!apiKey.isEmpty()) {
                builder.header("Authorization", "Bearer " + apiKey);
            }
            
            HttpResponse<String> response = httpClient.send(builder.build(), 
                HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() != 200) {
                throw new RuntimeException("API返回错误: " + response.body());
            }
            
            return extractContent(response.body());
            
        } catch (Exception e) {
            log.error("[OpenAICompatibleProvider] 生成文本失败", e);
            throw new RuntimeException("API调用失败: " + e.getMessage(), e);
        }
    }
    
    @Override
    public String generateJson(String systemPrompt, String userPrompt, double temperature) {
        try {
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", modelName);
            requestBody.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userPrompt)
            ));
            requestBody.put("temperature", temperature);
            requestBody.put("response_format", Map.of("type", "json_object"));
            
            String jsonBody = objectMapper.writeValueAsString(requestBody);
            
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/chat/completions"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(timeout))
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody));
            
            if (!apiKey.isEmpty()) {
                builder.header("Authorization", "Bearer " + apiKey);
            }
            
            HttpResponse<String> response = httpClient.send(builder.build(), 
                HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() != 200) {
                throw new RuntimeException("API返回错误: " + response.body());
            }
            
            return extractContent(response.body());
            
        } catch (Exception e) {
            log.error("[OpenAICompatibleProvider] 生成JSON失败", e);
            throw new RuntimeException("JSON生成失败: " + e.getMessage(), e);
        }
    }
    
    @Override
    public Map<String, Object> getConfig() {
        Map<String, Object> config = new HashMap<>();
        config.put("type", "openai-compatible");
        config.put("name", name);
        config.put("baseUrl", baseUrl);
        config.put("modelName", modelName);
        config.put("timeout", timeout);
        config.put("hasApiKey", !apiKey.isEmpty());
        return config;
    }
    
    /**
     * 从OpenAI兼容响应中提取内容
     */
    private String extractContent(String responseBody) throws IOException {
        Map<String, Object> responseMap = objectMapper.readValue(responseBody, Map.class);
        List<Map<String, Object>> choices = (List<Map<String, Object>>) responseMap.get("choices");
        
        if (choices != null && !choices.isEmpty()) {
            Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
            return (String) message.getOrDefault("content", "");
        }
        
        throw new IOException("响应中未找到choices");
    }
}
