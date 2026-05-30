package com.nl2sql.core.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 元数据 Mapper
 */
@Mapper
public interface MetadataMapper {
    
    /**
     * 统计数据源数量
     */
    Integer countDistinctDatasources();
    
    /**
     * 统计指定数据源的表是否存在
     */
    Integer countTableByDatasource(@Param("datasourceId") Long datasourceId,
                                   @Param("tableName") String tableName);
    
    /**
     * 获取表的中文注释
     */
    String getTableComment(@Param("datasourceId") Long datasourceId,
                          @Param("tableName") String tableName);
    
    /**
     * 获取数据源的数据库类型（MySQL, Oracle, DM, PostgreSQL等）
     */
    String getDbType(@Param("datasourceId") Long datasourceId);
}
