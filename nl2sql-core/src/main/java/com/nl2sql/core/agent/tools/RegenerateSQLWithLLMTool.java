package com.nl2sql.core.agent.tools;

import com.nl2sql.common.util.MarkdownUtils;
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
public class RegenerateSQLWithLLMTool {

    @Autowired(required = false)
    private LLMService llmService;

    @Tool("针对高风险SQL，调用LLM重新生成优化后的SQL。输入原始SQL、用户问题和EXPLAIN风险点，返回优化后的SQL语句")
    public String regenerateSQLWithLLM(
        @P("原始的高风险SQL语句") String originalSql,
        @P("用户的原始问题") String question,
        @P("EXPLAIN分析发现的风险点，JSON数组格式") String explainRisks,
        @P("EXPLAIN分析给出的优化建议，JSON数组格式（可选）") String explainSuggestions
    ) {
        try {
            log.info("[RegenerateSQLWithLLMTool] LLM重新生成SQL: originalSql={}", originalSql != null ? originalSql.substring(0, Math.min(80, originalSql.length())) : "null");

            if (llmService == null) {
                return ToolResponseBuilder.error("LLM_UNAVAILABLE", "LLM服务未配置，无法重新生成SQL")
                    .addMetadata("toolName", "regenerate_sql_with_llm")
                    .build();
            }

            if (originalSql == null || originalSql.trim().isEmpty()) {
                return ToolResponseBuilder.error("INVALID_INPUT", "原始SQL不能为空")
                    .addMetadata("toolName", "regenerate_sql_with_llm")
                    .build();
            }

            StringBuilder sb = new StringBuilder();
            sb.append("你是一个数据库专家。之前生成的SQL存在高风险，请重新生成一个更优化的SQL。\n\n");
            sb.append("用户问题：").append(question).append("\n\n");
            sb.append("原始 SQL（有高风险）：\n").append(originalSql).append("\n\n");

            sb.append("EXPLAIN 分析发现的风险：\n");
            if (explainRisks != null && !explainRisks.trim().isEmpty()) {
                try {
                    com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                    List<String> risks = mapper.readValue(explainRisks, mapper.getTypeFactory().constructCollectionType(List.class, String.class));
                    for (String risk : risks) {
                        sb.append("  - ").append(risk).append("\n");
                    }
                } catch (Exception e) {
                    sb.append("  - ").append(explainRisks).append("\n");
                }
            }

            if (explainSuggestions != null && !explainSuggestions.trim().isEmpty()) {
                sb.append("\n优化建议：\n");
                try {
                    com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                    List<String> suggestions = mapper.readValue(explainSuggestions, mapper.getTypeFactory().constructCollectionType(List.class, String.class));
                    for (String suggestion : suggestions) {
                        sb.append("  - ").append(suggestion).append("\n");
                    }
                } catch (Exception e) {
                    sb.append("  - ").append(explainSuggestions).append("\n");
                }
            }

            sb.append("\n任务：重新生成一个SQL，要求：\n");
            sb.append("1. 避免上述风险（如全表扫描、缺少索引等）\n");
            sb.append("2. 保持查询语义不变\n");
            sb.append("3. 如果无法优化，请说明原因并返回原 SQL\n");
            sb.append("4. 只输出 SQL 语句，不要包含其他内容\n\n");
            sb.append("新 SQL：");

            String response = llmService.generateSQL(sb.toString());
            String optimizedSql = extractSQLFromResponse(response);

            Map<String, Object> data = new HashMap<>();
            if (optimizedSql != null && !optimizedSql.trim().isEmpty()) {
                data.put("optimizedSql", optimizedSql);
                data.put("originalSql", originalSql);
                data.put("regenerationSuccess", true);
                log.info("[RegenerateSQLWithLLMTool] SQL重新生成完成: {}", optimizedSql.substring(0, Math.min(80, optimizedSql.length())));
            } else {
                data.put("optimizedSql", null);
                data.put("originalSql", originalSql);
                data.put("regenerationSuccess", false);
                log.warn("[RegenerateSQLWithLLMTool] LLM未能生成有效SQL");
            }

            return ToolResponseBuilder.success("data")
                .withData(data)
                .addMetadata("toolName", "regenerate_sql_with_llm")
                .build();

        } catch (Exception e) {
            log.error("[RegenerateSQLWithLLMTool] 重新生成SQL失败", e);
            return ToolResponseBuilder.error("REGENERATION_ERROR", e.getMessage())
                .addMetadata("toolName", "regenerate_sql_with_llm")
                .build();
        }
    }

    private String extractSQLFromResponse(String response) {
        if (response == null || response.isEmpty()) {
            return null;
        }

        String cleaned = MarkdownUtils.cleanSQL(response);
        cleaned = cleaned.trim();

        if (cleaned.contains("\n")) {
            String[] lines = cleaned.split("\n");
            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.toUpperCase().startsWith("SELECT")
                    || trimmed.toUpperCase().startsWith("WITH")
                    || trimmed.toUpperCase().startsWith("INSERT")
                    || trimmed.toUpperCase().startsWith("UPDATE")
                    || trimmed.toUpperCase().startsWith("DELETE")) {
                    return trimmed;
                }
            }
        }

        return cleaned;
    }
}
