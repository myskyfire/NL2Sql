package com.nl2sql.core.agent.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.nl2sql.core.agent.planner.QueryPlan;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
public class WorkflowLoader {

    private final ObjectMapper yamlMapper;

    public WorkflowLoader(ObjectMapper jsonMapper) {
        this.yamlMapper = new ObjectMapper(new YAMLFactory());
    }

    public WorkflowDefinition loadWorkflow(QueryPlan.ComplexityLevel complexity) {
        String resourcePath;
        switch (complexity) {
            case COMPLEX:
                resourcePath = "workflow/complex-query.yml";
                break;
            case MODERATE:
                resourcePath = "workflow/moderate-query.yml";
                break;
            case SIMPLE:
            default:
                return null;
        }

        try {
            ClassPathResource resource = new ClassPathResource(resourcePath);
            if (!resource.exists()) {
                log.warn("[WorkflowLoader] 工作流配置文件不存在: {}", resourcePath);
                return null;
            }

            try (InputStream is = resource.getInputStream()) {
                return yamlMapper.readValue(is, WorkflowDefinition.class);
            }
        } catch (Exception e) {
            log.error("[WorkflowLoader] 加载工作流配置失败: {}", resourcePath, e);
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    public WorkflowDefinition loadSkillWorkflow(String skillName) {
        String skillDir = resolveSkillDir(skillName);
        if (skillDir == null) {
            log.warn("[WorkflowLoader] 未找到 Skill 目录: {}", skillName);
            return null;
        }

        String yamlPath = "skills/" + skillDir + "/SKILL.md";
        try {
            ClassPathResource resource = new ClassPathResource(yamlPath);
            if (!resource.exists()) {
                log.warn("[WorkflowLoader] SKILL.md 不存在: {}", yamlPath);
                return null;
            }

            String content;
            try (InputStream is = resource.getInputStream()) {
                content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }

            String yamlContent = extractYamlFrontMatter(content);
            if (yamlContent == null) {
                log.warn("[WorkflowLoader] SKILL.md 无 YAML front matter: {}", yamlPath);
                return null;
            }

            Map<String, Object> frontMatter = yamlMapper.readValue(yamlContent, Map.class);
            Map<String, Object> workflowMap = (Map<String, Object>) frontMatter.get("workflow");
            if (workflowMap == null) {
                log.warn("[WorkflowLoader] SKILL.md 无 workflow 定义: {}", yamlPath);
                return null;
            }

            List<Map<String, Object>> stepsList = (List<Map<String, Object>>) workflowMap.get("steps");
            if (stepsList == null || stepsList.isEmpty()) {
                log.warn("[WorkflowLoader] SKILL.md workflow 无 steps: {}", yamlPath);
                return null;
            }

            WorkflowDefinition definition = new WorkflowDefinition();
            definition.setName(skillName);
            definition.setDescription((String) workflowMap.getOrDefault("description", "Skill workflow: " + skillName));

            List<WorkflowStep> steps = new ArrayList<>();
            for (Map<String, Object> stepMap : stepsList) {
                WorkflowStep step = new WorkflowStep();
                step.setId((String) stepMap.get("id"));

                String action = (String) stepMap.get("action");
                step.setAction(action);
                if (action != null) {
                    step.setType(action);
                } else {
                    step.setType((String) stepMap.getOrDefault("type", "skip"));
                }

                String toolValue = (String) stepMap.get("tool");
                step.setTool(toolValue);
                log.debug("[WorkflowLoader] 解析步骤 {} 的 tool 字段: {}", step.getId(), toolValue);

                String skillValue = (String) stepMap.get("skill");
                step.setSkill(skillValue);
                if (skillValue != null) {
                    log.debug("[WorkflowLoader] 解析步骤 {} 的 skill 字段: {}", step.getId(), skillValue);
                }

                step.setOutputVar((String) stepMap.get("output_var"));

                Map<String, Object> input = (Map<String, Object>) stepMap.get("input");
                step.setToolInput(input);

                Map<String, Object> output = (Map<String, Object>) stepMap.get("output");
                step.setOutput(output);

                String condition = (String) stepMap.get("condition");
                step.setCondition(condition);

                String onNext = (String) stepMap.get("onNext");
                if (onNext == null) {
                    onNext = (String) stepMap.get("on_next");
                }
                step.setOnNext(onNext);

                String onConditionTrue = (String) stepMap.get("on_condition_true");
                if (onConditionTrue == null) {
                    onConditionTrue = (String) stepMap.get("onConditionTrue");
                }
                step.setOnConditionTrue(onConditionTrue);

                String onConditionFalse = (String) stepMap.get("on_condition_false");
                if (onConditionFalse == null) {
                    onConditionFalse = (String) stepMap.get("onConditionFalse");
                }
                step.setOnConditionFalse(onConditionFalse);

                steps.add(step);
            }
            definition.setSteps(steps);

            log.info("[WorkflowLoader] 从 SKILL.md 加载 workflow: {}, 步骤数: {}", skillName, steps.size());
            return definition;

        } catch (Exception e) {
            log.error("[WorkflowLoader] 加载 Skill workflow 失败: {}", yamlPath, e);
            return null;
        }
    }

    private String resolveSkillDir(String skillName) {
        switch (skillName) {
            case "execute_standard_query": return "standard-query";
            case "sql_validate_execute": return "sql-validate-execute";
            case "sql_performance_analysis": return "sql-performance-analysis";
            case "generate_report_with_insights": return "report-with-insights";
            case "summarize_result": return "summarize-result";
            case "hybrid-example": return "hybrid-example";
            default: return skillName;
        }
    }

    private String extractYamlFrontMatter(String content) {
        if (content == null || !content.startsWith("---")) return null;

        int endIndex = content.indexOf("---", 3);
        if (endIndex == -1) return null;

        return content.substring(3, endIndex).trim();
    }
}
