package com.nl2sql.core.llm;

import com.nl2sql.core.llm.provider.LLMProvider;
import com.nl2sql.core.llm.provider.LLMProviderManager;
import com.nl2sql.core.tracing.TraceSpan;
import com.nl2sql.core.tracing.TracingService;
import com.nl2sql.core.tracing.TracingContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.*;

@Slf4j
@Service
public class LLMService {
    
    @Autowired
    private LLMProviderManager providerManager;
    
    @Autowired(required = false)
    private com.nl2sql.core.llm.provider.OllamaProvider ollamaReasoningProvider;
    
    @Autowired(required = false)
    private com.nl2sql.core.llm.provider.OllamaProvider ollamaCodeProvider;

    @Autowired(required = false)
    private TracingService tracingService;
    
    private LLMProvider activeProvider;
    
    @PostConstruct
    public void init() {
        this.activeProvider = providerManager.getActiveProvider();
        log.info("[LLMService] 初始化完成，活跃提供者: {}", activeProvider.getName());
        checkHealth();
    }
    
    private void checkHealth() {
        var healthStatus = providerManager.healthCheck();
        
        boolean allHealthy = true;
        for (var entry : healthStatus.entrySet()) {
            String status = entry.getValue() ? "✅" : "❌";
            log.info("[LLMService] 提供者 {} 状态: {}", entry.getKey(), status);
            if (!entry.getValue()) {
                allHealthy = false;
            }
        }
        
        if (!allHealthy) {
            log.warn("[LLMService] 部分LLM提供者不可用，请检查配置");
        } else {
            log.info("[LLMService] 所有LLM提供者健康检查通过 ✅");
        }
    }
    
    public String generateSQL(String prompt) {
        TraceSpan run = startLlmTrace("generateSQL", Map.of("prompt", truncate(prompt, 500)));
        try {
            log.debug("发送提示词到LLM: {}", prompt);
            LLMProvider codeProvider = (ollamaCodeProvider != null) ? ollamaCodeProvider : activeProvider;
            String response = codeProvider.generate(prompt, 0.0);
            log.debug("LLM响应: {}", response);
            endLlmTrace(run, Map.of("response", truncate(response, 500)), null);
            return response.trim();
        } catch (Exception e) {
            log.error("[LLMService] SQL生成失败", e);
            endLlmTrace(run, null, e.getMessage());
            throw new RuntimeException("SQL生成失败: " + e.getMessage(), e);
        }
    }
    
    public String summarizeResult(String query, Object result) {
        String systemPrompt = "用3句话总结查询结果。请以JSON格式返回，包含summary字段。";
        String userPrompt = String.format("问题: %s\n查询结果: %s", query, result.toString());
        TraceSpan run = startLlmTrace("summarizeResult", Map.of("query", query));
        try {
            LLMProvider reasoningProvider = (ollamaReasoningProvider != null) ? ollamaReasoningProvider : activeProvider;
            String response = reasoningProvider.generateJson(systemPrompt, userPrompt, 0.7).trim();
            endLlmTrace(run, Map.of("summary", truncate(response, 300)), null);
            return response;
        } catch (Exception e) {
            log.error("[LLMService] 总结结果失败", e);
            endLlmTrace(run, null, e.getMessage());
            return "无法生成总结";
        }
    }
    
    public String clarifyQuestion(String query, String missingInfo) {
        String systemPrompt = "友好地追问缺失信息。";
        String userPrompt = String.format("用户问题: %s\n缺少信息: %s", query, missingInfo);
        TraceSpan run = startLlmTrace("clarifyQuestion", Map.of("query", query, "missingInfo", missingInfo));
        try {
            LLMProvider reasoningProvider = (ollamaReasoningProvider != null) ? ollamaReasoningProvider : activeProvider;
            String response = reasoningProvider.generate(systemPrompt + "\n\n" + userPrompt, 0.7).trim();
            endLlmTrace(run, Map.of("clarification", truncate(response, 300)), null);
            return response;
        } catch (Exception e) {
            log.error("[LLMService] 生成澄清问题失败", e);
            endLlmTrace(run, null, e.getMessage());
            return "请补充更多信息";
        }
    }
    
    public String classifyIntent(String query) {
        String systemPrompt = "分类: QUERY/CREATE/UPDATE/DELETE/OTHER，只返回类型。";
        String userPrompt = "用户问题: " + query;
        TraceSpan run = startLlmTrace("classifyIntent", Map.of("query", query));
        try {
            LLMProvider reasoningProvider = (ollamaReasoningProvider != null) ? ollamaReasoningProvider : activeProvider;
            String response = reasoningProvider.generate(systemPrompt + "\n\n" + userPrompt, 0.0).trim().toUpperCase();
            endLlmTrace(run, Map.of("intent", response), null);
            return response;
        } catch (Exception e) {
            log.error("[LLMService] 意图分类失败", e);
            endLlmTrace(run, null, e.getMessage());
            return "OTHER";
        }
    }
    
    public String generateAnswer(String prompt) {
        TraceSpan run = startLlmTrace("generateAnswer", Map.of("prompt", truncate(prompt, 500)));
        try {
            log.debug("发送提示词到LLM: {}", prompt);
            LLMProvider reasoningProvider = (ollamaReasoningProvider != null) ? ollamaReasoningProvider : activeProvider;
            String response = reasoningProvider.generate(prompt, 0.7);
            log.debug("LLM响应: {}", response);
            endLlmTrace(run, Map.of("answer", truncate(response, 500)), null);
            return response.trim();
        } catch (Exception e) {
            log.error("[LLMService] 答案生成失败", e);
            endLlmTrace(run, null, e.getMessage());
            return null;
        }
    }
    
    public Map<String, Object> generateWithTools(List<Map<String, Object>> messages, double temperature, List<Map<String, Object>> tools) {
        TraceSpan run = startLlmTrace("generateWithTools", Map.of("messageCount", messages != null ? messages.size() : 0, "toolCount", tools != null ? tools.size() : 0));
        try {
            log.debug("[LLMService] 调用原生 Tool Calling，工具数量: {}", tools != null ? tools.size() : 0);
            
            if (activeProvider instanceof com.nl2sql.core.llm.provider.OpenAICompatibleProvider) {
                Map<String, Object> result = activeProvider.generateWithTools(messages, temperature, tools);
                endLlmTrace(run, Map.of("hasToolCalls", result.get("message") != null), null);
                return result;
            }
            
            if (ollamaReasoningProvider != null) {
                Map<String, Object> result = ollamaReasoningProvider.generateWithTools(messages, temperature, tools);
                endLlmTrace(run, Map.of("hasToolCalls", result.get("message") != null), null);
                return result;
            }
            
            throw new UnsupportedOperationException("当前 Provider 不支持原生 Tool Calling");
        } catch (Exception e) {
            log.error("[LLMService] Tool Calling 失败", e);
            endLlmTrace(run, null, e.getMessage());
            throw new RuntimeException("Tool Calling 失败: " + e.getMessage(), e);
        }
    }
    
    public String generate(String systemPrompt, String userPrompt, String fullPrompt) {
        TraceSpan run = startLlmTrace("generate", Map.of("systemPrompt", truncate(systemPrompt, 200), "userPrompt", truncate(userPrompt, 200)));
        try {
            LLMProvider reasoningProvider = (ollamaReasoningProvider != null) ? ollamaReasoningProvider : activeProvider;
            String response = reasoningProvider.generate(systemPrompt + "\n\n" + userPrompt, 0.7).trim();
            endLlmTrace(run, Map.of("response", truncate(response, 500)), null);
            return response;
        } catch (Exception e) {
            log.error("[LLMService] 生成失败", e);
            endLlmTrace(run, null, e.getMessage());
            throw new RuntimeException("LLM 生成失败: " + e.getMessage(), e);
        }
    }

    public String generateWithJsonSchema(String systemPrompt, String userPrompt, Class<?> targetClass) {
        TraceSpan run = startLlmTrace("generateWithJsonSchema", Map.of("targetClass", targetClass.getSimpleName(), "userPrompt", truncate(userPrompt, 200)));
        try {
            String schemaHint = generateJsonSchemaHint(targetClass);
            String fullSystemPrompt = systemPrompt + "\n\n## JSON Schema\n" + schemaHint;
            LLMProvider reasoningProvider = (ollamaReasoningProvider != null) ? ollamaReasoningProvider : activeProvider;
            String response = reasoningProvider.generateJson(fullSystemPrompt, userPrompt, 0.3).trim();
            endLlmTrace(run, Map.of("response", truncate(response, 500)), null);
            return response;
        } catch (Exception e) {
            log.error("[LLMService] JSON Schema 生成失败", e);
            endLlmTrace(run, null, e.getMessage());
            throw new RuntimeException("JSON Schema 生成失败: " + e.getMessage(), e);
        }
    }

    private String generateJsonSchemaHint(Class<?> clazz) {
        StringBuilder sb = new StringBuilder();
        sb.append("输出JSON格式如下：\n");
        java.lang.reflect.Field[] fields = clazz.getDeclaredFields();
        sb.append("{\n");
        for (int i = 0; i < fields.length; i++) {
            java.lang.reflect.Field field = fields[i];
            String type = mapTypeToJsonType(field.getType());
            sb.append("  \"").append(field.getName()).append("\": ").append(type);
            if (i < fields.length - 1) sb.append(",");
            sb.append("\n");
        }
        sb.append("}");
        return sb.toString();
    }

    private String mapTypeToJsonType(Class<?> type) {
        if (type == String.class) return "\"string\"";
        if (type == boolean.class || type == Boolean.class) return "true/false";
        if (type == int.class || type == Integer.class || type == long.class || type == Long.class) return "number";
        if (type == List.class) return "[...]";
        if (type.isEnum()) return "\"enum\"";
        return "\"object\"";
    }

    public String getActiveProviderName() {
        return activeProvider.getName();
    }
    
    public void switchProvider(String providerName) {
        providerManager.setActiveProvider(providerName);
        this.activeProvider = providerManager.getActiveProvider();
        log.info("[LLMService] 已切换LLM提供者: {}", providerName);
    }
    
    public java.util.Map<String, Boolean> getProviderHealthStatus() {
        return providerManager.healthCheck();
    }

    private TraceSpan startLlmTrace(String name, Map<String, Object> inputs) {
        if (tracingService == null || !tracingService.isEnabled()) return null;
        try {
            return tracingService.traceLlm(name, inputs, TracingContext.currentRunId());
        } catch (Exception e) {
            log.debug("[LangSmith] startLlmTrace 失败: {}", e.getMessage());
            return null;
        }
    }

    private void endLlmTrace(TraceSpan run, Map<String, Object> outputs, String error) {
        if (tracingService == null || run == null) return;
        try {
            tracingService.endRun(run, outputs, error);
        } catch (Exception e) {
            log.debug("[LangSmith] endLlmTrace 失败: {}", e.getMessage());
        }
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
