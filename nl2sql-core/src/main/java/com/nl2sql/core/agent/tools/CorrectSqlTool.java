package com.nl2sql.core.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nl2sql.core.service.NL2SQLService;
import com.nl2sql.core.service.SQLCorrectionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * SQL 纠错 Tool
 *
 * 在 Workflow 的 retry 环节使用，负责：
 * 1. jsqlparser 语法校验 + 规则修复
 * 2. 幻觉检测（未选定的表）→ 查找 schema → LLM 重写
 * 3. 通用 LLM 纠错兜底
 */
@Slf4j
@Component
public class CorrectSqlTool {

    @Autowired
    private NL2SQLService nl2sqlService;

    @Autowired
    private SQLCorrectionService sqlCorrectionService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 对失败的 SQL 进行纠错
     *
     * @param failedSql  失败的 SQL
     * @param error      错误信息
     * @param question   用户原始问题
     * @param datasourceId 数据源ID
     * @param maxRetries 最大重试次数
     * @return 纠错后的 SQL（可能为 null 表示无法修复）
     */
    public String correctSql(String failedSql, String error, String question, Long datasourceId, int maxRetries) {
        log.info("[CorrectSqlTool] 开始纠错，SQL前100字符: {}, error: {}",
            failedSql != null ? failedSql.substring(0, Math.min(100, failedSql.length())) : "null",
            error);

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            log.info("[CorrectSqlTool] 第 {} 次纠错尝试", attempt);

            try {
                // 调用 NL2SQLService 的 autoFixSQL 方法（内部已包含表结构检索 + LLM 纠错）
                String correctedSql = nl2sqlService.autoFixSQL(failedSql, error, datasourceId);

                if (correctedSql != null && !correctedSql.trim().isEmpty()
                    && !correctedSql.startsWith("错误：")
                    && !correctedSql.equals(failedSql)) {
                    log.info("[CorrectSqlTool] 纠错成功: {}",
                        correctedSql.substring(0, Math.min(100, correctedSql.length())));
                    return correctedSql;
                }

                failedSql = correctedSql != null ? correctedSql : failedSql;

            } catch (Exception e) {
                log.error("[CorrectSqlTool] 第 {} 次纠错失败", attempt, e);
            }
        }

        log.warn("[CorrectSqlTool] 达到最大重试次数 {}，放弃纠错", maxRetries);
        return null;
    }

    /**
     * 构建纠错结果的 JSON 响应
     */
    public String buildResult(String correctedSql, String originalError, boolean success) {
        try {
            Map<String, Object> result = new HashMap<>();
            if (success && correctedSql != null) {
                result.put("success", true);
                result.put("correctedSql", correctedSql);
            } else {
                result.put("success", false);
                result.put("error", originalError != null ? originalError : "无法修复");
            }
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            log.error("[CorrectSqlTool] 构建结果 JSON 失败", e);
            return "{\"success\":false,\"error\":\"JSON构建失败\"}";
        }
    }
}
