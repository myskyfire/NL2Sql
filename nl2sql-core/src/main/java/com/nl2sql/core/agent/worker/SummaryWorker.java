package com.nl2sql.core.agent.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nl2sql.core.agent.planner.QueryPlan;
import com.nl2sql.core.llm.LLMService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Summary Worker - 对查询结果进行 AI 智能总结
 */
@Slf4j
@Component
public class SummaryWorker implements Worker {

    private final LLMService llmService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SummaryWorker(LLMService llmService) {
        this.llmService = llmService;
    }

    @Override
    public String getWorkerType() {
        return "summary";
    }

    @Override
    public Worker.WorkerResult execute(Worker.WorkerContext context) {
        QueryPlan plan = context.getPlan();

        // 如果不需要AI总结，跳过
        if (!plan.isNeedSummary()) {
            return Worker.WorkerResult.skip("summary");
        }

        log.info("[SummaryWorker] 开始AI总结");

        try {
            // 从 SQL Worker 结果获取数据
            Worker.WorkerResult sqlResult = context.getPreviousResults().get("sql");
            if (sqlResult == null) {
                return Worker.WorkerResult.failure("summary", "未获取到SQL执行结果，无法总结");
            }

            String sql = sqlResult.getData() != null ?
                String.valueOf(sqlResult.getData().getOrDefault("sql", "")) : "";

            String dataJson;
            if (sqlResult.getData() != null && sqlResult.getData().containsKey("data")) {
                dataJson = objectMapper.writeValueAsString(sqlResult.getData().get("data"));
            } else if (sqlResult.getRawOutput() != null) {
                dataJson = sqlResult.getRawOutput();
            } else {
                dataJson = "无数据";
            }

            // 限制数据长度，避免 prompt 过长
            if (dataJson.length() > 5000) {
                dataJson = dataJson.substring(0, 5000) + "\n...（数据过长，已截断）";
            }

            String summary = llmService.generate(
                "你是数据分析专家。请根据以下查询结果，用简洁的中文进行总结。",
                String.format(
                    "用户问题：%s\n\n执行的SQL：%s\n\n查询结果数据（JSON格式）：\n%s\n\n" +
                    "要求：\n" +
                    "1. 总结关键发现和趋势\n" +
                    "2. 指出异常值或值得关注的数据点\n" +
                    "3. 给出简要的业务建议\n" +
                    "4. 用中文，简洁明了，200字以内\n" +
                    "5. 直接输出总结内容，不要加标题",
                    plan.getUserQuestion(), sql, dataJson),
                null
            );

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("text", summary);

            return Worker.WorkerResult.success("summary", result);

        } catch (Exception e) {
            log.error("[SummaryWorker] AI总结失败", e);
            return Worker.WorkerResult.failure("summary", "AI总结失败: " + e.getMessage());
        }
    }
}
