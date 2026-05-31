package com.nl2sql.core.agent.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
public class ExtractTablePreferenceTool {

    private static final Pattern[] TABLE_PATTERNS = {
        Pattern.compile("使用(\\w+)表"),
        Pattern.compile("用(\\w+)表"),
        Pattern.compile("从(\\w+)表"),
        Pattern.compile("基于(\\w+)表"),
        Pattern.compile("查询(\\w+)表"),
        Pattern.compile("查(\\w+)表")
    };

    @Tool("从用户问题中提取表名偏好。匹配'使用XX表'、'用XX表'、'从XX表'、'基于XX表'等模式，返回提取到的表名或null")
    public String extractTablePreference(
        @P("用户原始问题") String question
    ) {
        try {
            log.info("[ExtractTablePreferenceTool] 提取表名偏好: question={}", question);

            if (question == null || question.trim().isEmpty()) {
                Map<String, Object> data = new HashMap<>();
                data.put("tablePreference", null);
                data.put("detected", false);
                return ToolResponseBuilder.success("data")
                    .withData(data)
                    .addMetadata("toolName", "extract_table_preference")
                    .build();
            }

            String tablePreference = null;
            for (Pattern pattern : TABLE_PATTERNS) {
                Matcher matcher = pattern.matcher(question);
                if (matcher.find()) {
                    tablePreference = matcher.group(1);
                    log.info("[ExtractTablePreferenceTool] 检测到用户指定表: {}", tablePreference);
                    break;
                }
            }

            Map<String, Object> data = new HashMap<>();
            data.put("tablePreference", tablePreference);
            data.put("detected", tablePreference != null);

            if (tablePreference != null) {
                data.put("enhancedQuestion", question + " [优先使用表: " + tablePreference + "]");
            } else {
                data.put("enhancedQuestion", question);
            }

            return ToolResponseBuilder.success("data")
                .withData(data)
                .addMetadata("toolName", "extract_table_preference")
                .build();

        } catch (Exception e) {
            log.error("[ExtractTablePreferenceTool] 提取失败", e);
            return ToolResponseBuilder.error("EXTRACTION_ERROR", e.getMessage())
                .addMetadata("toolName", "extract_table_preference")
                .build();
        }
    }
}
