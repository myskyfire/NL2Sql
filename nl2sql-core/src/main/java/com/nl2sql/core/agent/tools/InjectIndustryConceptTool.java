package com.nl2sql.core.agent.tools;

import com.nl2sql.core.llm.extension.IndustryConceptExtension;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
public class InjectIndustryConceptTool {

    @Autowired(required = false)
    private ApplicationContext applicationContext;

    @Tool("注入行业概念扩展提示词。检测已注册的IndustryConceptExtension实现，将行业特定术语和规则注入到用户问题中，增强SQL生成准确性。输入用户问题和数据源ID，返回增强后的问题")
    public String injectIndustryConcept(
        @P("用户原始问题") String question,
        @P("数据源ID") Long datasourceId
    ) {
        try {
            log.info("[InjectIndustryConceptTool] 注入行业概念: question={}, datasourceId={}", question, datasourceId);

            if (question == null || question.trim().isEmpty()) {
                Map<String, Object> data = new HashMap<>();
                data.put("enhancedQuestion", question);
                data.put("conceptInjected", false);
                data.put("extensionName", null);
                return ToolResponseBuilder.success("data")
                    .withData(data)
                    .addMetadata("toolName", "inject_industry_concept")
                    .build();
            }

            Collection<IndustryConceptExtension> conceptExtensions =
                applicationContext != null ?
                applicationContext.getBeansOfType(IndustryConceptExtension.class).values() :
                java.util.Collections.emptyList();

            String enhancedQuestion = question;
            String extensionName = null;
            boolean conceptInjected = false;

            if (!conceptExtensions.isEmpty()) {
                for (IndustryConceptExtension extension : conceptExtensions) {
                    try {
                        String enhancedPrompt = extension.enhancePromptBeforeGeneration(null, question, datasourceId);
                        if (enhancedPrompt != null && !enhancedPrompt.trim().isEmpty()) {
                            log.info("[InjectIndustryConceptTool] 行业扩展点增强Prompt: {}", extension.getClass().getSimpleName());
                            enhancedQuestion = question + "\n\n" + enhancedPrompt;
                            extensionName = extension.getClass().getSimpleName();
                            conceptInjected = true;
                            break;
                        }
                    } catch (Exception e) {
                        log.warn("[InjectIndustryConceptTool] 行业扩展点执行失败: {}, error: {}",
                            extension.getClass().getSimpleName(), e.getMessage());
                    }
                }
            }

            Map<String, Object> data = new HashMap<>();
            data.put("enhancedQuestion", enhancedQuestion);
            data.put("conceptInjected", conceptInjected);
            data.put("extensionName", extensionName);

            log.info("[InjectIndustryConceptTool] 注入完成: conceptInjected={}, extensionName={}", conceptInjected, extensionName);

            return ToolResponseBuilder.success("data")
                .withData(data)
                .addMetadata("toolName", "inject_industry_concept")
                .build();

        } catch (Exception e) {
            log.error("[InjectIndustryConceptTool] 注入失败", e);
            return ToolResponseBuilder.error("INJECTION_ERROR", e.getMessage())
                .addMetadata("toolName", "inject_industry_concept")
                .build();
        }
    }
}
