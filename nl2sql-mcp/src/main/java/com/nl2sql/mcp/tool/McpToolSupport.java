package com.nl2sql.mcp.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * MCP Tool 基础支持类
 * 
 * 封装 Tool 注册、参数解析、结果构建、错误处理的样板代码
 * 子类只需实现核心业务逻辑
 */
@Slf4j
public abstract class McpToolSupport {

    protected final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 构建 Tool Specification（子类调用）
     * 
     * @param name        Tool 名称
     * @param description Tool 描述
     * @param properties  参数定义
     * @param required    必填参数列表
     * @param handler     业务处理函数
     */
    protected McpServerFeatures.SyncToolSpecification buildTool(
            String name,
            String description,
            Map<String, Object> properties,
            List<String> required,
            Function<Map<String, Object>, String> handler
    ) {
        String inputSchemaJson = buildInputSchema(properties, required);

        return new McpServerFeatures.SyncToolSpecification(
                new McpSchema.Tool(name, description, inputSchemaJson),
                (exchange, args) -> execute(handler, args)
        );
    }

    /**
     * 构建参数 Schema JSON
     */
    private String buildInputSchema(Map<String, Object> properties, List<String> required) {
        try {
            Map<String, Object> schema = Map.of(
                    "type", "object",
                    "properties", properties,
                    "required", required
            );
            return objectMapper.writeValueAsString(schema);
        } catch (Exception e) {
            log.warn("构建 Input Schema 失败，使用空 schema", e);
            return "{}";
        }
    }

    /**
     * 执行 Tool 调用（统一错误处理）
     */
    private McpSchema.CallToolResult execute(Function<Map<String, Object>, String> handler, Map<String, Object> args) {
        try {
            String result = handler.apply(args);
            return McpSchema.CallToolResult.builder()
                    .content(List.of(new McpSchema.TextContent(result)))
                    .isError(false)
                    .build();
        } catch (Exception e) {
            log.error("Tool 执行失败", e);
            return McpSchema.CallToolResult.builder()
                    .content(List.of(new McpSchema.TextContent("{\"error\": \"" + escapeJson(e.getMessage()) + "\"}")))
                    .isError(true)
                    .build();
        }
    }

    /**
     * 转义 JSON 字符串中的特殊字符
     */
    protected String escapeJson(String message) {
        if (message == null) return "";
        return message.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    /**
     * 安全获取参数（带类型转换）
     */
    protected Long getLongParam(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (value == null) return null;
        return ((Number) value).longValue();
    }

    protected String getStringParam(Map<String, Object> args, String key) {
        Object value = args.get(key);
        return value != null ? (String) value : null;
    }

    /**
     * 将对象序列化为 JSON 字符串
     */
    protected String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            log.error("序列化失败", e);
            return "{\"error\": \"序列化失败\"}";
        }
    }

    /**
     * 定义单个参数的便捷方法
     */
    protected Map<String, Object> param(String type, String description) {
        return Map.of("type", type, "description", description);
    }
}
