package com.nl2sql.metadata.mapper;

import com.nl2sql.metadata.entity.ColumnMetadata;
import com.nl2sql.metadata.entity.TableMetadata;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.List;
import java.util.Map;

/**
 * MetadataQuery Mapper - 元数据查询相关
 */
@Mapper
public interface MetadataQueryMapper {
    
    @Select("SELECT * FROM table_metadata WHERE table_name = #{tableName}")
    Map<String, Object> getTableMetadata(@Param("tableName") String tableName);
    
    @Select("SELECT * FROM column_metadata WHERE table_name = #{tableName}")
    List<Map<String, Object>> getColumnMetadata(@Param("tableName") String tableName);
    
    @Select("SELECT * FROM table_metadata")
    List<Map<String, Object>> getAllTables();
    
    /**
     * 查询指定数据源的所有表
     */
    @Select("SELECT * FROM table_metadata WHERE datasource_id = #{datasourceId} ORDER BY table_name")
    List<Map<String, Object>> getTablesByDatasource(@Param("datasourceId") Long datasourceId);
    
    /**
     * 查询指定数据源和表名的表信息
     */
    @Select("SELECT * FROM table_metadata WHERE datasource_id = #{datasourceId} AND table_name = #{tableName}")
    List<Map<String, Object>> getTableByDatasourceAndName(@Param("datasourceId") Long datasourceId, @Param("tableName") String tableName);
    
    /**
     * 查询指定数据源和表名的字段信息
     */
    @Select("SELECT * FROM column_metadata WHERE datasource_id = #{datasourceId} AND table_name = #{tableName} ORDER BY ordinal_position")
    List<Map<String, Object>> getColumnsByDatasourceAndTable(@Param("datasourceId") Long datasourceId, @Param("tableName") String tableName);
    
    /**
     * 搜索表名
     */
    @Select("SELECT * FROM table_metadata WHERE datasource_id = #{datasourceId} AND table_name LIKE CONCAT('%', #{keyword}, '%') ORDER BY table_name LIMIT 50")
    List<Map<String, Object>> searchTables(@Param("datasourceId") Long datasourceId, @Param("keyword") String keyword);
    
    /**
     * 统计本地修改的表数量
     */
    @Select("SELECT COUNT(*) FROM table_metadata WHERE datasource_id = #{datasourceId} AND is_local_modified = 1")
    Integer countLocalModifiedTables(@Param("datasourceId") Long datasourceId);
    
    /**
     * 统计本地修改的字段数量
     */
    @Select("SELECT COUNT(*) FROM column_metadata WHERE datasource_id = #{datasourceId} AND is_local_modified = 1")
    Integer countLocalModifiedColumns(@Param("datasourceId") Long datasourceId);
    
    // ==================== 新增方法（返回实体对象）====================
    
    /**
     * 查询指定数据源的所有表（返回实体）
     */
    List<TableMetadata> selectTablesByDatasource(@Param("datasourceId") Long datasourceId);
    
    /**
     * 查询指定数据源和表名的表信息（返回实体）
     */
    TableMetadata selectTableByDatasourceAndName(@Param("datasourceId") Long datasourceId,
                                                 @Param("tableName") String tableName);
    
    /**
     * 查询指定数据源和表名的字段信息（返回实体）
     */
    List<ColumnMetadata> selectColumnsByDatasourceAndTable(@Param("datasourceId") Long datasourceId,
                                                           @Param("tableName") String tableName);
    
    /**
     * 搜索表名（返回实体）
     */
    List<TableMetadata> searchTablesAsEntity(@Param("datasourceId") Long datasourceId,
                                             @Param("keyword") String keyword);
    
    /**
     * 查询表注释
     */
    String selectTableComment(@Param("datasourceId") Long datasourceId,
                             @Param("tableName") String tableName);
    
    /**
     * 查询表的字段列表（简化版）
     */
    List<Map<String, Object>> selectColumnList(@Param("datasourceId") Long datasourceId,
                                               @Param("tableName") String tableName);
    
    /**
     * 查询表的字段列表（完整版，含is_nullable/column_key）
     */
    List<Map<String, Object>> selectColumnListFull(@Param("datasourceId") Long datasourceId,
                                                   @Param("tableName") String tableName);
    
    /**
     * 查询表的字段名称和注释
     */
    List<Map<String, Object>> selectColumnNameAndComment(@Param("datasourceId") Long datasourceId,
                                                         @Param("tableName") String tableName);
    
    /**
     * 插入NL2SQL查询日志
     */
    int insertQueryLog(@Param("sessionId") String sessionId,
                      @Param("userId") Long userId,
                      @Param("question") String question,
                      @Param("normalizedQuery") String normalizedQuery,
                      @Param("hasPersonEntity") Integer hasPersonEntity,
                      @Param("hasLocationEntity") Integer hasLocationEntity,
                      @Param("normalizationMethod") String normalizationMethod,
                      @Param("cacheLevel") String cacheLevel,
                      @Param("cacheHit") Integer cacheHit,
                      @Param("ragExamplesCount") Integer ragExamplesCount,
                      @Param("industryTermsMatched") String industryTermsMatched,
                      @Param("generatedSql") String generatedSql,
                      @Param("executedSql") String executedSql,
                      @Param("executionSuccess") Integer executionSuccess,
                      @Param("rowCount") Long rowCount,
                      @Param("executionTimeMs") Long executionTimeMs,
                      @Param("datasourceId") Long datasourceId,
                      @Param("selectedTables") String selectedTables);
}
