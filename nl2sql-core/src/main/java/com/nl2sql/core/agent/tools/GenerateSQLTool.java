package com.nl2sql.core.agent.tools;

import com.nl2sql.core.service.NL2SQLService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
public class GenerateSQLTool {

    @Autowired
    private NL2SQLService nl2sqlService;

    @Deprecated
    @Tool(name = "generateSQL", value = "基于用户问题和数据源生成 SQL 查询语句。@Deprecated - 表名偏好提取已移至 extractTablePreference，行业扩展注入已移至 injectIndustryConcept")
    public String execute(
        @P("用户的自然语言问题，例如：查询上月订单总额") String question,
        @P("数据源ID") Long datasourceId,
        @P("预检索的表结构信息（可选，如果提供则跳过内部检索）") String schemaInfo,
        @P("用户指定的表名偏好（可选，如'使用XX表'）") String tableHint
    ) {
        try {
            log.info("[GenerateSQLTool] @Deprecated - 表名偏好提取已移至 extractTablePreference，行业扩展注入已移至 injectIndustryConcept");
            log.info("[GenerateSQLTool] 开始生成SQL: question={}, datasourceId={}, hasSchemaInfo={}, hasTableHint={}",
                question, datasourceId, schemaInfo != null && !schemaInfo.isEmpty(),
                tableHint != null && !tableHint.isEmpty());

            if (question == null || question.trim().isEmpty()) {
                return buildErrorResponse("用户问题不能为空");
            }

            if (datasourceId == null) {
                return buildErrorResponse("数据源ID不能为空");
            }

            String enhancedQuestion = question;

            if (tableHint != null && !tableHint.trim().isEmpty()) {
                enhancedQuestion = question + " [优先使用表: " + tableHint.trim() + "]";
                log.info("[GenerateSQLTool] 应用表名偏好: {}", tableHint);
            }

            String sql = nl2sqlService.generateSQL(enhancedQuestion, datasourceId);

            if (sql == null || sql.trim().isEmpty()) {
                return buildErrorResponse("SQL生成失败：返回结果为空");
            }

            if (sql.startsWith("错误：") || sql.startsWith("ERROR:")) {
                return buildErrorResponse("SQL生成失败: " + sql);
            }

            if (sql.startsWith("CLARIFICATION_NEEDED:")) {
                return buildErrorResponse("需要澄清: " + sql.substring("CLARIFICATION_NEEDED:".length()).trim());
            }

            if (sql.startsWith("TABLE_SELECTION_NEEDED:")) {
                return buildErrorResponse("需要选择表: " + sql.substring("TABLE_SELECTION_NEEDED:".length()).trim());
            }

            log.info("[GenerateSQLTool] SQL生成成功: {}", sql);

            return buildSuccessResponse(sql);

        } catch (Exception e) {
            log.error("[GenerateSQLTool] SQL生成失败", e);
            return buildErrorResponse("SQL生成异常: " + e.getMessage());
        }
    }

    private String buildSuccessResponse(String sql) {
        Map<String, Object> data = new HashMap<>();
        data.put("sql", sql);

        return ToolResponseBuilder.success("data")
            .withData(data)
            .addMetadata("toolName", "generate_sql")
            .build();
    }

    private String buildErrorResponse(String error) {
        return ToolResponseBuilder.error("SQL_GENERATION_ERROR", error)
            .addMetadata("toolName", "generate_sql")
            .build();
    }
}
