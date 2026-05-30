package com.nl2sql.mcp.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 密码解密服务（MCP Server 运行时使用）
 */
@Slf4j
@Component
public class PasswordDecryptor {

    @Value("${nl2sql.security.master-key:}")
    private String masterKey;

    /**
     * 解密密码（如果已加密则解密，否则直接返回）
     */
    public String decrypt(String encryptedPassword) {
        if (encryptedPassword == null || encryptedPassword.isEmpty()) {
            return encryptedPassword;
        }

        if (!encryptedPassword.startsWith("ENC(AES256:")) {
            return encryptedPassword; // 未加密，直接返回
        }

        if (masterKey == null || masterKey.isEmpty()) {
            throw new IllegalStateException("主密钥未配置，无法解密密码。请设置 nl2sql.security.master-key");
        }

        log.debug("解密数据库密码");
        return PasswordEncryptor.decrypt(masterKey, encryptedPassword);
    }
}
