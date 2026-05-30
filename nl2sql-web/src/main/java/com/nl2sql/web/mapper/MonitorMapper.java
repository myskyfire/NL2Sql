package com.nl2sql.web.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/**
 * Monitor Mapper - 系统监控相关查询
 */
@Mapper
public interface MonitorMapper {
    
    /**
     * 查询总查询次数
     */
    @Select("SELECT COUNT(*) FROM nl2sql_query_log")
    int countTotalQueries();
    
    /**
     * 查询今日查询次数
     */
    @Select("SELECT COUNT(*) FROM nl2sql_query_log WHERE DATE(created_at) = CURDATE()")
    int countTodayQueries();
    
    /**
     * 查询活跃用户数
     */
    @Select("SELECT COUNT(DISTINCT user_id) FROM nl2sql_query_log WHERE DATE(created_at) >= DATE_SUB(CURDATE(), INTERVAL 7 DAY)")
    int countActiveUsers();
    
    /**
     * 查询慢查询数（超过 3 秒）
     */
    @Select("SELECT COUNT(*) FROM nl2sql_query_log WHERE execution_time_ms > 3000")
    int countSlowQueries();
    
    /**
     * 查询平均执行时间
     */
    @Select("SELECT AVG(execution_time_ms) FROM nl2sql_query_log")
    Double getAvgExecutionTime();
    
    /**
     * 查询数据源数量
     */
    @Select("SELECT COUNT(*) FROM datasource_config WHERE is_active = 1")
    int countActiveDatasources();
    
    /**
     * 查询表元数据总数
     */
    @Select("SELECT COUNT(*) FROM table_metadata")
    int countTables();
    
    /**
     * 查询列元数据总数
     */
    @Select("SELECT COUNT(*) FROM column_metadata")
    int countColumns();
    
    /**
     * 查询热词总数
     */
    @Select("SELECT COUNT(*) FROM hot_word_library")
    int countHotWords();
    
    /**
     * 查询示例总数
     */
    @Select("SELECT COUNT(*) FROM nl2sql_example")
    int countExamples();
}
