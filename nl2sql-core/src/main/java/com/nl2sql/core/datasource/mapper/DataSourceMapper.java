package com.nl2sql.core.datasource.mapper;

import com.nl2sql.metadata.entity.DataSourceConfig;
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
    
    // ==================== 新增方法 ====================
    
    /**
     * 插入数据源配置
     */
    int insertDataSource(DataSourceConfig config);
    
    /**
     * 根据ID查询数据源
     */
    DataSourceConfig selectById(@Param("id") Long id);
}
