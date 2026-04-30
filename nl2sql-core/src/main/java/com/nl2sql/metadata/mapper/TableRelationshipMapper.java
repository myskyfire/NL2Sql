package com.nl2sql.metadata.mapper;

import com.nl2sql.metadata.entity.TableRelationship;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * TableRelationship Mapper
 */
@Mapper
public interface TableRelationshipMapper {
    
    // ==================== 查询方法 ====================
    
    /**
     * 查询数据源的所有表名和注释
     */
    List<Map<String, Object>> selectTableMetadata(@Param("datasourceId") Long datasourceId);
    
    /**
     * 批量查询字段信息
     */
    List<Map<String, Object>> selectColumnsBatch(@Param("datasourceId") Long datasourceId, 
                                                  @Param("tableNames") List<String> tableNames);
    
    /**
     * 查询数据源的数据库名称
     */
    String selectDatabaseName(@Param("datasourceId") Long datasourceId);
    
    /**
     * 查询真实外键约束
     */
    List<Map<String, Object>> selectRealForeignKeys(@Param("schemaName") String schemaName);
    
    /**
     * 根据ID查询关联关系
     */
    TableRelationship selectById(@Param("id") Long id);
    
    /**
     * 查询数据源的所有关联关系
     */
    List<TableRelationship> selectByDatasourceId(@Param("datasourceId") Long datasourceId);
    
    /**
     * 查询表的关联关系（作为源表）
     */
    List<TableRelationship> selectBySourceTable(@Param("datasourceId") Long datasourceId,
                                                @Param("tableName") String tableName);
    
    /**
     * 查询表的关联关系（作为目标表）
     */
    List<TableRelationship> selectByTargetTable(@Param("datasourceId") Long datasourceId,
                                                @Param("tableName") String tableName);
    
    // ==================== 插入方法 ====================
    
    /**
     * 插入关联关系
     */
    int insert(TableRelationship relationship);
    
    /**
     * 批量插入关联关系
     */
    int batchInsert(@Param("relationships") List<TableRelationship> relationships);
    
    // ==================== 更新方法 ====================
    
    /**
     * 更新关联关系
     */
    int update(TableRelationship relationship);
    
    /**
     * 更新激活状态
     */
    int updateActiveStatus(@Param("id") Long id, @Param("active") boolean active);
    
    // ==================== 删除方法 ====================
    
    /**
     * 根据ID删除
     */
    int deleteById(@Param("id") Long id);
    
    /**
     * 删除数据源的所有关联关系
     */
    int deleteByDatasourceId(@Param("datasourceId") Long datasourceId);
}
