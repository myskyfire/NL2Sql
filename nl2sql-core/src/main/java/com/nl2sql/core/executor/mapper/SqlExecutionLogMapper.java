package com.nl2sql.core.executor.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * SQL执行日志Mapper
 */
@Mapper
public interface SqlExecutionLogMapper {
    
    /**
     * 插入SQL执行日志
     */
    void insertSqlExecutionLog(@Param("userId") Long userId,
                               @Param("username") String username,
                               @Param("sessionId") String sessionId,
                               @Param("question") String question,
                               @Param("generatedSql") String generatedSql,
                               @Param("datasourceId") Long datasourceId,
                               @Param("datasourceName") String datasourceName,
                               @Param("executionTime") Long executionTime,
                               @Param("rowCount") Integer rowCount,
                               @Param("status") String status,
                               @Param("errorMessage") String errorMessage,
                               @Param("ipAddress") String ipAddress);
}
