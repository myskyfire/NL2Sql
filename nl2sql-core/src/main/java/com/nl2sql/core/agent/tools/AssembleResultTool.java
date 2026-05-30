package com.nl2sql.core.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 结果组装 Tool
 *
 * 将 SQL Worker、Chart Worker、Summary Worker 的结果合并为统一的返回格式
 */
@Slf4j
@Component
public class AssembleResultTool {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 组装多个 Worker 的结果
     *
     * @param sqlResult     SQL Worker 结果（JSON字符串）
     * @param chartResult   Chart Worker 结果（JSON字符串，可为null）
     * @param summaryResult Summary Worker 结果（JSON字符串，可为null）
     * @return 组装后的统一响应 JSON
     */
    public String assemble(String sqlResult, String chartResult, String summaryResult) {
        try {
            Map<String, Object> assembled = new LinkedHashMap<>();
            assembled.put("success", true);

            // SQL 数据
            if (sqlResult != null && !sqlResult.trim().isEmpty()) {
                try {
                    Map<String, Object> sqlData = objectMapper.readValue(sqlResult, Map.class);
                    assembled.put("data", sqlData);
                } catch (Exception e) {
                    // 如果不是JSON，直接作为字符串
                    assembled.put("data", sqlResult);
                }
            }

            // 图表配置
            if (chartResult != null && !chartResult.trim().isEmpty()) {
                try {
                    Map<String, Object> chartData = objectMapper.readValue(chartResult, Map.class);
                    assembled.put("chart", chartData);
                    log.info("[AssembleResultTool] 已组装图表结果");
                } catch (Exception e) {
                    assembled.put("chart", chartResult);
                }
            }

            // AI 总结
            if (summaryResult != null && !summaryResult.trim().isEmpty()) {
                try {
                    Map<String, Object> summaryData = objectMapper.readValue(summaryResult, Map.class);
                    assembled.put("summary", summaryData);
                    log.info("[AssembleResultTool] 已组装AI总结结果");
                } catch (Exception e) {
                    assembled.put("summary", summaryResult);
                }
            }

            return objectMapper.writeValueAsString(assembled);

        } catch (Exception e) {
            log.error("[AssembleResultTool] 组装结果失败", e);
            return "{\"success\":false,\"error\":\"结果组装失败: " + e.getMessage() + "\"}";
        }
    }
}
