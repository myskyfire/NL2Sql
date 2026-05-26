package com.nl2sql.core.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nl2sql.core.mcp.auth.JwtTokenGenerator;
import com.nl2sql.core.mcp.proof.ProofGenerator;
import com.nl2sql.core.mcp.proof.ValidationProof;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ListToolsResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * MCP Client 服务
 * 
 * 作为 nl2sql-core 与 nl2sql-mcp 之间的桥梁，
 * 通过标准 MCP 协议调用远端数据库操作能力。
 * 
 * 使用方式：
 * 1. 在 application.yml 中配置 nl2sql.mcp.enabled=true
 * 2. 注入 McpClientService 并调用对应方法
 * 
 * 注意：默认关闭（enabled=false），不影响现有 JDBC 逻辑
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "nl2sql.mcp", name = "enabled", havingValue = "true")
public class McpClientService {

    @Autowired
    private McpClientConfig config;

    @Autowired(required = false)
    private ProofGenerator proofGenerator;

    @Autowired(required = false)
    private JwtTokenGenerator jwtTokenGenerator;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private io.modelcontextprotocol.client.McpSyncClient client;
    private final AtomicBoolean initialized = new AtomicBoolean(false);

    @PostConstruct
    public void init() {
        if (!config.isEnabled()) {
            log.info("[McpClient] MCP Client 未启用，跳过初始化");
            return;
        }

        try {
            log.info("[McpClient] 初始化 MCP Client，传输模式: {}", config.getTransportMode());

            // 根据配置创建传输层
            var transport = createTransport();

            // 创建同步客户端
            client = McpClient.sync(transport)
                    .requestTimeout(config.getRequestTimeout())
                    .build();

            // 初始化连接
            client.initialize();

            // 列出可用工具
            ListToolsResult tools = client.listTools();
            log.info("[McpClient] 连接成功，可用工具数: {}", tools.tools().size());
            tools.tools().forEach(tool ->
                    log.info("[McpClient]   - {}: {}", tool.name(), tool.description())
            );

            initialized.set(true);
            log.info("[McpClient] MCP Client 初始化完成");

        } catch (Exception e) {
            log.error("[McpClient] MCP Client 初始化失败", e);
            throw new RuntimeException("MCP Client 初始化失败", e);
        }
    }

    @PreDestroy
    public void destroy() {
        if (client != null) {
            try {
                client.closeGracefully();
                log.info("[McpClient] MCP Client 已关闭");
            } catch (Exception e) {
                log.warn("[McpClient] 关闭 MCP Client 时发生异常", e);
            }
        }
    }

    /**
     * 创建传输层
     */
    private io.modelcontextprotocol.spec.McpClientTransport createTransport() {
        return switch (config.getTransportMode()) {
            case STDIO -> createStdioTransport();
            case SSE -> createSseTransport();
        };
    }

    /**
     * STDIO 传输层（通过子进程启动 MCP Server）
     */
    private StdioClientTransport createStdioTransport() {
        McpClientConfig.StdioConfig stdio = config.getStdio();

        log.info("[McpClient] STDIO 模式: command={}, args={}", stdio.getCommand(), String.join(" ", stdio.getArgs()));

        ServerParameters.Builder builder = ServerParameters.builder(stdio.getCommand());
        if (stdio.getArgs() != null && stdio.getArgs().length > 0) {
            builder.args(stdio.getArgs());
        }
        if (!stdio.getEnv().isEmpty()) {
            builder.env(stdio.getEnv());
        }

        return new StdioClientTransport(builder.build());
    }

    /**
     * SSE 传输层（通过 HTTP 连接 MCP Server）
     */
    private HttpClientSseClientTransport createSseTransport() {
        String url = config.getSse().getUrl();
        log.info("[McpClient] SSE 模式: url={}", url);
        return new HttpClientSseClientTransport(url);
    }

    // ==================== 业务方法 ====================

    /**
     * 列出所有可用数据源
     */
    public String listDatasources() {
        return callTool("list_datasources", Map.of());
    }

    /**
     * 列出指定数据源中的所有表
     *
     * @param datasourceId  数据源 ID
     * @param schemaPattern Schema 匹配模式（可选）
     */
    public String listTables(Long datasourceId, String schemaPattern) {
        Map<String, Object> args = new HashMap<>();
        args.put("datasource_id", datasourceId);
        if (schemaPattern != null) {
            args.put("schema_pattern", schemaPattern);
        }
        return callTool("list_tables", args);
    }

    /**
     * 查询表结构
     *
     * @param datasourceId 数据源 ID
     * @param tableName    表名
     */
    public String querySchema(Long datasourceId, String tableName) {
        return callTool("query_schema", Map.of(
                "datasource_id", datasourceId,
                "table_name", tableName
        ));
    }

    /**
     * 批量查询表结构（用于元数据同步）
     *
     * @param datasourceId  数据源 ID
     * @param tableNames    表名列表（为空则查询所有表）
     * @param schemaPattern Schema 匹配模式（可选）
     */
    public String batchQuerySchema(Long datasourceId, List<String> tableNames, String schemaPattern) {
        Map<String, Object> args = new HashMap<>();
        args.put("datasource_id", datasourceId);
        if (tableNames != null && !tableNames.isEmpty()) {
            args.put("table_names", tableNames);
        }
        if (schemaPattern != null) {
            args.put("schema_pattern", schemaPattern);
        }
        return callTool("batch_query_schema", args);
    }

    /**
     * 执行只读 SQL 查询（带安全凭证）
     *
     * @param datasourceId    数据源 ID
     * @param sql             SQL 语句
     * @param riskLevel       风险等级（LOW/MEDIUM）
     * @param validationSteps 已执行的校验步骤
     * @param sessionId       会话 ID
     * @param userId          用户 ID
     */
    public String executeSqlWithProof(Long datasourceId, String sql, String riskLevel,
                                      List<String> validationSteps, String sessionId, Long userId) {
        String proofJson = null;

        // 如果 ProofGenerator 可用，生成校验凭证
        if (proofGenerator != null) {
            try {
                ValidationProof proof = proofGenerator.generate(
                        sql, riskLevel, validationSteps, sessionId, userId, datasourceId
                );
                proofJson = objectMapper.writeValueAsString(proof);
                log.debug("[McpClient] 已生成校验凭证: sqlHash={}", proof.getSqlHash());
            } catch (Exception e) {
                log.warn("[McpClient] 生成校验凭证失败，将不带凭证执行 SQL", e);
            }
        }

        return executeSql(datasourceId, sql, proofJson);
    }

    /**
     * 执行只读 SQL 查询（内部方法，支持传入 proof）
     */
    private String executeSql(Long datasourceId, String sql, String proofJson) {
        Map<String, Object> args = new HashMap<>();
        args.put("datasource_id", datasourceId);
        args.put("sql", sql);
        if (proofJson != null) {
            args.put("validation_proof", proofJson);
        }
        return callTool("execute_sql", args);
    }

    /**
     * 执行只读 SQL 查询（无凭证，向后兼容）
     *
     * @param datasourceId 数据源 ID
     * @param sql          SQL 语句
     */
    public String executeSql(Long datasourceId, String sql) {
        return executeSql(datasourceId, sql, null);
    }

    /**
     * 获取表关联关系
     *
     * @param datasourceId 数据源 ID
     */
    public String getTableRelationships(Long datasourceId) {
        return callTool("get_table_relationships", Map.of(
                "datasource_id", datasourceId
        ));
    }

    /**
     * 生成 SQL
     *
     * @param datasourceId 数据源 ID
     * @param question     自然语言问题
     */
    public String generateSql(Long datasourceId, String question) {
        return callTool("generate_sql", Map.of(
                "datasource_id", datasourceId,
                "question", question
        ));
    }

    /**
     * 验证 SQL
     *
     * @param datasourceId 数据源 ID
     * @param sql          SQL 语句
     */
    public String validateSql(Long datasourceId, String sql) {
        return callTool("validate_sql", Map.of(
                "datasource_id", datasourceId,
                "sql", sql
        ));
    }

    // ==================== 内部方法 ====================

    /**
     * 调用 MCP Tool（自动注入 JWT Token）
     */
    private String callTool(String toolName, Map<String, Object> arguments) {
        checkInitialized();

        try {
            // 注入 JWT Token（如果启用）
            Map<String, Object> argsWithAuth = injectJwtToken(arguments);

            log.debug("[McpClient] 调用 Tool: {}，参数: {}", toolName, argsWithAuth);

            CallToolRequest request = new CallToolRequest(toolName, argsWithAuth);
            CallToolResult result = client.callTool(request);

            if (result.isError()) {
                String errorMsg = extractTextContent(result);
                log.error("[McpClient] Tool 调用失败: {}，错误: {}", toolName, errorMsg);
                throw new McpToolException("MCP Tool 调用失败 [" + toolName + "]: " + errorMsg);
            }

            String response = extractTextContent(result);
            log.debug("[McpClient] Tool 调用成功: {}，响应长度: {}", toolName, response.length());
            return response;

        } catch (McpToolException e) {
            throw e;
        } catch (Exception e) {
            log.error("[McpClient] Tool 调用异常: {}", toolName, e);
            throw new McpToolException("MCP Tool 调用异常 [" + toolName + "]: " + e.getMessage(), e);
        }
    }

    /**
     * 向请求参数中注入 JWT Token（如果启用）
     */
    private Map<String, Object> injectJwtToken(Map<String, Object> arguments) {
        if (jwtTokenGenerator == null) {
            return arguments;
        }

        Map<String, Object> argsWithAuth = new HashMap<>(arguments);
        try {
            String token = jwtTokenGenerator.generateDefault(null);
            argsWithAuth.put("_jwt_token", token);
            log.debug("[McpClient] 已注入 JWT Token");
        } catch (Exception e) {
            log.warn("[McpClient] 生成 JWT Token 失败，将不带 Token 调用", e);
        }
        return argsWithAuth;
    }

    /**
     * 从 CallToolResult 中提取文本内容
     */
    private String extractTextContent(CallToolResult result) {
        if (result.content() == null || result.content().isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        for (var content : result.content()) {
            if (content instanceof McpSchema.TextContent textContent) {
                sb.append(textContent.text());
            }
        }
        return sb.toString();
    }

    /**
     * 检查客户端是否已初始化
     */
    private void checkInitialized() {
        if (!initialized.get()) {
            throw new IllegalStateException("MCP Client 未初始化，请检查配置 nl2sql.mcp.enabled=true");
        }
    }

    /**
     * 检查客户端是否可用
     */
    public boolean isAvailable() {
        return initialized.get();
    }

    /**
     * MCP Tool 调用异常
     */
    public static class McpToolException extends RuntimeException {
        public McpToolException(String message) {
            super(message);
        }

        public McpToolException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
