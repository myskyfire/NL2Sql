package com.nl2sql.mcp.persistence;

import com.nl2sql.mcp.datasource.DataSourcePoolManager.DataSourceConfig;
import com.nl2sql.mcp.security.PasswordEncryptor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.sql.*;
import java.util.HashMap;
import java.util.Map;

/**
 * SQLite 数据源持久化存储
 * 
 * 将数据源配置持久化到本地 SQLite 文件，重启后自动恢复
 */
@Slf4j
@Component
public class DatasourceRepository {

    @Value("${nl2sql.persistence.sqlite.path:./data/nl2sql.db}")
    private String dbPath;

    @Value("${nl2sql.security.master-key:}")
    private String masterKey;

    private Connection connection;

    @PostConstruct
    public void init() {
        try {
            // 确保目录存在
            java.nio.file.Path path = java.nio.file.Paths.get(dbPath).toAbsolutePath();
            java.nio.file.Files.createDirectories(path.getParent());

            connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
            createTable();
            log.info("SQLite 持久化存储初始化成功: {}", path);
        } catch (Exception e) {
            log.error("初始化 SQLite 存储失败", e);
            throw new RuntimeException("初始化 SQLite 存储失败", e);
        }
    }

    @PreDestroy
    public void destroy() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException e) {
                log.warn("关闭 SQLite 连接失败", e);
            }
        }
    }

    private void createTable() throws SQLException {
        String sql = """
            CREATE TABLE IF NOT EXISTS datasources (
                id INTEGER PRIMARY KEY,
                name TEXT NOT NULL,
                db_type TEXT NOT NULL DEFAULT 'MYSQL',
                jdbc_url TEXT NOT NULL,
                username TEXT NOT NULL,
                password TEXT NOT NULL,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )
            """;
        try (Statement stmt = connection.createStatement()) {
            stmt.execute(sql);
        }
    }

    /**
     * 加载所有数据源配置
     */
    public Map<Long, DataSourceConfig> loadAll() {
        Map<Long, DataSourceConfig> result = new HashMap<>();
        String sql = "SELECT id, name, db_type, jdbc_url, username, password FROM datasources ORDER BY id";
        
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            
            while (rs.next()) {
                Long id = rs.getLong("id");
                DataSourceConfig config = new DataSourceConfig();
                config.setName(rs.getString("name"));
                config.setDbType(rs.getString("db_type"));
                config.setJdbcUrl(rs.getString("jdbc_url"));
                config.setUsername(rs.getString("username"));
                // 解密密码
                String encryptedPassword = rs.getString("password");
                config.setPassword(decryptPassword(encryptedPassword));
                result.put(id, config);
            }
            
            log.info("从 SQLite 加载 {} 个数据源配置", result.size());
        } catch (SQLException e) {
            log.error("加载数据源配置失败", e);
        }
        
        return result;
    }

    /**
     * 保存数据源配置（密码加密后存储）
     */
    public synchronized Long save(DataSourceConfig config) {
        String sql = "INSERT INTO datasources (name, db_type, jdbc_url, username, password) VALUES (?, ?, ?, ?, ?)";
        
        try (PreparedStatement pstmt = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            pstmt.setString(1, config.getName());
            pstmt.setString(2, config.getDbType() != null ? config.getDbType() : "MYSQL");
            pstmt.setString(3, config.getJdbcUrl());
            pstmt.setString(4, config.getUsername());
            // 加密密码
            String encryptedPassword = encryptPassword(config.getPassword());
            pstmt.setString(5, encryptedPassword);
            pstmt.executeUpdate();
            
            try (ResultSet rs = pstmt.getGeneratedKeys()) {
                if (rs.next()) {
                    Long id = rs.getLong(1);
                    log.info("保存数据源到 SQLite: id={}, name={}", id, config.getName());
                    return id;
                }
            }
        } catch (SQLException e) {
            log.error("保存数据源配置失败", e);
            throw new RuntimeException("保存数据源配置失败", e);
        }
        
        throw new RuntimeException("保存数据源失败：未获取到生成的 ID");
    }

    /**
     * 删除数据源配置
     */
    public synchronized void delete(Long id) {
        String sql = "DELETE FROM datasources WHERE id = ?";
        
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setLong(1, id);
            int rows = pstmt.executeUpdate();
            if (rows > 0) {
                log.info("从 SQLite 删除数据源: id={}", id);
            }
        } catch (SQLException e) {
            log.error("删除数据源配置失败", e);
            throw new RuntimeException("删除数据源配置失败", e);
        }
    }

    /**
     * 加密密码
     */
    private String encryptPassword(String plainPassword) {
        if (plainPassword == null || plainPassword.isEmpty()) {
            return plainPassword;
        }
        if (masterKey == null || masterKey.isEmpty()) {
            throw new IllegalStateException("主密钥未配置，无法加密密码。请设置 nl2sql.security.master-key");
        }
        return PasswordEncryptor.encrypt(masterKey, plainPassword);
    }

    /**
     * 解密密码
     */
    private String decryptPassword(String encryptedPassword) {
        if (encryptedPassword == null || encryptedPassword.isEmpty()) {
            return encryptedPassword;
        }
        if (!encryptedPassword.startsWith("ENC(AES256:")) {
            return encryptedPassword; // 未加密，直接返回（兼容旧数据）
        }
        if (masterKey == null || masterKey.isEmpty()) {
            throw new IllegalStateException("主密钥未配置，无法解密密码。请设置 nl2sql.security.master-key");
        }
        return PasswordEncryptor.decrypt(masterKey, encryptedPassword);
    }
}
