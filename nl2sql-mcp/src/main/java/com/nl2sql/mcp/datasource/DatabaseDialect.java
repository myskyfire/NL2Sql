package com.nl2sql.mcp.datasource;

import com.zaxxer.hikari.HikariConfig;

import java.util.Properties;

/**
 * 数据库方言配置
 * 
 * 为不同数据库提供最优的 HikariCP 连接池配置
 */
public class DatabaseDialect {

    /**
     * 应用数据库特定的 HikariCP 配置
     */
    public static void applyDialectConfig(HikariConfig config, DbType dbType) {
        switch (dbType) {
            case MYSQL -> applyMySQLConfig(config);
            case POSTGRESQL -> applyPostgreSQLConfig(config);
            case ORACLE -> applyOracleConfig(config);
            case DM -> applyDMConfig(config);
            case SQLSERVER -> applySQLServerConfig(config);
        }
    }

    private static void applyMySQLConfig(HikariConfig config) {
        config.setMaximumPoolSize(20);
        config.setMinimumIdle(5);
        config.setConnectionTimeout(30000);
        config.setIdleTimeout(600000);
        config.setMaxLifetime(1800000);
        
        // MySQL 优化
        Properties props = new Properties();
        props.setProperty("cachePrepStmts", "true");
        props.setProperty("prepStmtCacheSize", "250");
        props.setProperty("prepStmtCacheSqlLimit", "2048");
        props.setProperty("useServerPrepStmts", "true");
        props.setProperty("useLocalSessionState", "true");
        props.setProperty("rewriteBatchedStatements", "true");
        props.setProperty("cacheResultSetMetadata", "true");
        props.setProperty("cacheServerConfiguration", "true");
        props.setProperty("elideSetAutoCommits", "true");
        config.setDataSourceProperties(props);
    }

    private static void applyPostgreSQLConfig(HikariConfig config) {
        config.setMaximumPoolSize(20);
        config.setMinimumIdle(5);
        config.setConnectionTimeout(30000);
        config.setIdleTimeout(600000);
        config.setMaxLifetime(1800000);
        
        // PostgreSQL 优化
        Properties props = new Properties();
        props.setProperty("prepareThreshold", "5");
        props.setProperty("preparedStatementCacheQueries", "256");
        props.setProperty("databaseMetadataCacheFields", "65536");
        config.setDataSourceProperties(props);
    }

    private static void applyOracleConfig(HikariConfig config) {
        config.setMaximumPoolSize(15);
        config.setMinimumIdle(3);
        config.setConnectionTimeout(60000);
        config.setIdleTimeout(600000);
        config.setMaxLifetime(1800000);
        
        // Oracle 优化
        Properties props = new Properties();
        props.setProperty("oracle.jdbc.ReadTimeout", "30000");
        props.setProperty("oracle.net.CONNECT_TIMEOUT", "30000");
        config.setDataSourceProperties(props);
    }

    private static void applyDMConfig(HikariConfig config) {
        config.setMaximumPoolSize(15);
        config.setMinimumIdle(3);
        config.setConnectionTimeout(30000);
        config.setIdleTimeout(600000);
        config.setMaxLifetime(1800000);
    }

    private static void applySQLServerConfig(HikariConfig config) {
        config.setMaximumPoolSize(15);
        config.setMinimumIdle(3);
        config.setConnectionTimeout(30000);
        config.setIdleTimeout(600000);
        config.setMaxLifetime(1800000);
        
        // SQL Server 优化
        Properties props = new Properties();
        props.setProperty("encrypt", "false");
        props.setProperty("trustServerCertificate", "true");
        config.setDataSourceProperties(props);
    }
}
