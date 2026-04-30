package com.nl2sql.web.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.List;
import java.util.Map;

/**
 * AdminMetadata Mapper - 元数据管理相关查询
 */
@Mapper
public interface AdminMetadataMapper {
    
    /**
     * 查询所有数据源配置
     */
    @Select("SELECT * FROM datasource_config")
    List<Map<String, Object>> getAllDatasources();
    
    /**
     * 查询活跃数据源
     */
    @Select("SELECT * FROM datasource_config WHERE is_active = 1")
    List<Map<String, Object>> getActiveDatasources();
    
    /**
     * 查询表元数据统计
     */
    @Select("SELECT table_name, column_count, last_updated FROM table_metadata ORDER BY table_name")
    List<Map<String, Object>> getTableMetadataStats();
    
    /**
     * 查询列元数据
     */
    @Select("SELECT table_name, column_name, data_type, is_nullable FROM column_metadata WHERE table_name = #{tableName}")
    List<Map<String, Object>> getColumnMetadata(@Param("tableName") String tableName);
}
