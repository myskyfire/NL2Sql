package com.nl2sql.mcp.security;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 密码加密工具
 * 
 * 使用 AES-256-GCM 加密算法
 * 
 * 使用方式：
 * 1. 设置主密钥：java -cp nl2sql-mcp.jar com.nl2sql.mcp.security.PasswordEncryptor generate-key
 * 2. 加密密码：java -cp nl2sql-mcp.jar com.nl2sql.mcp.security.PasswordEncryptor encrypt <masterKey> <plainPassword>
 */
public class PasswordEncryptor {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int KEY_LENGTH = 256;
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;

    /**
     * 生成随机主密钥
     */
    public static String generateMasterKey() {
        SecureRandom random = new SecureRandom();
        byte[] key = new byte[KEY_LENGTH / 8];
        random.nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    /**
     * 从主密钥字符串派生 AES-256 密钥
     * 支持任意字符串作为主密钥，通过 SHA-256 哈希派生 256 位密钥
     */
    private static SecretKey deriveKey(String masterKey) throws Exception {
        MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
        byte[] keyBytes = sha256.digest(masterKey.getBytes(StandardCharsets.UTF_8));
        return new SecretKeySpec(keyBytes, "AES");
    }

    /**
     * 加密明文密码
     *
     * @param masterKey     主密钥（任意字符串）
     * @param plainPassword 明文密码
     * @return 加密后的密码，格式：ENC(AES256:iv:ciphertext)
     */
    public static String encrypt(String masterKey, String plainPassword) {
        try {
            SecretKey key = deriveKey(masterKey);

            // 生成随机 IV
            byte[] iv = new byte[GCM_IV_LENGTH];
            new SecureRandom().nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.ENCRYPT_MODE, key, spec);

            byte[] ciphertext = cipher.doFinal(plainPassword.getBytes(StandardCharsets.UTF_8));

            // 拼接 IV 和密文，Base64 编码
            String encoded = Base64.getEncoder().encodeToString(iv) + ":" + Base64.getEncoder().encodeToString(ciphertext);
            return "ENC(AES256:" + encoded + ")";

        } catch (Exception e) {
            throw new RuntimeException("加密失败", e);
        }
    }

    /**
     * 解密密码
     *
     * @param masterKey         主密钥（任意字符串）
     * @param encryptedPassword 加密后的密码，格式：ENC(AES256:iv:ciphertext)
     * @return 明文密码
     */
    public static String decrypt(String masterKey, String encryptedPassword) {
        try {
            if (encryptedPassword == null || !encryptedPassword.startsWith("ENC(AES256:")) {
                return encryptedPassword; // 未加密，直接返回
            }

            // 解析 ENC(AES256:iv:ciphertext)
            String content = encryptedPassword.substring("ENC(AES256:".length(), encryptedPassword.length() - 1);
            String[] parts = content.split(":");
            if (parts.length != 2) {
                throw new IllegalArgumentException("无效的加密密码格式");
            }

            SecretKey key = deriveKey(masterKey);

            byte[] iv = Base64.getDecoder().decode(parts[0]);
            byte[] ciphertext = Base64.getDecoder().decode(parts[1]);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.DECRYPT_MODE, key, spec);

            byte[] plaintext = cipher.doFinal(ciphertext);
            return new String(plaintext, StandardCharsets.UTF_8);

        } catch (Exception e) {
            throw new RuntimeException("解密失败", e);
        }
    }

    public static void main(String[] args) {
        if (args.length == 0) {
            System.out.println("用法:");
            System.out.println("  生成主密钥: java -cp nl2sql-mcp.jar com.nl2sql.mcp.security.PasswordEncryptor generate-key");
            System.out.println("  加密密码:   java -cp nl2sql-mcp.jar com.nl2sql.mcp.security.PasswordEncryptor encrypt <masterKey> <plainPassword>");
            System.out.println("  解密密码:   java -cp nl2sql-mcp.jar com.nl2sql.mcp.security.PasswordEncryptor decrypt <masterKey> <encryptedPassword>");
            return;
        }

        String command = args[0];
        switch (command) {
            case "generate-key" -> {
                String key = generateMasterKey();
                System.out.println("生成的主密钥: " + key);
                System.out.println("请妥善保管此密钥，建议通过环境变量 NL2SQL_MASTER_KEY 注入");
            }
            case "encrypt" -> {
                if (args.length < 3) {
                    System.out.println("用法: encrypt <masterKey> <plainPassword>");
                    return;
                }
                String encrypted = encrypt(args[1], args[2]);
                System.out.println("加密后的密码: " + encrypted);
                System.out.println("将此值填入 application.yml 的 password 字段");
            }
            case "decrypt" -> {
                if (args.length < 3) {
                    System.out.println("用法: decrypt <masterKey> <encryptedPassword>");
                    return;
                }
                String decrypted = decrypt(args[1], args[2]);
                System.out.println("解密后的密码: " + decrypted);
            }
            default -> System.out.println("未知命令: " + command);
        }
    }
}
