package com.nl2sql.core.mcp.proof;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * SQL 校验凭证
 * 
 * 由 Agent 侧生成，携带到 MCP Server 调用中，
 * 用于证明该 SQL 已经通过了完整的安全校验链路。
 * 
 * 安全机制：
 * 1. HMAC-SHA256 签名防篡改
 * 2. SQL Hash 绑定防重放到不同 SQL
 * 3. TTL 过期防重放攻击
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ValidationProof {

    /**
     * SQL 的 SHA-256 哈希值（绑定 SQL 内容，防止 Token 被重放到不同 SQL）
     */
    @JsonProperty("sql_hash")
    private String sqlHash;

    /**
     * 风险等级：LOW / MEDIUM / HIGH
     */
    @JsonProperty("risk_level")
    private String riskLevel;

    /**
     * 已执行的校验步骤列表
     * 例如：["ast_check", "explain", "llm_review", "permission_check"]
     */
    @JsonProperty("validation_steps")
    private List<String> validationSteps;

    /**
     * 会话 ID（用于溯源）
     */
    @JsonProperty("session_id")
    private String sessionId;

    /**
     * 用户 ID（用于溯源）
     */
    @JsonProperty("user_id")
    private Long userId;

    /**
     * 数据源 ID
     */
    @JsonProperty("datasource_id")
    private Long datasourceId;

    /**
     * 生成时间戳（毫秒）
     */
    @JsonProperty("timestamp")
    private long timestamp;

    /**
     * 有效期（秒），默认 300 秒（5 分钟）
     */
    @JsonProperty("ttl")
    private int ttl;

    /**
     * HMAC-SHA256 签名
     * 对上述所有字段（除 signature 本身外）进行签名，防止篡改
     */
    @JsonProperty("signature")
    private String signature;

    /**
     * 默认 TTL：5 分钟
     */
    public static final int DEFAULT_TTL_SECONDS = 300;

    /**
     * 检查凭证是否已过期
     */
    public boolean isExpired() {
        return System.currentTimeMillis() - timestamp > ttl * 1000L;
    }
}
