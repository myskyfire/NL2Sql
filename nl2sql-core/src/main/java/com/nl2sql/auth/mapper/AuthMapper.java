package com.nl2sql.auth.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 认证授权Mapper
 */
@Mapper
public interface AuthMapper {
    
    /**
     * 检查用户是否存在
     */
    Integer checkUserExists(@Param("userId") Long userId);
    
    /**
     * 添加白名单（存在则更新）
     */
    void insertOrUpdateWhitelist(@Param("userId") Long userId,
                                 @Param("addedBy") Long addedBy,
                                 @Param("reason") String reason,
                                 @Param("expiresAt") String expiresAt);
    
    /**
     * 撤销表权限
     */
    int revokeTablePermission(@Param("userId") Long userId, 
                             @Param("tableName") String tableName);
    
    /**
     * 停用白名单
     */
    int deactivateWhitelist(@Param("userId") Long userId);
    
    /**
     * 获取用户名
     */
    String getUsernameById(@Param("userId") Long userId);
}
