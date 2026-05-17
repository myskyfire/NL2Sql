package com.nl2sql.mcp.datasource;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
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

    @Data
    public static class DataSourceConfig {
        private String name;
        private String jdbcUrl;
        private String username;
        private String password;
    }

    /**
     * 设置数据源配置（由 Spring Boot 自动注入）
     */
    public void setDatasources(Map<Long, DataSourceConfig> datasources) {
        this.datasources = datasources;
        log.info("加载 {} 个数据源配置", datasources.size());
    }

    /**
     * 动态添加数据源（运行时）
     */
    public synchronized Long addDatasource(String name, String jdbcUrl, String username, String password) {
        // 生成新的 datasource_id（取最大 ID + 1）
        Long newId = datasources.keySet().stream()
            .max(Long::compareTo)
            .orElse(0L) + 1;
        
        DataSourceConfig config = new DataSourceConfig();
        config.setName(name);
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(username);
        config.setPassword(password);
        
        datasources.put(newId, config);
        log.info("动态添加数据源: id={}, name={}", newId, name);
        
        // TODO: 持久化到配置文件或数据库
        
        return newId;
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
            return createHikariPool(config.getJdbcUrl(), config.getUsername(), config.getPassword());
        });
    }

    /**
     * 创建 HikariCP 连接池
     */
    private HikariDataSource createHikariPool(String jdbcUrl, String username, String password) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(username);
        config.setPassword(password);
        
        // 连接池配置
        config.setMaximumPoolSize(10);
        config.setMinimumIdle(2);
        config.setConnectionTimeout(30000);
        config.setIdleTimeout(600000);
        config.setMaxLifetime(1800000);
        
        // MySQL 优化
        config.addDataSourceProperty("cachePrepStmts", "true");
        config.addDataSourceProperty("prepStmtCacheSize", "250");
        config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        config.addDataSourceProperty("useServerPrepStmts", "true");
        
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
