package com.nl2sql.metadata.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.List;
import java.util.Map;

/**
 * DdlExtract Mapper - DDL 提取相关
 */
@Mapper
public interface DdlExtractMapper {
    
    @Select("SHOW CREATE TABLE ${tableName}")
    Map<String, Object> getCreateTable(@Param("tableName") String tableName);
    
    @Select("SELECT * FROM information_schema.tables WHERE table_schema = #{schema}")
    List<Map<String, Object>> getTables(@Param("schema") String schema);
    
    /**
     * 查询所有表名
     */
    @Select("SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE' AND table_name NOT LIKE 'sys_%' AND table_name NOT LIKE 'temp_%'")
    List<Map<String, Object>> queryAllTableNames();
}
