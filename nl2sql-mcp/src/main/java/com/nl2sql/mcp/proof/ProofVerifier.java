package com.nl2sql.mcp.proof;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * ValidationProof 验证器（MCP Server 侧）
 * 
 * 验证来自 Agent 侧的 SQL 校验凭证，确保：
 * 1. 签名有效（未被篡改）
 * 2. SQL Hash 匹配（未被替换 SQL）
 * 3. 凭证未过期（防重放攻击）
 * 4. 风险等级不是 HIGH
 * 5. 二次只读校验（可选）
 */
@Slf4j
@Component
public class ProofVerifier {

    @Value("${nl2sql.mcp.proof.shared-secret:change-me-in-production}")
    private String sharedSecret;

    @Value("${nl2sql.mcp.proof.secondary-check:true}")
    private boolean secondaryCheck;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String HASH_ALGORITHM = "SHA-256";

    /**
     * 验证 SQL 校验凭证
     *
     * @param proofJson 凭证 JSON 字符串
     * @param sql       实际要执行的 SQL
     * @return 验证结果
     */
    public VerificationResult verify(String proofJson, String sql) {
        if (proofJson == null || proofJson.trim().isEmpty()) {
            return VerificationResult.fail("缺少校验凭证");
        }

        ValidationProof proof;
        try {
            proof = objectMapper.readValue(proofJson, ValidationProof.class);
        } catch (Exception e) {
            return VerificationResult.fail("凭证解析失败: " + e.getMessage());
        }

        // 1. 验证签名
        if (!verifySignature(proof)) {
            log.warn("[ProofVerifier] 签名验证失败: sqlHash={}", proof.getSqlHash());
            return VerificationResult.fail("签名验证失败，凭证可能被篡改");
        }

        // 2. 验证 SQL Hash 匹配
        String actualHash = sha256(sql);
        if (!actualHash.equals(proof.getSqlHash())) {
            log.warn("[ProofVerifier] SQL Hash 不匹配: expected={}, actual={}", proof.getSqlHash(), actualHash);
            return VerificationResult.fail("SQL 内容与凭证不匹配");
        }

        // 3. 验证过期
        if (proof.isExpired()) {
            log.warn("[ProofVerifier] 凭证已过期: sqlHash={}, age={}s",
                    proof.getSqlHash(),
                    (System.currentTimeMillis() - proof.getTimestamp()) / 1000);
            return VerificationResult.fail("校验凭证已过期");
        }

        // 4. 验证风险等级
        if ("HIGH".equals(proof.getRiskLevel())) {
            log.warn("[ProofVerifier] 高风险 SQL 被拒绝: sqlHash={}", proof.getSqlHash());
            return VerificationResult.fail("高风险 SQL 被拒绝执行");
        }

        // 5. 二次只读校验（可选）
        if (secondaryCheck && !isReadOnlyQuery(sql)) {
            log.warn("[ProofVerifier] 二次校验失败：非只读 SQL: sqlHash={}", proof.getSqlHash());
            return VerificationResult.fail("只允许执行只读查询（SELECT/WITH/SHOW/DESCRIBE）");
        }

        log.info("[ProofVerifier] 凭证验证通过: sqlHash={}, riskLevel={}, steps={}",
                proof.getSqlHash(), proof.getRiskLevel(), proof.getValidationSteps());

        return VerificationResult.success(proof);
    }

    /**
     * 验证 HMAC 签名
     */
    private boolean verifySignature(ValidationProof proof) {
        try {
            String expectedSignature = proof.getSignature();
            if (expectedSignature == null || expectedSignature.isEmpty()) {
                return false;
            }

            // 重新计算签名
            String data = toSignString(proof);
            SecretKeySpec keySpec = new SecretKeySpec(
                    sharedSecret.getBytes(StandardCharsets.UTF_8),
                    HMAC_ALGORITHM
            );
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(keySpec);
            byte[] signature = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            String computedSignature = Base64.getEncoder().encodeToString(signature);

            return expectedSignature.equals(computedSignature);
        } catch (Exception e) {
            log.error("[ProofVerifier] 签名验证异常", e);
            return false;
        }
    }

    /**
     * 构建待签名字符串（必须与 ProofGenerator 一致）
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
     * 计算 SHA-256
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

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /**
     * 检查是否为只读查询
     */
    private boolean isReadOnlyQuery(String sql) {
        if (sql == null || sql.trim().isEmpty()) return false;
        String trimmed = sql.trim().toUpperCase();
        return trimmed.startsWith("SELECT") || trimmed.startsWith("WITH")
                || trimmed.startsWith("SHOW") || trimmed.startsWith("DESCRIBE")
                || trimmed.startsWith("EXPLAIN");
    }

    /**
     * 验证结果
     */
    @Data
    public static class VerificationResult {
        private boolean success;
        private String errorMessage;
        private ValidationProof proof;

        public static VerificationResult success(ValidationProof proof) {
            VerificationResult result = new VerificationResult();
            result.success = true;
            result.proof = proof;
            return result;
        }

        public static VerificationResult fail(String errorMessage) {
            VerificationResult result = new VerificationResult();
            result.success = false;
            result.errorMessage = errorMessage;
            return result;
        }
    }
}
