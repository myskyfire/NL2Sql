package com.nl2sql.core.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Component
public class ParamValidationTool {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Tool("校验技能执行所需的必填参数。返回校验结果，如果缺少必填参数则返回澄清请求。")
    public String validateParams(
        @P("用户问题") String question,
        @P("数据源ID") Long datasourceId,
        @P("用户ID（可选）") Long userId,
        @P("用户名（可选）") String username,
        @P("是否仅生成SQL（可选）") Boolean sqlOnly
    ) {
        try {
            log.info("[ParamValidationTool] 开始参数校验: question={}, datasourceId={}, sqlOnly={}",
                question != null, datasourceId, sqlOnly);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", true);

            if (datasourceId == null) {
                log.info("[ParamValidationTool] 缺少datasourceId，返回澄清请求");
                response.put("needsClarification", true);
                response.put("clarificationMessage", "请先选择数据源");
                response.put("type", "clarification");
                return objectMapper.writeValueAsString(response);
            }

            if (question == null || question.trim().isEmpty()) {
                log.info("[ParamValidationTool] 缺少question，返回澄清请求");
                response.put("needsClarification", true);
                response.put("clarificationMessage", "请输入您要查询的问题");
                response.put("type", "clarification");
                return objectMapper.writeValueAsString(response);
            }

            response.put("needsClarification", false);
            response.put("validated", true);
            response.put("sqlOnly", sqlOnly != null && sqlOnly);

            log.info("[ParamValidationTool] 参数校验通过");
            return objectMapper.writeValueAsString(response);

        } catch (Exception e) {
            log.error("[ParamValidationTool] 校验失败", e);
            return "{\"success\":false,\"error\":\"参数校验异常: " + e.getMessage() + "\"}";
        }
    }
}