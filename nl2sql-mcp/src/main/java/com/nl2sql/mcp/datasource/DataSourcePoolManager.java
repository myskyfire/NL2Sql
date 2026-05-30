package com.nl2sql.mcp.datasource;

import com.nl2sql.mcp.persistence.DatasourceRepository;
import com.nl2sql.mcp.security.PasswordDecryptor;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 数据源连接池管理器
 * 
 * 从配置文件读取数据源凭据，AI 只需传 datasource_id
 */
@Slf4j
@Component
@ConfigurationProperties(prefix = "nl2sql.datasources")
public class DataSourcePoolManager {

    private final Map<Long, HikariDataSource> poolCache = new ConcurrentHashMap<>();
    private Map<Long, DataSourceConfig> datasources = new ConcurrentHashMap<>();

    @Autowired
    private PasswordDecryptor passwordDecryptor;

    @Autowired
    private DatasourceRepository datasourceRepository;

    @Data
    public static class DataSourceConfig {
        private String name;
        private String dbType;
        private String jdbcUrl;
        private String username;
        private String password;
    }

    /**
     * 设置数据源配置（由 Spring Boot 自动注入，合并 SQLite 中的数据）
     */
    public void setDatasources(Map<Long, DataSourceConfig> datasources) {
        // 先加载 SQLite 中的数据
        Map<Long, DataSourceConfig> persisted = datasourceRepository.loadAll();
        this.datasources.putAll(persisted);
        
        // 再合并配置文件中的数据（配置文件优先级更高）
        this.datasources.putAll(datasources);
        
        log.info("加载 {} 个数据源配置（SQLite: {}, 配置文件: {}）", 
            this.datasources.size(), persisted.size(), datasources.size());
    }

    /**
     * 动态添加数据源（运行时）
     */
    public synchronized Long addDatasource(String name, String dbType, String jdbcUrl, String username, String password) {
        DataSourceConfig config = new DataSourceConfig();
        config.setName(name);
        config.setDbType(dbType);
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(username);
        config.setPassword(password);
        
        // 持久化到 SQLite
        Long newId = datasourceRepository.save(config);
        
        // 加入内存
        datasources.put(newId, config);
        log.info("动态添加数据源: id={}, name={}, type={}", newId, name, dbType);
        
        return newId;
    }

    /**
     * 动态添加数据源（兼容旧接口，自动识别数据库类型）
     */
    public synchronized Long addDatasource(String name, String jdbcUrl, String username, String password) {
        DbType dbType = DbType.fromJdbcUrl(jdbcUrl);
        if (dbType == null) {
            throw new IllegalArgumentException("无法识别的 JDBC URL: " + jdbcUrl);
        }
        return addDatasource(name, dbType.name(), jdbcUrl, username, password);
    }

    /**
     * 删除数据源
     */
    public synchronized void removeDatasource(Long datasourceId) {
        // 从 SQLite 删除
        datasourceRepository.delete(datasourceId);
        
        // 从内存删除
        datasources.remove(datasourceId);
        closePool(datasourceId);
        log.info("删除数据源: id={}", datasourceId);
    }

    /**
     * 获取或创建数据源连接池
     */
    public DataSource getDataSource(Long datasourceId) {
        return poolCache.computeIfAbsent(datasourceId, id -> {
            DataSourceConfig config = datasources.get(id);
            if (config == null) {
                throw new IllegalArgumentException("未知的数据源 ID: " + id + "，可用数据源: " + datasources.keySet());
            }
            log.info("创建数据源连接池: id={}, name={}", id, config.getName());
            return createHikariPool(config.getDbType(), config.getJdbcUrl(), config.getUsername(), config.getPassword());
        });
    }

    /**
     * 创建 HikariCP 连接池
     */
    private HikariDataSource createHikariPool(String dbType, String jdbcUrl, String username, String password) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(username);
        config.setPassword(passwordDecryptor.decrypt(password));
        
        // 识别数据库类型并应用方言配置
        DbType type = dbType != null ? DbType.valueOf(dbType) : DbType.fromJdbcUrl(jdbcUrl);
        if (type == null) {
            throw new IllegalArgumentException("无法识别的数据库类型: " + dbType + ", JDBC URL: " + jdbcUrl);
        }
        
        config.setDriverClassName(type.getDriverClass());
        DatabaseDialect.applyDialectConfig(config, type);
        
        log.info("创建连接池: type={}, url={}", type.getName(), jdbcUrl);
        return new HikariDataSource(config);
    }

    /**
     * 获取数据库连接
     */
    public Connection getConnection(Long datasourceId) throws SQLException {
        DataSource dataSource = getDataSource(datasourceId);
        return dataSource.getConnection();
    }

    /**
     * 获取所有可用数据源（不包含密码）
     */
    public Map<Long, DataSourceConfig> getAvailableDatasources() {
        return datasources;
    }

    /**
     * 关闭指定数据源的连接池
     */
    public void closePool(Long datasourceId) {
        HikariDataSource pool = poolCache.remove(datasourceId);
        if (pool != null && !pool.isClosed()) {
            log.info("关闭数据源连接池: datasourceId={}", datasourceId);
            pool.close();
        }
    }

    /**
     * 关闭所有连接池
     */
    public void closeAll() {
        log.info("关闭所有数据源连接池，数量: {}", poolCache.size());
        poolCache.values().forEach(pool -> {
            if (!pool.isClosed()) {
                pool.close();
            }
        });
        poolCache.clear();
    }
}
