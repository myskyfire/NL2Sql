package com.nl2sql.core.mcp.proof;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Proof 配置属性
 */
@Data
@Component
@ConfigurationProperties(prefix = "nl2sql.mcp.proof")
public class ProofConfig {

    /**
     * 是否启用 Proof 机制
     */
    private boolean enabled = false;

    /**
     * 共享密钥（Agent 和 MCP Server 使用相同密钥进行 HMAC 签名验证）
     * 
     * 生产环境建议通过环境变量注入：
     * nl2sql.mcp.proof.shared-secret=${MCP_PROOF_SECRET}
     */
    private String sharedSecret = "change-me-in-production";

    /**
     * 凭证有效期（秒），默认 300 秒（5 分钟）
     */
    private int ttlSeconds = 300;

    /**
     * 是否在 MCP Server 侧进行二次只读校验
     */
    private boolean secondaryCheck = true;
}
