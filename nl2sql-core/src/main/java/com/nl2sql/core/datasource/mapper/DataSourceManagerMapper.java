package com.nl2sql.core.datasource.mapper;

import com.nl2sql.metadata.entity.DataSourceConfig;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * DataSourceManager Mapper
 */
@Mapper
public interface DataSourceManagerMapper {
    
    /**
     * 根据 ID 查询数据源配置
     */
    @Select("SELECT id, name, db_type, host, port, database_name, username, password_encrypted " +
            "FROM datasource_config WHERE id = #{datasourceId} AND is_active = 1")
    DataSourceConfig selectDataSourceConfig(@Param("datasourceId") Long datasourceId);
    
    /**
     * 测试数据库连接
     */
    @Select("SELECT 1")
    int testConnection();
}
