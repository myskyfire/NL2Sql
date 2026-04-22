package com.nl2sql.auth.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 操作日志Mapper
 */
@Mapper
public interface OperationLogMapper {
    
    /**
     * 插入操作日志
     */
    void insertOperationLog(@Param("userId") Long userId,
                            @Param("username") String username,
                            @Param("operation") String operation,
                            @Param("details") String details,
                            @Param("ipAddress") String ipAddress);
}
