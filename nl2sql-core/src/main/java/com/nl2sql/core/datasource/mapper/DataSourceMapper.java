package com.nl2sql.core.datasource.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * 数据源Mapper
 */
@Mapper
public interface DataSourceMapper {
    
    /**
     * 查询所有数据源
     */
    List<Map<String, Object>> findAllDataSources();
}
