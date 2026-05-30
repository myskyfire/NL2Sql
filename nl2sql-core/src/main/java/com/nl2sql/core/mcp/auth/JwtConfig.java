package com.nl2sql.core.mcp.auth;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collections;
import java.util.List;

/**
 * JWT 认证配置（Agent 侧）
 */
@Data
@Component
@ConfigurationProperties(prefix = "nl2sql.mcp.auth")
public class JwtConfig {

    /**
     * 是否启用 JWT 认证
     */
    private boolean enabled = false;

    /**
     * JWT 签名密钥（Agent 和 MCP Server 必须使用相同密钥）
     * 生产环境建议通过环境变量注入：nl2sql.mcp.auth.secret=${MCP_JWT_SECRET}
     */
    private String secret = "change-me-in-production";

    /**
     * Token 有效期，默认 10 分钟
     */
    private Duration tokenTtl = Duration.ofMinutes(10);

    /**
     * Token 签发者标识
     */
    private String issuer = "nl2sql-agent";

    /**
     * 默认允许访问的数据源 ID 列表（为空表示不限制）
     */
    private List<Long> defaultAllowedDatasourceIds = Collections.emptyList();

    /**
     * 默认角色列表
     */
    private List<String> defaultRoles = List.of("analyst");
}
