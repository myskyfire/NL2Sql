package com.nl2sql.core.mcp.auth;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;

/**
 * JWT Token 生成器（Agent 侧）
 * 
 * 为每次 MCP 调用生成带签名的 Token，包含：
 * - 用户身份（userId）
 * - 授权范围（allowedDatasourceIds）
 * - 角色（roles）
 * - 过期时间
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "nl2sql.mcp.auth", name = "enabled", havingValue = "true", matchIfMissing = false)
public class JwtTokenGenerator {

    @Autowired
    private JwtConfig jwtConfig;

    /**
     * 生成 JWT Token
     *
     * @param userId                用户 ID
     * @param allowedDatasourceIds  允许访问的数据源 ID 列表
     * @param roles                 角色列表
     * @return JWT Token 字符串
     */
    public String generate(Long userId, List<Long> allowedDatasourceIds, List<String> roles) {
        SecretKey key = Keys.hmacShaKeyFor(jwtConfig.getSecret().getBytes(StandardCharsets.UTF_8));
        Date now = new Date();
        Date expiry = new Date(now.getTime() + jwtConfig.getTokenTtl().toMillis());

        String token = Jwts.builder()
                .subject(String.valueOf(userId))
                .issuer(jwtConfig.getIssuer())
                .issuedAt(now)
                .expiration(expiry)
                .claim("user_id", userId)
                .claim("allowed_datasource_ids", allowedDatasourceIds)
                .claim("roles", roles)
                .signWith(key)
                .compact();

        log.debug("[JwtTokenGenerator] 生成 Token: userId={}, datasources={}, ttl={}s",
                userId, allowedDatasourceIds, jwtConfig.getTokenTtl().getSeconds());

        return token;
    }

    /**
     * 使用默认配置生成 Token
     */
    public String generateDefault(Long userId) {
        return generate(userId, jwtConfig.getDefaultAllowedDatasourceIds(), jwtConfig.getDefaultRoles());
    }
}
