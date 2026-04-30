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
    
    @Override
    public Map<String, Object> generateWithTools(List<Map<String, Object>> messages, double temperature, List<Map<String, Object>> tools) {
        // ✅ 重试机制：最多2次，超时时间逐级增加（60s → 90s）
        int maxRetries = 2;
        int[] timeouts = {60, 90};
        
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                log.info("[OpenAICompatibleProvider] 第{}/{}次尝试，超时={}秒", attempt, maxRetries, timeouts[attempt - 1]);
                
                Map<String, Object> requestBody = new HashMap<>();
                requestBody.put("model", modelName);
                requestBody.put("messages", messages);
                requestBody.put("temperature", temperature);
                
                // ✅ 禁用 thinking/reasoning 模式，强制直接返回 tool_calls
                requestBody.put("enable_thinking", false);
                
                // 添加工具定义
                if (tools != null && !tools.isEmpty()) {
                    requestBody.put("tools", tools);
                    log.debug("[OpenAICompatibleProvider] 启用 Tool Calling，工具数量: {}", tools.size());
                }
                
                String jsonBody = objectMapper.writeValueAsString(requestBody);
                
                HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/chat/completions"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(timeouts[attempt - 1]))
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody));
                
                if (!apiKey.isEmpty()) {
                    builder.header("Authorization", "Bearer " + apiKey);
                }
                
                HttpResponse<String> response = httpClient.send(builder.build(), 
                    HttpResponse.BodyHandlers.ofString());
                
                if (response.statusCode() != 200) {
                    throw new RuntimeException("API返回错误: " + response.body());
                }
                
                // 解析完整响应（包含 tool_calls）
                Map<String, Object> responseMap = objectMapper.readValue(response.body(), Map.class);
                
                // ✅ 将 OpenAI 格式转换为统一格式：{choices: [{message: {...}}]} → {message: {...}}
                List<Map<String, Object>> choices = (List<Map<String, Object>>) responseMap.get("choices");
                if (choices != null && !choices.isEmpty()) {
                    Map<String, Object> firstChoice = choices.get(0);
                    Map<String, Object> message = (Map<String, Object>) firstChoice.get("message");
                    
                    // 构建统一格式响应
                    Map<String, Object> unifiedResponse = new HashMap<>();
                    unifiedResponse.put("message", message);
                    
                    // 保留其他有用字段
                    if (responseMap.containsKey("usage")) {
                        unifiedResponse.put("usage", responseMap.get("usage"));
                    }
                    if (responseMap.containsKey("model")) {
                        unifiedResponse.put("model", responseMap.get("model"));
                    }
                    
                    log.info("[OpenAICompatibleProvider] ✅ 第{}次尝试成功", attempt);
                    return unifiedResponse;
                }
                
                throw new RuntimeException("响应中未找到 choices");
                
            } catch (java.net.http.HttpTimeoutException e) {
                log.warn("[OpenAICompatibleProvider] 第{}次尝试超时: {}", attempt, e.getMessage());
                if (attempt == maxRetries) {
                    log.error("[OpenAICompatibleProvider] ❌ 所有重试均失败");
                    throw new RuntimeException("LLM调用超时，已重试" + maxRetries + "次", e);
                }
                // 继续下一次重试
            } catch (Exception e) {
                log.error("[OpenAICompatibleProvider] Tool Calling 失败", e);
                throw new RuntimeException("Tool Calling 失败: " + e.getMessage(), e);
            }
        }
        
        // 理论上不会到达这里
        throw new RuntimeException("LLM调用失败");
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
