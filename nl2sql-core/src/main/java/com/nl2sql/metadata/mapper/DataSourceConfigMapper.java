package com.nl2sql.metadata.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.List;
import java.util.Map;

/**
 * DataSourceConfig Mapper - 数据源配置相关查询
 */
@Mapper
public interface DataSourceConfigMapper {
    
    @Select("SELECT * FROM datasource_config WHERE id = #{id}")
    Map<String, Object> getDatasourceById(@Param("id") Long id);
    
    @Select("SELECT * FROM datasource_config WHERE is_active = 1")
    List<Map<String, Object>> getActiveDatasources();
    
    @Select("SELECT * FROM datasource_config")
    List<Map<String, Object>> getAllDatasources();
}
