package com.nl2sql.metadata.service;

import com.nl2sql.common.util.EncryptionUtil;
import com.nl2sql.core.datasource.mapper.DataSourceMapper;
import com.nl2sql.metadata.entity.DataSourceConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import java.sql.*;
import java.util.List;

@Slf4j
@Service
public class DataSourceConfigService {
    
    private final JdbcTemplate jdbcTemplate;
    
    @Autowired
    private DataSourceMapper dataSourceMapper;
    
    public DataSourceConfigService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }
    
    /**
     * 保存数据源配置
     */
    public Long saveConfig(DataSourceConfig config) {
        // 加密密码
        String encryptedPassword = EncryptionUtil.encrypt(config.getPassword());
        config.setPasswordEncrypted(encryptedPassword);
        
        dataSourceMapper.insertDataSource(config);
        
        Long id = config.getId();
        log.info("保存数据源配置成功: id={}, name={}", id, config.getName());
        
        return id;
    }
    
    /**
     * 测试数据库连接
     */
    public boolean testConnection(DataSourceConfig config) {
        Connection conn = null;
        try {
            String url = buildJdbcUrl(config);
            conn = DriverManager.getConnection(url, config.getUsername(), config.getPassword());
            
            DatabaseMetaData metaData = conn.getMetaData();
            log.info("连接测试成功: {} - {}", metaData.getDatabaseProductName(), metaData.getDatabaseProductVersion());
            
            return true;
        } catch (Exception e) {
            log.error("连接测试失败: {}", e.getMessage());
            return false;
        } finally {
            closeConnection(conn);
        }
    }
    
    /**
     * 获取所有激活的数据源
     */
    public List<DataSourceConfig> listActiveConfigs() {
        String sql = "SELECT * FROM datasource_config WHERE is_active = 1 ORDER BY created_at DESC";
        return jdbcTemplate.query(sql, new DataSourceRowMapper());
    }
    
    /**
     * 根据ID获取配置（含解密密码）
     */
    public DataSourceConfig getConfigById(Long id) {
        DataSourceConfig config = dataSourceMapper.selectById(id);
        
        if (config != null) {
            // 解密密码
            config.setPassword(EncryptionUtil.decrypt(config.getPasswordEncrypted()));
        }
        
        return config;
    }
    
    /**
     * 构建JDBC URL
     */
    private String buildJdbcUrl(DataSourceConfig config) {
        switch (config.getDbType().toUpperCase()) {
            case "MYSQL":
                return String.format("jdbc:mysql://%s:%d/%s?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai",
                    config.getHost(), config.getPort(), config.getDatabaseName());
            case "ORACLE":
                return String.format("jdbc:oracle:thin:@%s:%d:%s",
                    config.getHost(), config.getPort(), config.getDatabaseName());
            case "DAMENG":
                return String.format("jdbc:dm://%s:%d/%s",
                    config.getHost(), config.getPort(), config.getDatabaseName());
            case "POSTGRESQL":
                return String.format("jdbc:postgresql://%s:%d/%s",
                    config.getHost(), config.getPort(), config.getDatabaseName());
            default:
                throw new IllegalArgumentException("不支持的数据库类型: " + config.getDbType());
        }
    }
    
    /**
     * 关闭连接
     */
    private void closeConnection(Connection conn) {
        if (conn != null) {
            try {
                conn.close();
            } catch (SQLException e) {
                log.error("关闭连接失败", e);
            }
        }
    }
    
    /**
     * RowMapper
     */
    private static class DataSourceRowMapper implements RowMapper<DataSourceConfig> {
        @Override
        public DataSourceConfig mapRow(ResultSet rs, int rowNum) throws SQLException {
            DataSourceConfig config = new DataSourceConfig();
            config.setId(rs.getLong("id"));
            config.setName(rs.getString("name"));
            config.setDbType(rs.getString("db_type"));
            config.setHost(rs.getString("host"));
            config.setPort(rs.getInt("port"));
            config.setDatabaseName(rs.getString("database_name"));
            config.setUsername(rs.getString("username"));
            config.setPasswordEncrypted(rs.getString("password_encrypted"));
            config.setEncryptionAlgorithm(rs.getString("encryption_algorithm"));
            config.setIsActive(rs.getInt("is_active"));
            config.setDescription(rs.getString("description"));
            config.setCreatedBy(rs.getLong("created_by"));
            config.setCreatedAt(rs.getTimestamp("created_at").toLocalDateTime());
            config.setUpdatedAt(rs.getTimestamp("updated_at").toLocalDateTime());
            return config;
        }
    }
}
