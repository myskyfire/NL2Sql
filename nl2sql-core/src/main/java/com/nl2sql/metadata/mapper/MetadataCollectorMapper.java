package com.nl2sql.core.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.List;
import java.util.Map;

/**
 * MetadataCollector Mapper - 元数据采集相关
 */
@Mapper
public interface MetadataCollectorMapper {
    
    @Select("SELECT * FROM table_metadata WHERE datasource_id = #{datasourceId}")
    List<Map<String, Object>> getTableMetadata(@Param("datasourceId") Long datasourceId);
    
    @Select("SELECT * FROM column_metadata WHERE datasource_id = #{datasourceId}")
    List<Map<String, Object>> getColumnMetadata(@Param("datasourceId") Long datasourceId);
}
