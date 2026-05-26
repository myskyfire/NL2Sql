package com.nl2sql.mcp.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nl2sql.mcp.auth.JwtTokenVerifier;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * MCP Tool 基础支持类
 * 
 * 封装 Tool 注册、参数解析、结果构建、错误处理的样板代码
 * 子类只需实现核心业务逻辑
 * 
 * 安全特性：
 * - 自动验证 JWT Token（如果启用）
 * - 自动检查数据源访问权限
 */
@Slf4j
public abstract class McpToolSupport {

    @Autowired(required = false)
    protected JwtTokenVerifier jwtTokenVerifier;

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
                (exchange, args) -> executeWithAuth(handler, args)
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
     * 执行 Tool 调用（带 JWT 认证 + 统一错误处理）
     */
    private McpSchema.CallToolResult executeWithAuth(Function<Map<String, Object>, String> handler, Map<String, Object> args) {
        // 1. JWT 认证
        String token = getStringParam(args, "_jwt_token");
        if (token != null) {
            JwtTokenVerifier.VerificationResult authResult = jwtTokenVerifier.verify(token);
            if (!authResult.isSuccess()) {
                log.warn("[McpToolSupport] JWT 认证失败: {}", authResult.getErrorMessage());
                return errorResult("认证失败: " + authResult.getErrorMessage());
            }

            // 2. 数据源权限检查（如果请求包含 datasource_id）
            Long datasourceId = getLongParam(args, "datasource_id");
            if (datasourceId != null && !authResult.isSkipped()) {
                if (!jwtTokenVerifier.hasDatasourceAccess(authResult, datasourceId)) {
                    log.warn("[McpToolSupport] 无权访问数据源: datasourceId={}, userId={}",
                            datasourceId, authResult.getUserId());
                    return errorResult("无权访问数据源: " + datasourceId);
                }
            }
        }

        // 3. 执行实际业务逻辑
        return execute(handler, args);
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
            return errorResult(e.getMessage());
        }
    }

    /**
     * 构建错误响应
     */
    private McpSchema.CallToolResult errorResult(String message) {
        return McpSchema.CallToolResult.builder()
                .content(List.of(new McpSchema.TextContent("{\"error\": \"" + escapeJson(message) + "\"}")))
                .isError(true)
                .build();
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
