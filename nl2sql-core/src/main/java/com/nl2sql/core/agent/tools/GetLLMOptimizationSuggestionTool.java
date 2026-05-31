package com.nl2sql.core.agent.tools;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nl2sql.core.llm.LLMService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class GetLLMOptimizationSuggestionTool {

    @Autowired(required = false)
    private LLMService llmService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Tool("针对中风险SQL，调用LLM获取优化建议（不重新生成SQL，仅提供瓶颈分析和优化方向）。输入SQL、用户问题和EXPLAIN风险点，返回bottleneck/suggestion/expected_improvement")
    public String getLLMOptimizationSuggestion(
        @P("待优化的SQL语句") String sql,
        @P("用户的原始问题") String question,
        @P("EXPLAIN分析发现的风险点，JSON数组格式") String explainRisks
    ) {
        try {
            log.info("[GetLLMOptimizationSuggestionTool] 获取LLM优化建议: sql={}", sql != null ? sql.substring(0, Math.min(80, sql.length())) : "null");

            if (llmService == null) {
                return ToolResponseBuilder.error("LLM_UNAVAILABLE", "LLM服务未配置，无法获取优化建议")
                    .addMetadata("toolName", "get_llm_optimization_suggestion")
                    .build();
            }

            if (sql == null || sql.trim().isEmpty()) {
                return ToolResponseBuilder.error("INVALID_INPUT", "SQL不能为空")
                    .addMetadata("toolName", "get_llm_optimization_suggestion")
                    .build();
            }

            StringBuilder sb = new StringBuilder();
            sb.append("你是一个数据库优化专家。以下SQL存在中等风险，请提供优化建议。\n\n");
            sb.append("用户问题：").append(question).append("\n\n");
            sb.append("原始 SQL：\n").append(sql).append("\n\n");

            if (explainRisks != null && !explainRisks.trim().isEmpty()) {
                sb.append("EXPLAIN 分析发现的风险点：\n");
                try {
                    List<String> risks = objectMapper.readValue(explainRisks, new TypeReference<List<String>>() {});
                    for (String risk : risks) {
                        sb.append("  - ").append(risk).append("\n");
                    }
                } catch (Exception e) {
                    sb.append("  - ").append(explainRisks).append("\n");
                }
            }

            sb.append("\n请提供优化建议（不要重新生成 SQL，只给建议）：\n");
            sb.append("1. 指出主要性能瓶颈\n");
            sb.append("2. 给出具体的优化建议（如添加索引、改写 WHERE 条件等）\n");
            sb.append("3. 说明预期优化效果\n\n");
            sb.append("返回JSON格式：\n");
            sb.append("{\n");
            sb.append("  \"bottleneck\": \"主要性能瓶颈\",\n");
            sb.append("  \"suggestion\": \"具体优化建议\",\n");
            sb.append("  \"expected_improvement\": \"预期优化效果\"\n");
            sb.append("}");

            String response = llmService.generateAnswer(sb.toString());
            Map<String, Object> suggestion = parseJsonResponse(response);

            Map<String, Object> data = new HashMap<>();
            if (suggestion != null) {
                data.put("bottleneck", suggestion.getOrDefault("bottleneck", ""));
                data.put("suggestion", suggestion.getOrDefault("suggestion", ""));
                data.put("expectedImprovement", suggestion.getOrDefault("expected_improvement", ""));
            } else {
                data.put("bottleneck", "");
                data.put("suggestion", "LLM返回格式异常，无法解析优化建议");
                data.put("expectedImprovement", "");
            }

            log.info("[GetLLMOptimizationSuggestionTool] 优化建议获取完成");

            return ToolResponseBuilder.success("data")
                .withData(data)
                .addMetadata("toolName", "get_llm_optimization_suggestion")
                .build();

        } catch (Exception e) {
            log.error("[GetLLMOptimizationSuggestionTool] 获取优化建议失败", e);
            return ToolResponseBuilder.error("SUGGESTION_ERROR", e.getMessage())
                .addMetadata("toolName", "get_llm_optimization_suggestion")
                .build();
        }
    }

    private Map<String, Object> parseJsonResponse(String response) {
        try {
            int jsonStart = response.indexOf("{");
            int jsonEnd = response.lastIndexOf("}");
            if (jsonStart >= 0 && jsonEnd > jsonStart) {
                String jsonStr = response.substring(jsonStart, jsonEnd + 1);
                return objectMapper.readValue(jsonStr, new TypeReference<Map<String, Object>>() {});
            }
        } catch (Exception e) {
            log.warn("[GetLLMOptimizationSuggestionTool] JSON解析失败: {}", e.getMessage());
        }
        return null;
    }
}
