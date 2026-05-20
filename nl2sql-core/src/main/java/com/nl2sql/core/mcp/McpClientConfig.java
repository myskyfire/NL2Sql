package com.nl2sql.core.mcp;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * MCP Client 配置属性
 * 
 * 支持两种传输模式：
 * - STDIO: 通过子进程启动 MCP Server（适合本地部署）
 * - SSE: 通过 HTTP 连接 MCP Server（适合远程部署）
 */
@Data
@Component
@ConfigurationProperties(prefix = "nl2sql.mcp")
public class McpClientConfig {

    /**
     * 是否启用 MCP Client（默认关闭，保持向后兼容）
     */
    private boolean enabled = false;

    /**
     * 传输模式：stdio 或 sse
     */
    private TransportMode transportMode = TransportMode.STDIO;

    /**
     * STDIO 模式配置
     */
    private StdioConfig stdio = new StdioConfig();

    /**
     * SSE 模式配置
     */
    private SseConfig sse = new SseConfig();

    /**
     * 请求超时时间
     */
    private Duration requestTimeout = Duration.ofSeconds(30);

    /**
     * 默认数据源 ID（可选）
     */
    private Long defaultDatasourceId;

    /**
     * 传输模式枚举
     */
    public enum TransportMode {
        /**
         * 标准输入/输出模式，通过子进程启动 MCP Server
         */
        STDIO,
        /**
         * Server-Sent Events 模式，通过 HTTP 连接 MCP Server
         */
        SSE
    }

    @Data
    public static class StdioConfig {
        /**
         * MCP Server 启动命令
         */
        private String command = "java";

        /**
         * MCP Server 启动参数
         */
        private String[] args = {"-jar", "nl2sql-mcp.jar"};

        /**
         * 工作目录
         */
        private String workingDirectory;

        /**
         * 环境变量
         */
        private Map<String, String> env = new HashMap<>();
    }

    @Data
    public static class SseConfig {
        /**
         * MCP Server URL
         */
        private String url = "http://localhost:8080";
    }
}
