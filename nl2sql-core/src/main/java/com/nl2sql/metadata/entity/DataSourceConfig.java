package com.nl2sql.metadata.entity;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class DataSourceConfig {
    private Long id;
    private String name;
    private String dbType; // MYSQL, ORACLE, DAMENG, POSTGRESQL
    private String host;
    private Integer port;
    private String databaseName;
    private String username;
    private String passwordEncrypted;
    private String encryptionAlgorithm;
    private Integer isActive;
    private String description;
    private String businessCategory; // 业务类别标签（逗号分隔）
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    
    // 解密后的密码（仅内存中使用，不持久化）
    private transient String password;
}
