package com.nl2sql.core.agent.planner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nl2sql.core.llm.LLMService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Planner Agent - 将用户自然语言问题拆解为结构化的 QueryPlan
 *
 * 不执行查询，只生成计划。供 WorkflowEngine 和 Worker 执行。
 */
@Slf4j
@Component
public class PlannerAgent {

    private final LLMService llmService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String PLANNER_SYSTEM_PROMPT =
        "你是SQL查询规划师。根据用户的自然语言问题，输出结构化的QueryPlan（JSON格式）。\n\n" +
        "## 规则\n" +
        "1. 先确定数据源（datasourceId已在问题中指定或为null）\n" +
        "2. 分析涉及的表和需要的字段\n" +
        "3. 拆解SQL执行步骤（简单查询1步，多步聚合查询多步）\n" +
        "4. 判断是否需要图表（用户提到趋势/分布/图/可视化等关键词时）\n" +
        "5. 判断是否需要AI总结（用户提到分析/总结/解读/洞察等关键词时）\n" +
        "6. 评估复杂度：SIMPLE=单表简单查询，MODERATE=多表JOIN，COMPLEX=多步聚合+图表+总结\n\n" +
        "## 禁止\n" +
        "- 不要生成实际SQL，只生成计划\n" +
        "- 不要执行查询\n" +
        "- 不要添加用户未提及的表或字段\n\n" +
        "## 输出格式\n" +
        "必须输出纯JSON，符合以下结构：\n" +
        "{\n" +
        "  \"datasource\": {\"id\": 数据源ID或null},\n" +
        "  \"tables\": [{\"tableName\": \"表名\", \"joinType\": \"LEFT/INNER/null\", \"joinCondition\": \"ON条件或null\", \"columns\": [\"字段列表\"]}],\n" +
        "  \"sqlSteps\": [{\"order\": 1, \"description\": \"这步要做什么\"}],\n" +
        "  \"chart\": {\"needed\": true/false, \"type\": \"BAR/LINE/PIE/TABLE/SCATTER/AREA/UNKNOWN\", \"xField\": \"\", \"yField\": \"\", \"groupByField\": \"\", \"title\": \"\"},\n" +
        "  \"needSummary\": true/false,\n" +
        "  \"complexity\": \"SIMPLE/MODERATE/COMPLEX\",\n" +
        "  \"userQuestion\": \"用户原始问题\"\n" +
        "}\n\n" +
        "只输出JSON，不要任何其他内容。";

    public PlannerAgent(LLMService llmService) {
        this.llmService = llmService;
    }

    /**
     * 根据用户问题生成 QueryPlan
     *
     * @param userMessage  用户原始问题
     * @param datasourceId 数据源ID（可能为null）
     * @return QueryPlan 对象
     */
    public QueryPlan plan(String userMessage, Long datasourceId) {
        log.info("[PlannerAgent] 开始规划，问题: {}, datasourceId: {}", userMessage, datasourceId);

        String userPrompt = String.format(
            "用户问题: %s\n数据源ID: %s\n\n请输出QueryPlan JSON。",
            userMessage, datasourceId != null ? datasourceId : "null（需要先澄清数据源）"
        );

        try {
            // 调用 LLM 生成 JSON 格式的 QueryPlan
            String jsonResponse = llmService.generateWithJsonSchema(
                PLANNER_SYSTEM_PROMPT,
                userPrompt,
                QueryPlan.class
            );

            // 清理可能的 markdown 代码块
            jsonResponse = cleanJson(jsonResponse);

            QueryPlan plan = objectMapper.readValue(jsonResponse, QueryPlan.class);

            // 补充透传字段
            plan.setUserQuestion(userMessage);
            if (datasourceId != null && plan.getDatasource() == null) {
                QueryPlan.DatasourceInfo ds = new QueryPlan.DatasourceInfo();
                ds.setId(datasourceId);
                plan.setDatasource(ds);
            }

            log.info("[PlannerAgent] QueryPlan 生成成功，复杂度: {}, SQL步骤数: {}, 需要图表: {}, 需要总结: {}",
                plan.getComplexity(),
                plan.getSqlSteps().size(),
                plan.getChart() != null && plan.getChart().isNeeded(),
                plan.isNeedSummary());

            return plan;

        } catch (Exception e) {
            log.error("[PlannerAgent] QueryPlan 生成失败，降级为 MODERATE 复杂度", e);
            // 降级：构造一个最简单的 Plan
            return createFallbackPlan(userMessage, datasourceId);
        }
    }

    /**
     * 降级方案：构造最简单的 Plan
     */
    private QueryPlan createFallbackPlan(String userMessage, Long datasourceId) {
        QueryPlan plan = new QueryPlan();
        plan.setUserQuestion(userMessage);

        if (datasourceId != null) {
            QueryPlan.DatasourceInfo ds = new QueryPlan.DatasourceInfo();
            ds.setId(datasourceId);
            plan.setDatasource(ds);
        }

        QueryPlan.SqlStep step = new QueryPlan.SqlStep();
        step.setOrder(1);
        step.setDescription(userMessage);
        plan.setSqlSteps(List.of(step));

        plan.setComplexity(QueryPlan.ComplexityLevel.MODERATE);
        plan.setNeedSummary(false);

        QueryPlan.ChartPlan chart = new QueryPlan.ChartPlan();
        chart.setNeeded(false);
        plan.setChart(chart);

        log.info("[PlannerAgent] 使用降级 Plan");
        return plan;
    }

    /**
     * 清理 JSON（去除 markdown 代码块标记）
     */
    private String cleanJson(String text) {
        if (text == null) return null;
        String trimmed = text.trim();
        // 去除 ```json ... ``` 包裹
        if (trimmed.startsWith("```")) {
            int start = trimmed.indexOf('\n');
            if (start > 0) {
                trimmed = trimmed.substring(start + 1);
            }
            int end = trimmed.lastIndexOf("```");
            if (end > 0) {
                trimmed = trimmed.substring(0, end);
            }
        }
        return trimmed.trim();
    }
}
