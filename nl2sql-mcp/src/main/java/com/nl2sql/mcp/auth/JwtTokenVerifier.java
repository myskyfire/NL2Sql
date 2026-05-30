package com.nl2sql.mcp.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

/**
 * JWT Token 验证器（MCP Server 侧）
 * 
 * 验证来自 Agent 的 JWT Token，确保：
 * 1. 签名有效（未被篡改）
 * 2. Token 未过期
 * 3. 签发者可信
 * 4. 用户有权限访问请求的数据源
 */
@Slf4j
@Component
public class JwtTokenVerifier {

    @Value("${nl2sql.mcp.auth.secret:change-me-in-production}")
    private String secret;

    @Value("${nl2sql.mcp.auth.issuer:nl2sql-agent}")
    private String expectedIssuer;

    @Value("${nl2sql.mcp.auth.enabled:false}")
    private boolean authEnabled;

    private SecretKey getSigningKey() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 验证 JWT Token 并提取声明
     *
     * @param token JWT Token 字符串
     * @return 验证结果
     */
    public VerificationResult verify(String token) {
        if (!authEnabled) {
            log.debug("[JwtTokenVerifier] JWT 认证未启用，跳过验证");
            return VerificationResult.skipped();
        }

        if (token == null || token.trim().isEmpty()) {
            return VerificationResult.fail("缺少认证 Token");
        }

        try {
            Claims claims = Jwts.parser()
                    .verifyWith(getSigningKey())
                    .requireIssuer(expectedIssuer)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            Long userId = claims.get("user_id", Long.class);
            List<Long> allowedDatasourceIds = claims.get("allowed_datasource_ids", List.class);
            List<String> roles = claims.get("roles", List.class);

            if (userId == null) {
                return VerificationResult.fail("Token 中缺少 user_id");
            }

            log.debug("[JwtTokenVerifier] Token 验证通过: userId={}, datasources={}",
                    userId, allowedDatasourceIds);

            return VerificationResult.success(
                    userId,
                    allowedDatasourceIds != null ? allowedDatasourceIds : Collections.emptyList(),
                    roles != null ? roles : Collections.emptyList()
            );

        } catch (io.jsonwebtoken.ExpiredJwtException e) {
            log.warn("[JwtTokenVerifier] Token 已过期");
            return VerificationResult.fail("认证 Token 已过期");
        } catch (io.jsonwebtoken.security.SignatureException e) {
            log.warn("[JwtTokenVerifier] 签名验证失败");
            return VerificationResult.fail("Token 签名无效，可能被篡改");
        } catch (io.jsonwebtoken.JwtException e) {
            log.warn("[JwtTokenVerifier] JWT 验证失败: {}", e.getMessage());
            return VerificationResult.fail("Token 无效: " + e.getMessage());
        } catch (Exception e) {
            log.error("[JwtTokenVerifier] 验证异常", e);
            return VerificationResult.fail("Token 验证失败");
        }
    }

    /**
     * 验证用户是否有权访问指定数据源
     */
    public boolean hasDatasourceAccess(VerificationResult result, Long datasourceId) {
        if (result == null || !result.isSuccess()) {
            return false;
        }
        // 如果 allowedDatasourceIds 为空，表示不限制
        if (result.getAllowedDatasourceIds().isEmpty()) {
            return true;
        }
        return result.getAllowedDatasourceIds().contains(datasourceId);
    }

    /**
     * 验证结果
     */
    @Data
    public static class VerificationResult {
        private boolean success;
        private boolean skipped;
        private String errorMessage;
        private Long userId;
        private List<Long> allowedDatasourceIds;
        private List<String> roles;

        public static VerificationResult success(Long userId, List<Long> allowedDatasourceIds, List<String> roles) {
            VerificationResult result = new VerificationResult();
            result.success = true;
            result.userId = userId;
            result.allowedDatasourceIds = allowedDatasourceIds;
            result.roles = roles;
            return result;
        }

        public static VerificationResult fail(String errorMessage) {
            VerificationResult result = new VerificationResult();
            result.success = false;
            result.errorMessage = errorMessage;
            return result;
        }

        public static VerificationResult skipped() {
            VerificationResult result = new VerificationResult();
            result.success = true;
            result.skipped = true;
            return result;
        }
    }
}
