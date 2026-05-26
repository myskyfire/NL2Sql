package com.nl2sql.mcp.proof;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * SQL 校验凭证（MCP Server 侧副本）
 * 
 * 与 nl2sql-core 中的 ValidationProof 结构完全一致，
 * 用于反序列化 Agent 侧传来的凭证 JSON。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ValidationProof {

    @JsonProperty("sql_hash")
    private String sqlHash;

    @JsonProperty("risk_level")
    private String riskLevel;

    @JsonProperty("validation_steps")
    private List<String> validationSteps;

    @JsonProperty("session_id")
    private String sessionId;

    @JsonProperty("user_id")
    private Long userId;

    @JsonProperty("datasource_id")
    private Long datasourceId;

    @JsonProperty("timestamp")
    private long timestamp;

    @JsonProperty("ttl")
    private int ttl;

    @JsonProperty("signature")
    private String signature;

    public boolean isExpired() {
        return System.currentTimeMillis() - timestamp > ttl * 1000L;
    }
}
