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
            // ✅ 使用 Chat API 支持 Tool Calling
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", modelName);
            requestBody.put("messages", List.of(
                Map.of("role", "user", "content", prompt)
            ));
            requestBody.put("temperature", temperature);
            requestBody.put("stream", false);
            
            String jsonBody = objectMapper.writeValueAsString(requestBody);
            
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/chat"))  // ✅ 使用 Chat API
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(timeout))
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();
            
            HttpResponse<String> response = httpClient.send(request, 
                HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() != 200) {
                throw new RuntimeException("Ollama API返回错误: " + response.body());
            }
            
            return extractChatResponse(response.body());
            
        } catch (Exception e) {
            log.error("[OllamaProvider] 生成文本失败", e);
            throw new RuntimeException("Ollama调用失败: " + e.getMessage(), e);
        }
    }
    
    @Override
    public String generateJson(String systemPrompt, String userPrompt, double temperature) {
        // ✅ 使用 Chat API + format 参数强制 JSON 输出
        try {
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", modelName);
            requestBody.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userPrompt)
            ));
            requestBody.put("temperature", temperature);
            requestBody.put("stream", false);
            requestBody.put("format", "json");  // 强制JSON格式
            
            String jsonBody = objectMapper.writeValueAsString(requestBody);
            
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/chat"))  // ✅ 使用 Chat API
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(timeout))
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();
            
            HttpResponse<String> response = httpClient.send(request, 
                HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() != 200) {
                throw new RuntimeException("Ollama API返回错误: " + response.body());
            }
            
            return extractChatResponse(response.body());
            
        } catch (Exception e) {
            log.error("[OllamaProvider] 生成JSON失败", e);
            throw new RuntimeException("Ollama JSON生成失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 生成文本并支持原生 Tool Calling
     * @param messages 消息列表
     * @param temperature 温度参数
     * @param tools 工具定义列表（OpenAI 兼容格式）
     * @return Chat API 完整响应（包含 tool_calls）
     */
    public Map<String, Object> generateWithTools(List<Map<String, Object>> messages, double temperature, List<Map<String, Object>> tools) {
        try {
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", modelName);
            requestBody.put("messages", messages);
            requestBody.put("temperature", temperature);
            requestBody.put("stream", false);
            
            // ✅ 禁用 thinking/reasoning 模式，强制直接返回 tool_calls
            requestBody.put("think", false);
            
            if (tools != null && !tools.isEmpty()) {
                requestBody.put("tools", tools);
                log.debug("[OllamaProvider] 启用 Tool Calling，工具数量: {}", tools.size());
            }
            
            String jsonBody = objectMapper.writeValueAsString(requestBody);
            
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/chat"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(timeout))
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();
            
            HttpResponse<String> response = httpClient.send(request, 
                HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() != 200) {
                throw new RuntimeException("Ollama API返回错误: " + response.body());
            }
            
            // 解析完整响应
            Map<String, Object> responseMap = objectMapper.readValue(response.body(), Map.class);
            return responseMap;
            
        } catch (Exception e) {
            log.error("[OllamaProvider] Tool Calling 失败", e);
            throw new RuntimeException("Ollama Tool Calling 失败: " + e.getMessage(), e);
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
     * 从 Ollama Generate API 响应中提取文本（已废弃，保留兼容）
     */
    @Deprecated
    private String extractResponse(String responseBody) throws IOException {
        Map<String, Object> responseMap = objectMapper.readValue(responseBody, Map.class);
        return (String) responseMap.getOrDefault("response", "");
    }
    
    /**
     * 从 Ollama Chat API 响应中提取文本
     */
    private String extractChatResponse(String responseBody) throws IOException {
        Map<String, Object> responseMap = objectMapper.readValue(responseBody, Map.class);
        
        // Chat API 返回结构: {"message": {"role": "assistant", "content": "..."}}
        Map<String, Object> message = (Map<String, Object>) responseMap.get("message");
        if (message != null) {
            return (String) message.getOrDefault("content", "");
        }
        
        // 兼容旧版本或错误情况
        return (String) responseMap.getOrDefault("response", "");
    }
}
