package com.nl2sql.metadata.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
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
    
    /**
     * ✅ 新增：查询缺少注释的字段
     */
    @Select("SELECT column_name, data_type, column_size, is_nullable, column_default, column_comment, is_primary_key " +
            "FROM column_metadata WHERE datasource_id = #{datasourceId} AND table_name = #{tableName} " +
            "AND (column_comment IS NULL OR column_comment = '') ORDER BY ordinal_position")
    List<Map<String, Object>> getColumnsWithoutComment(@Param("datasourceId") Long datasourceId, 
                                                        @Param("tableName") String tableName);
    
    /**
     * ✅ 新增：更新表注释
     */
    @Update("UPDATE table_metadata SET table_comment = #{comment} WHERE datasource_id = #{datasourceId} AND table_name = #{tableName}")
    int updateTableComment(@Param("datasourceId") Long datasourceId,
                          @Param("tableName") String tableName,
                          @Param("comment") String comment);
    
    /**
     * ✅ 新增：更新列注释
     */
    @Update("UPDATE column_metadata SET column_comment = #{comment} WHERE datasource_id = #{datasourceId} " +
            "AND table_name = #{tableName} AND column_name = #{columnName}")
    int updateColumnComment(@Param("datasourceId") Long datasourceId,
                           @Param("tableName") String tableName,
                           @Param("columnName") String columnName,
                           @Param("comment") String comment);
    
    /**
     * ✅ 新增：统计表注释覆盖率
     */
    @Select("SELECT COUNT(*) as total, " +
            "SUM(CASE WHEN column_comment IS NOT NULL AND column_comment != '' THEN 1 ELSE 0 END) as commented " +
            "FROM column_metadata WHERE datasource_id = #{datasourceId} AND table_name = #{tableName}")
    Map<String, Object> getColumnCommentStats(@Param("datasourceId") Long datasourceId,
                                              @Param("tableName") String tableName);
    
    /**
     * ✅ 新增：更新列注释（带来源标记）
     */
    @Update("UPDATE column_metadata SET column_comment = #{comment}, comment_source = #{source}, enhanced_at = NOW() " +
            "WHERE datasource_id = #{datasourceId} AND table_name = #{tableName} AND column_name = #{columnName}")
    int updateColumnCommentWithSource(@Param("datasourceId") Long datasourceId,
                                     @Param("tableName") String tableName,
                                     @Param("columnName") String columnName,
                                     @Param("comment") String comment,
                                     @Param("source") String source);
    
    /**
     * ✅ 新增：批量插入表元数据
     */
    @org.apache.ibatis.annotations.Insert("INSERT IGNORE INTO table_metadata (datasource_id, table_name, table_comment, table_type, schema_name) " +
            "VALUES (#{datasourceId}, #{tableName}, #{tableComment}, #{tableType}, #{schemaName})")
    int insertTableMetadata(@Param("datasourceId") Long datasourceId,
                           @Param("tableName") String tableName,
                           @Param("tableComment") String tableComment,
                           @Param("tableType") String tableType,
                           @Param("schemaName") String schemaName);
    
    /**
     * ✅ 新增：批量插入列元数据
     */
    @org.apache.ibatis.annotations.Insert("INSERT IGNORE INTO column_metadata (datasource_id, table_name, table_comment, column_name, data_type, column_size, decimal_digits, is_nullable, column_default, column_comment, is_primary_key, ordinal_position, character_set_name, comment_source) " +
            "VALUES (#{datasourceId}, #{tableName}, #{tableComment}, #{columnName}, #{dataType}, #{columnSize}, #{decimalDigits}, #{isNullable}, #{columnDefault}, #{columnComment}, #{isPrimaryKey}, #{ordinalPosition}, #{characterSetName}, #{commentSource})")
    int insertColumnMetadata(@Param("datasourceId") Long datasourceId,
                            @Param("tableName") String tableName,
                            @Param("tableComment") String tableComment,
                            @Param("columnName") String columnName,
                            @Param("dataType") String dataType,
                            @Param("columnSize") Integer columnSize,
                            @Param("decimalDigits") Integer decimalDigits,
                            @Param("isNullable") Integer isNullable,
                            @Param("columnDefault") String columnDefault,
                            @Param("columnComment") String columnComment,
                            @Param("isPrimaryKey") Integer isPrimaryKey,
                            @Param("ordinalPosition") Integer ordinalPosition,
                            @Param("characterSetName") String characterSetName,
                            @Param("commentSource") String commentSource);
    
    /**
     * ✅ 新增：清空数据源元数据
     */
    @Update("DELETE FROM column_metadata WHERE datasource_id = #{datasourceId}")
    int clearColumnMetadata(@Param("datasourceId") Long datasourceId);
    
    @Update("DELETE FROM table_metadata WHERE datasource_id = #{datasourceId}")
    int clearTableMetadata(@Param("datasourceId") Long datasourceId);
    
    /**
     * ✅ 新增：插入同步日志
     */
    @org.apache.ibatis.annotations.Insert("INSERT INTO metadata_sync_log (datasource_id, sync_status, table_count, column_count, foreign_key_count, error_message, started_at, completed_at, duration_seconds, created_by) " +
            "VALUES (#{datasourceId}, #{syncStatus}, #{tableCount}, #{columnCount}, #{foreignKeyCount}, #{errorMessage}, #{startedAt}, #{completedAt}, #{durationSeconds}, #{createdBy})")
    int insertSyncLog(@Param("datasourceId") Long datasourceId,
                     @Param("syncStatus") String syncStatus,
                     @Param("tableCount") Integer tableCount,
                     @Param("columnCount") Integer columnCount,
                     @Param("foreignKeyCount") Integer foreignKeyCount,
                     @Param("errorMessage") String errorMessage,
                     @Param("startedAt") java.time.LocalDateTime startedAt,
                     @Param("completedAt") java.time.LocalDateTime completedAt,
                     @Param("durationSeconds") Integer durationSeconds,
                     @Param("createdBy") Long createdBy);
}
