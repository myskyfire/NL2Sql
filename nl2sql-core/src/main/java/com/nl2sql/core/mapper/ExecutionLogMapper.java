package com.nl2sql.core.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * SQL执行日志Mapper
 */
@Mapper
public interface ExecutionLogMapper {
    
    /**
     * 记录SQL执行日志
     */
    void insertExecutionLog(@Param("userId") Long userId,
                           @Param("username") String username,
                           @Param("sqlText") String sqlText,
                           @Param("executionTimeMs") Long executionTimeMs,
                           @Param("rowCount") Integer rowCount,
                           @Param("isSlowQuery") int isSlowQuery,
                           @Param("status") String status,
                           @Param("errorMessage") String errorMessage,
                           @Param("ipAddress") String ipAddress);
}
