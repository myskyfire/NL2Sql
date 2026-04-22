package com.nl2sql.auth.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * 表权限Mapper
 */
@Mapper
public interface TablePermissionMapper {
    
    /**
     * 查询用户授权的表列表
     */
    List<String> findAuthorizedTables(@Param("userId") Long userId);
    
    /**
     * 插入表权限
     */
    void insertTablePermission(@Param("userId") Long userId,
                               @Param("tableName") String tableName,
                               @Param("grantedBy") String grantedBy);
    
    /**
     * 停用表权限
     */
    void deactivateTablePermission(@Param("userId") Long userId,
                                   @Param("tableName") String tableName);
    
    /**
     * 查询表权限列表
     */
    List<Map<String, Object>> findTablePermissionList();
    
    /**
     * 统计表使用情况
     */
    List<Map<String, Object>> countTableUsage();
    
    /**
     * 查询有权限的用户列表
     */
    List<Map<String, Object>> findUsersWithTablePermission();
}
