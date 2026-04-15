package com.nl2sql.core.datasource;

import com.nl2sql.common.util.EncryptionUtil;
import com.nl2sql.metadata.entity.DataSourceConfig;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 动态数据源管理器
 * 支持多数据源连接池管理和切换
 */
@Slf4j
@Component
public class DataSourceManager {
    
    private final JdbcTemplate metaJdbcTemplate;
    private final Map<Long, HikariDataSource> dataSourcePool = new ConcurrentHashMap<>();
    
    public DataSourceManager(JdbcTemplate jdbcTemplate) {
        this.metaJdbcTemplate = jdbcTemplate;
    }
    
    /**
     * 获取数据源的JdbcTemplate
     */
    public JdbcTemplate getJdbcTemplate(Long datasourceId) {
        HikariDataSource dataSource = getOrCreateDataSource(datasourceId);
        return new JdbcTemplate(dataSource);
    }
    
    /**
     * 获取或创建数据源
     */
    private HikariDataSource getOrCreateDataSource(Long datasourceId) {
        HikariDataSource dataSource = dataSourcePool.get(datasourceId);
        // 检查连接池是否有效
        if (dataSource != null && dataSource.isClosed()) {
            log.warn("检测到已关闭的数据源连接池，将重新创建: id={}", datasourceId);
            dataSourcePool.remove(datasourceId);
            dataSource = null;
        }
        return dataSourcePool.computeIfAbsent(datasourceId, this::createDataSource);
    }
    
    /**
     * 创建数据源连接池
     */
    private HikariDataSource createDataSource(Long datasourceId) {
        try {
            // 从元数据表查询数据源配置
            DataSourceConfig config = loadDataSourceConfig(datasourceId);
            if (config == null) {
                throw new IllegalArgumentException("数据源不存在: id=" + datasourceId);
            }
            
            // 解密密码（兼容加密和明文）
            String password;
            try {
                password = EncryptionUtil.decrypt(config.getPasswordEncrypted());
                log.debug("[数据源] 密码解密成功: id={}", datasourceId);
            } catch (Exception e) {
                // 解密失败，尝试使用明文密码（开发环境兼容）
                log.warn("[数据源] 密码解密失败，尝试使用明文: id={}, error={}", datasourceId, e.getMessage());
                password = config.getPasswordEncrypted();
            }
            
            // 构建JDBC URL
            String jdbcUrl = buildJdbcUrl(config);
            log.info("[数据源创建] ID={}, URL={}", datasourceId, jdbcUrl);
            
            // 创建HikariCP配置
            HikariConfig hikariConfig = new HikariConfig();
            hikariConfig.setJdbcUrl(jdbcUrl);
            hikariConfig.setUsername(config.getUsername());
            hikariConfig.setPassword(password);
            hikariConfig.setDriverClassName(getDriverClassName(config.getDbType()));
            hikariConfig.setMaximumPoolSize(5);
            hikariConfig.setMinimumIdle(1);
            hikariConfig.setConnectionTimeout(30000);
            hikariConfig.setIdleTimeout(600000);
            hikariConfig.setMaxLifetime(1800000);
            hikariConfig.setPoolName("DynamicDS-" + config.getName());
            
            // MySQL特殊配置：确保UTF-8编码
            if ("MYSQL".equalsIgnoreCase(config.getDbType())) {
                // 注意：characterEncoding只能用utf8，不能用utf8mb4
                hikariConfig.addDataSourceProperty("useUnicode", "true");
                hikariConfig.addDataSourceProperty("characterEncoding", "utf8");
                hikariConfig.addDataSourceProperty("connectionCollation", "utf8mb4_unicode_ci");
                hikariConfig.addDataSourceProperty("useSSL", "false");
                hikariConfig.addDataSourceProperty("serverTimezone", "Asia/Shanghai");
                // 关键：允许公钥检索（MySQL 8.0+ caching_sha2_password必需）
                // 必须同时设置在URL和DataSource属性中
                hikariConfig.addDataSourceProperty("allowPublicKeyRetrieval", "true");
                hikariConfig.addDataSourceProperty("cachePrepStmts", "true");
                hikariConfig.addDataSourceProperty("prepStmtCacheSize", "250");
                hikariConfig.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
                // 强制设置连接字符集（每次获取连接时执行）
                hikariConfig.setConnectionInitSql("SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci");
            }
            
            // 创建数据源
            HikariDataSource dataSource = new HikariDataSource(hikariConfig);
            
            log.info("创建数据源连接池成功: id={}, name={}, url={}", datasourceId, config.getName(), jdbcUrl);
            return dataSource;
            
        } catch (Exception e) {
            log.error("创建数据源连接池失败: id={}", datasourceId, e);
            throw new RuntimeException("创建数据源连接失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 从数据库加载数据源配置
     */
    private DataSourceConfig loadDataSourceConfig(Long datasourceId) {
        try {
            String sql = "SELECT id, name, db_type, host, port, database_name, username, password_encrypted " +
                        "FROM datasource_config WHERE id = ? AND is_active = 1";
            
            return metaJdbcTemplate.queryForObject(sql, 
                (rs, rowNum) -> {
                    DataSourceConfig config = new DataSourceConfig();
                    config.setId(rs.getLong("id"));
                    config.setName(rs.getString("name"));
                    config.setDbType(rs.getString("db_type"));
                    config.setHost(rs.getString("host"));
                    config.setPort(rs.getInt("port"));
                    config.setDatabaseName(rs.getString("database_name"));
                    config.setUsername(rs.getString("username"));
                    config.setPasswordEncrypted(rs.getString("password_encrypted"));
                    return config;
                },
                datasourceId
            );
        } catch (Exception e) {
            log.error("加载数据源配置失败: id={}", datasourceId, e);
            return null;
        }
    }
    
    /**
     * 构建JDBC URL
     */
    private String buildJdbcUrl(DataSourceConfig config) {
        switch (config.getDbType().toUpperCase()) {
            case "MYSQL":
                // 注意：characterEncoding只能用utf8，utf8mb4通过connectionCollation设置
                return String.format(
                    "jdbc:mysql://%s:%d/%s?allowPublicKeyRetrieval=true&useSSL=false&useUnicode=true&characterEncoding=utf8&connectionCollation=utf8mb4_unicode_ci&serverTimezone=Asia/Shanghai",
                    config.getHost(), config.getPort(), config.getDatabaseName()
                );
            case "POSTGRESQL":
                return String.format(
                    "jdbc:postgresql://%s:%d/%s",
                    config.getHost(), config.getPort(), config.getDatabaseName()
                );
            case "ORACLE":
                return String.format(
                    "jdbc:oracle:thin:@%s:%d:%s",
                    config.getHost(), config.getPort(), config.getDatabaseName()
                );
            default:
                throw new IllegalArgumentException("不支持的数据库类型: " + config.getDbType());
        }
    }
    
    /**
     * 获取驱动类名
     */
    private String getDriverClassName(String dbType) {
        switch (dbType.toUpperCase()) {
            case "MYSQL":
                return "com.mysql.cj.jdbc.Driver";
            case "POSTGRESQL":
                return "org.postgresql.Driver";
            case "ORACLE":
                return "oracle.jdbc.OracleDriver";
            default:
                throw new IllegalArgumentException("不支持的数据库类型: " + dbType);
        }
    }
    
    /**
     * 关闭所有数据源
     */
    @PreDestroy
    public void destroy() {
        log.info("关闭所有动态数据源连接池...");
        dataSourcePool.values().forEach(dataSource -> {
            try {
                dataSource.close();
                log.info("关闭数据源: {}", dataSource.getPoolName());
            } catch (Exception e) {
                log.warn("关闭数据源失败", e);
            }
        });
        dataSourcePool.clear();
    }
    
    /**
     * 移除数据源（用于配置更新时）
     */
    public void removeDataSource(Long datasourceId) {
        HikariDataSource dataSource = dataSourcePool.remove(datasourceId);
        if (dataSource != null) {
            try {
                dataSource.close();
                log.info("移除数据源: id={}", datasourceId);
            } catch (Exception e) {
                log.warn("关闭数据源失败: id={}", datasourceId, e);
            }
        }
    }
}
