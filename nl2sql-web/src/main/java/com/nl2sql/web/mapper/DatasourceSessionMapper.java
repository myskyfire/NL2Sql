package com.nl2sql.web.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.List;
import java.util.Map;

/**
 * DatasourceSession Mapper - 数据源会话相关
 */
@Mapper
public interface DatasourceSessionMapper {
    
    @Select("SELECT * FROM datasource_sessions WHERE session_id = #{sessionId}")
    Map<String, Object> getSession(@Param("sessionId") String sessionId);
    
    @Select("SELECT * FROM datasource_sessions WHERE user_id = #{userId}")
    List<Map<String, Object>> getUserSessions(@Param("userId") Long userId);
    
    /**
     * 统计活跃数据源数量
     */
    @Select("SELECT COUNT(*) FROM datasource_config WHERE is_active = 1")
    Integer countActiveDatasources();
    
    /**
     * 获取唯一活跃数据源 ID
     */
    @Select("SELECT id FROM datasource_config WHERE is_active = 1 LIMIT 1")
    Long getSingleActiveDatasource();
    
    /**
     * 获取所有活跃数据源列表
     */
    @Select("SELECT id, name, database_name, description FROM datasource_config WHERE is_active = 1 ORDER BY name")
    List<Map<String, Object>> getActiveDatasources();
    
    /**
     * 获取除指定 ID 外的活跃数据源列表
     */
    @Select("SELECT id, name, database_name, description FROM datasource_config WHERE is_active = 1 AND id != #{excludeId} ORDER BY name")
    List<Map<String, Object>> getAlternativeDatasources(@Param("excludeId") Long excludeId);
    
    /**
     * 获取数据源的数据库名
     */
    @Select("SELECT database_name FROM datasource_config WHERE id = #{datasourceId}")
    String getDatabaseName(@Param("datasourceId") Long datasourceId);
    
    /**
     * 检查表是否存在
     */
    @Select("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = #{schemaName} AND table_name = #{tableName}")
    Integer checkTableExists(@Param("schemaName") String schemaName, @Param("tableName") String tableName);
}
