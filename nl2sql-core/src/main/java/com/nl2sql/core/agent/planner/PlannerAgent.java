package com.nl2sql.core.agent.planner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nl2sql.core.llm.LLMService;
import com.nl2sql.core.tracing.TraceSpan;
import com.nl2sql.core.tracing.TracingService;
import com.nl2sql.core.tracing.TracingContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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

    @Autowired(required = false)
    private TracingService tracingService;

    private static final String PLANNER_SYSTEM_PROMPT =
        "你是SQL查询规划师。根据用户的自然语言问题，输出结构化的QueryPlan（JSON格式）。\n\n" +
        "## 规则\n" +
        "1. 先确定数据源（datasourceId已在问题中指定或为null）\n" +
        "2. 分析涉及的表和需要的字段\n" +
        "3. 拆解SQL执行步骤（简单查询1步，多步聚合查询多步）\n" +
        "4. 判断是否需要图表（用户提到趋势/分布/图/可视化等关键词时）\n" +
        "5. 判断是否需要AI总结（用户提到分析/总结/解读/洞察等关键词时）\n" +
        "6. 评估复杂度（这是最关键的一步，请严格按以下标准判断）：\n" +
        "   - SIMPLE：单表查询，无论是否有GROUP BY、聚合函数(SUM/COUNT/AVG)、WHERE条件、ORDER BY。只要只涉及一张表就是SIMPLE。例如：\"每天的订单金额\"、\"最近一周各产品的销量\"、\"订单总数\"都是SIMPLE。\n" +
        "   - MODERATE：必须涉及两张或以上表的JOIN。例如：\"订单及其对应的用户信息\"、\"每个客户的订单总金额\"（需要JOIN订单表和客户表）。\n" +
        "   - COMPLEX：多步聚合+图表+总结，且涉及多表JOIN。例如：\"分析各城市各品类的销售趋势并生成图表和总结\"。\n\n" +
        "## 复杂度判断示例\n" +
        "- \"最近一周每天的订单金额和订单数量\" → SIMPLE（单表orders，GROUP BY日期）\n" +
        "- \"每个产品的销售总额\" → SIMPLE（单表，GROUP BY产品）\n" +
        "- \"订单及对应的客户姓名\" → MODERATE（JOIN订单表和客户表）\n" +
        "- \"各城市各品类的销售趋势分析并生成图表\" → COMPLEX（多表JOIN+多维度+图表）\n\n" +
        "## 禁止\n" +
        "- 不要生成实际SQL，只生成计划\n" +
        "- 不要执行查询\n" +
        "- 不要添加用户未提及的表或字段\n" +
        "- 不要因为查询包含GROUP BY或聚合函数就判为MODERATE，单表聚合永远是SIMPLE\n\n" +
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

        TraceSpan planRun = null;
        if (tracingService != null && tracingService.isEnabled()) {
            planRun = tracingService.traceChain("PlannerAgent.plan",
                Map.of("userMessage", userMessage.length() > 200 ? userMessage.substring(0, 200) + "..." : userMessage,
                       "datasourceId", datasourceId != null ? datasourceId.toString() : "null"),
                TracingContext.currentRunId());
        }

        String userPrompt = String.format(
            "用户问题: %s\n数据源ID: %s\n\n请输出QueryPlan JSON。",
            userMessage, datasourceId != null ? datasourceId : "null（需要先澄清数据源）"
        );

        try {
            String jsonResponse = llmService.generateWithJsonSchema(
                PLANNER_SYSTEM_PROMPT,
                userPrompt,
                QueryPlan.class
            );

            jsonResponse = cleanJson(jsonResponse);

            QueryPlan plan = objectMapper.readValue(jsonResponse, QueryPlan.class);

            plan.setUserQuestion(userMessage);
            if (datasourceId != null && plan.getDatasource() == null) {
                QueryPlan.DatasourceInfo ds = new QueryPlan.DatasourceInfo();
                ds.setId(datasourceId);
                plan.setDatasource(ds);
            }

            correctComplexity(plan);

            log.info("[PlannerAgent] QueryPlan 生成成功，复杂度: {}, SQL步骤数: {}, 需要图表: {}, 需要总结: {}",
                plan.getComplexity(),
                plan.getSqlSteps().size(),
                plan.getChart() != null && plan.getChart().isNeeded(),
                plan.isNeedSummary());

            endPlanTrace(planRun, plan, null);
            return plan;

        } catch (Exception e) {
            log.error("[PlannerAgent] QueryPlan 生成失败，降级为 MODERATE 复杂度", e);
            QueryPlan fallbackPlan = createFallbackPlan(userMessage, datasourceId);
            endPlanTrace(planRun, fallbackPlan, e.getMessage());
            return fallbackPlan;
        }
    }

    private void endPlanTrace(TraceSpan run, QueryPlan plan, String error) {
        if (tracingService == null || run == null) return;
        try {
            Map<String, Object> outputs = new LinkedHashMap<>();
            outputs.put("complexity", plan.getComplexity() != null ? plan.getComplexity().name() : "null");
            outputs.put("tableCount", plan.getTables() != null ? plan.getTables().size() : 0);
            outputs.put("sqlSteps", plan.getSqlSteps() != null ? plan.getSqlSteps().size() : 0);
            outputs.put("chartNeeded", plan.getChart() != null && plan.getChart().isNeeded());
            outputs.put("needSummary", plan.isNeedSummary());
            tracingService.endRun(run, outputs, error);
        } catch (Exception e) {
            log.debug("[LangSmith] endPlanTrace 失败: {}", e.getMessage());
        }
    }

    private void correctComplexity(QueryPlan plan) {
        if (plan.getComplexity() == null) return;

        List<QueryPlan.TablePlan> tables = plan.getTables();
        int tableCount = tables != null ? tables.size() : 0;
        boolean hasJoin = false;
        if (tables != null) {
            for (QueryPlan.TablePlan t : tables) {
                if (t.getJoinType() != null && !t.getJoinType().isEmpty()) {
                    hasJoin = true;
                    break;
                }
            }
        }

        QueryPlan.ComplexityLevel original = plan.getComplexity();

        if (tableCount <= 1 && !hasJoin) {
            if (plan.getComplexity() != QueryPlan.ComplexityLevel.SIMPLE) {
                log.info("[PlannerAgent] 复杂度修正: {} → SIMPLE (单表查询，tables={}, hasJoin={})",
                    original, tableCount, hasJoin);
                plan.setComplexity(QueryPlan.ComplexityLevel.SIMPLE);
            }
        } else if (tableCount >= 2 && hasJoin) {
            boolean needsChart = plan.getChart() != null && plan.getChart().isNeeded();
            boolean needsSummary = plan.isNeedSummary();
            if (plan.getComplexity() == QueryPlan.ComplexityLevel.SIMPLE) {
                QueryPlan.ComplexityLevel corrected = (needsChart || needsSummary)
                    ? QueryPlan.ComplexityLevel.COMPLEX : QueryPlan.ComplexityLevel.MODERATE;
                log.info("[PlannerAgent] 复杂度修正: {} → {} (多表JOIN, tables={}, chart={}, summary={})",
                    original, corrected, tableCount, needsChart, needsSummary);
                plan.setComplexity(corrected);
            }
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
