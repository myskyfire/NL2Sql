package com.nl2sql.web.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import java.util.List;
import java.util.Map;

/**
 * DataSourceConfig Mapper - 数据源配置相关查询
 */
@Mapper
public interface DataSourceConfigMapper {
    
    /**
     * 保存数据源配置
     */
    @Insert("INSERT INTO datasource_config (name, db_type, host, port, database_name, username, password_encrypted, description, is_active, created_by) " +
            "VALUES (#{name}, #{dbType}, #{host}, #{port}, #{databaseName}, #{username}, #{passwordEncrypted}, #{description}, #{isActive}, #{createdBy})")
    void saveConfig(@Param("name") String name,
                   @Param("dbType") String dbType,
                   @Param("host") String host,
                   @Param("port") Integer port,
                   @Param("databaseName") String databaseName,
                   @Param("username") String username,
                   @Param("passwordEncrypted") String passwordEncrypted,
                   @Param("description") String description,
                   @Param("isActive") Integer isActive,
                   @Param("createdBy") String createdBy);
    
    /**
     * 获取最后插入的 ID
     */
    @Select("SELECT LAST_INSERT_ID()")
    Long getLastInsertId();
    
    /**
     * 查询所有激活的数据源
     */
    @Select("SELECT * FROM datasource_config WHERE is_active = 1 ORDER BY created_at DESC")
    List<Map<String, Object>> listActiveConfigs();
    
    /**
     * 根据 ID 查询数据源配置
     */
    @Select("SELECT * FROM datasource_config WHERE id = #{id}")
    Map<String, Object> getConfigById(@Param("id") Long id);
    
    /**
     * 根据 ID 更新数据源配置
     */
    @Update("UPDATE datasource_config SET name = #{name}, db_type = #{dbType}, host = #{host}, port = #{port}, " +
            "database_name = #{databaseName}, username = #{username}, password_encrypted = #{passwordEncrypted}, " +
            "description = #{description}, is_active = #{isActive}, updated_at = NOW() WHERE id = #{id}")
    void updateConfig(@Param("id") Long id,
                     @Param("name") String name,
                     @Param("dbType") String dbType,
                     @Param("host") String host,
                     @Param("port") Integer port,
                     @Param("databaseName") String databaseName,
                     @Param("username") String username,
                     @Param("passwordEncrypted") String passwordEncrypted,
                     @Param("description") String description,
                     @Param("isActive") Integer isActive);
}
