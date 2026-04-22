package com.nl2sql.auth.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * 白名单Mapper
 */
@Mapper
public interface WhitelistMapper {
    
    /**
     * 检查用户是否在白名单中
     */
    int countActiveWhitelist(@Param("userId") Long userId);
    
    /**
     * 插入白名单记录
     */
    void insertWhitelist(@Param("userId") Long userId,
                         @Param("addedBy") String addedBy,
                         @Param("reason") String reason,
                         @Param("expiresAt") String expiresAt);
    
    /**
     * 停用白名单
     */
    void deactivateWhitelist(@Param("userId") Long userId);
    
    /**
     * 查询白名单列表
     */
    List<Map<String, Object>> findWhitelistList();
}
