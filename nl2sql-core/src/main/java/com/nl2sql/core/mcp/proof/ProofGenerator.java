package com.nl2sql.core.mcp.proof;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;

/**
 * ValidationProof 生成器（Agent 侧）
 * 
 * 在调用 MCP Server 之前，为已通过安全校验的 SQL 生成校验凭证。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "nl2sql.mcp.proof", name = "enabled", havingValue = "true", matchIfMissing = false)
public class ProofGenerator {

    @Autowired
    private ProofConfig proofConfig;

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String HASH_ALGORITHM = "SHA-256";

    /**
     * 为 SQL 生成校验凭证
     *
     * @param sql             SQL 语句
     * @param riskLevel       风险等级（LOW/MEDIUM/HIGH）
     * @param validationSteps 已执行的校验步骤
     * @param sessionId       会话 ID
     * @param userId          用户 ID
     * @param datasourceId    数据源 ID
     * @return ValidationProof
     */
    public ValidationProof generate(
            String sql,
            String riskLevel,
            List<String> validationSteps,
            String sessionId,
            Long userId,
            Long datasourceId
    ) {
        String sqlHash = sha256(sql);
        long timestamp = System.currentTimeMillis();
        int ttl = proofConfig.getTtlSeconds();

        // 构建待签名内容
        ValidationProof proof = ValidationProof.builder()
                .sqlHash(sqlHash)
                .riskLevel(riskLevel)
                .validationSteps(validationSteps)
                .sessionId(sessionId)
                .userId(userId)
                .datasourceId(datasourceId)
                .timestamp(timestamp)
                .ttl(ttl)
                .build();

        // 生成 HMAC 签名
        String signature = hmacSign(toSignString(proof));
        proof.setSignature(signature);

        log.debug("[ProofGenerator] 生成校验凭证: sqlHash={}, riskLevel={}, ttl={}s", sqlHash, riskLevel, ttl);

        return proof;
    }

    /**
     * 计算 SQL 的 SHA-256 哈希
     */
    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance(HASH_ALGORITHM);
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(hash);
        } catch (Exception e) {
            throw new RuntimeException("计算 SQL Hash 失败", e);
        }
    }

    /**
     * HMAC-SHA256 签名
     */
    private String hmacSign(String data) {
        try {
            SecretKeySpec keySpec = new SecretKeySpec(
                    proofConfig.getSharedSecret().getBytes(StandardCharsets.UTF_8),
                    HMAC_ALGORITHM
            );
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(keySpec);
            byte[] signature = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signature);
        } catch (Exception e) {
            throw new RuntimeException("HMAC 签名失败", e);
        }
    }

    /**
     * 构建待签名字符串（所有关键字段拼接）
     */
    private String toSignString(ValidationProof proof) {
        return String.join("|",
                proof.getSqlHash(),
                proof.getRiskLevel(),
                String.valueOf(proof.getTimestamp()),
                String.valueOf(proof.getTtl()),
                proof.getSessionId() != null ? proof.getSessionId() : "",
                proof.getUserId() != null ? proof.getUserId().toString() : "",
                proof.getDatasourceId() != null ? proof.getDatasourceId().toString() : ""
        );
    }

    /**
     * 字节数组转十六进制字符串
     */
    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
