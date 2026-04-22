package com.nl2sql.auth.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * 用户认证Mapper
 */
@Mapper
public interface UserMapper {
    
    /**
     * 根据用户名查询用户
     */
    Map<String, Object> findByUsername(@Param("username") String username);
    
    /**
     * 更新最后登录时间
     */
    void updateLastLoginAt(@Param("userId") Long userId);
    
    /**
     * 根据ID查询角色
     */
    String findRoleById(@Param("id") Long id);
    
    /**
     * 插入新用户
     */
    void insertUser(@Param("username") String username,
                    @Param("password") String password,
                    @Param("realName") String realName,
                    @Param("role") String role);
    
    /**
     * 更新密码
     */
    void updatePassword(@Param("id") Long id, @Param("password") String password);
    
    /**
     * 查询所有用户列表
     */
    List<Map<String, Object>> findAllUsers();
}
