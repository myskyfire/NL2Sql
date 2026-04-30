package com.nl2sql.core.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.List;
import java.util.Map;

/**
 * DataSourceManager Mapper - 数据源管理相关
 */
@Mapper
public interface DataSourceManagerMapper {
    
    @Select("SELECT * FROM datasource_config WHERE id = #{id}")
    Map<String, Object> getDatasource(@Param("id") Long id);
    
    @Select("SELECT * FROM datasource_config WHERE is_active = 1")
    List<Map<String, Object>> getActiveDatasources();
    
    @Select("SELECT connection_string FROM datasource_config WHERE id = #{id}")
    String getConnectionString(@Param("id") Long id);
}
