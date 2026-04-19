package com.nl2sql.core.llm.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 测试 Ollama Chat API 的原生 Tool Calling 功能
 */
public class OllamaToolCallingTest {
    
    private static final String OLLAMA_URL = "http://localhost:11434";
    private static final String MODEL = "qwen3:8b";
    
    @Test
    public void testNativeToolCalling() throws Exception {
        System.out.println("========== 测试 qwen3:8b 原生 Tool Calling ==========");
        
        // 构建请求体
        Map<String, Object> requestBody = Map.of(
            "model", MODEL,
            "messages", List.of(
                Map.of("role", "user", "content", "查询昨天的订单总额")
            ),
            "stream", false,
            "tools", List.of(
                Map.of(
                    "type", "function",
                    "function", Map.of(
                        "name", "execute_sql",
                        "description", "执行SQL查询",
                        "parameters", Map.of(
                            "type", "object",
                            "properties", Map.of(
                                "sql", Map.of("type", "string", "description", "SQL语句")
                            ),
                            "required", List.of("sql")
                        )
                    )
                )
            )
        );
        
        ObjectMapper mapper = new ObjectMapper();
        String jsonBody = mapper.writeValueAsString(requestBody);
        
        System.out.println("请求体: " + jsonBody);
        
        // 发送请求
        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();
        
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(OLLAMA_URL + "/api/chat"))
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(60))
            .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
            .build();
        
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        
        System.out.println("状态码: " + response.statusCode());
        System.out.println("响应: " + response.body());
        
        // 解析响应
        Map<String, Object> responseMap = mapper.readValue(response.body(), Map.class);
        Map<String, Object> message = (Map<String, Object>) responseMap.get("message");
        
        if (message != null) {
            List<Map<String, Object>> toolCalls = (List<Map<String, Object>>) message.get("tool_calls");
            
            if (toolCalls != null && !toolCalls.isEmpty()) {
                System.out.println("\n✅ Tool Calling 成功！");
                System.out.println("工具调用数量: " + toolCalls.size());
                
                for (Map<String, Object> toolCall : toolCalls) {
                    Map<String, Object> function = (Map<String, Object>) toolCall.get("function");
                    System.out.println("工具名: " + function.get("name"));
                    System.out.println("参数: " + function.get("arguments"));
                }
            } else {
                System.out.println("\n❌ 未检测到 tool_calls");
                System.out.println("回复内容: " + message.get("content"));
            }
        }
    }
}
