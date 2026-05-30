package com.nl2sql.mcp.datasource;

import lombok.Getter;

import java.util.HashMap;
import java.util.Map;

/**
 * 支持的数据库类型
 */
@Getter
public enum DbType {

    MYSQL("MySQL", "com.mysql.cj.jdbc.Driver", "jdbc:mysql://"),
    POSTGRESQL("PostgreSQL", "org.postgresql.Driver", "jdbc:postgresql://"),
    ORACLE("Oracle", "oracle.jdbc.OracleDriver", "jdbc:oracle:thin:@"),
    DM("达梦", "dm.jdbc.driver.DmDriver", "jdbc:dm://"),
    SQLSERVER("SQL Server", "com.microsoft.sqlserver.jdbc.SQLServerDriver", "jdbc:sqlserver://");

    private final String name;
    private final String driverClass;
    private final String jdbcPrefix;

    DbType(String name, String driverClass, String jdbcPrefix) {
        this.name = name;
        this.driverClass = driverClass;
        this.jdbcPrefix = jdbcPrefix;
    }

    /**
     * 根据 JDBC URL 自动识别数据库类型
     */
    public static DbType fromJdbcUrl(String jdbcUrl) {
        if (jdbcUrl == null) return null;
        String url = jdbcUrl.toLowerCase();
        for (DbType dbType : values()) {
            if (url.startsWith(dbType.jdbcPrefix.toLowerCase())) {
                return dbType;
            }
        }
        return null;
    }

    /**
     * 获取所有支持的数据库类型（用于前端下拉选择）
     */
    public static Map<String, String> getSupportedTypes() {
        Map<String, String> map = new HashMap<>();
        for (DbType dbType : values()) {
            map.put(dbType.name(), dbType.name);
        }
        return map;
    }
}
