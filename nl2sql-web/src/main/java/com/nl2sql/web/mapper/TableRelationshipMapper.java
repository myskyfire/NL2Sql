package com.nl2sql.web.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import java.util.List;
import java.util.Map;

/**
 * TableRelationship Mapper - 表关系相关查询
 */
@Mapper
public interface TableRelationshipMapper {
    
    /**
     * 查询指定数据源的表
     */
    @Select("SELECT DISTINCT table_name, table_comment FROM column_metadata WHERE datasource_id = #{datasourceId} ORDER BY table_name")
    List<Map<String, Object>> getTablesByDatasource(@Param("datasourceId") Long datasourceId);
    
    /**
     * 查询指定表的字段
     */
    @Select("SELECT column_name, data_type, column_comment, is_primary_key FROM column_metadata WHERE datasource_id = #{datasourceId} AND table_name = #{tableName} ORDER BY ordinal_position")
    List<Map<String, Object>> getColumnsByTable(@Param("datasourceId") Long datasourceId, @Param("tableName") String tableName);
    
    /**
     * 查询指定数据源的所有关联关系
     */
    @Select("SELECT * FROM table_relationships WHERE datasource_id = #{datasourceId} ORDER BY source_table, target_table")
    List<Map<String, Object>> getRelationshipsByDatasource(@Param("datasourceId") Long datasourceId);
    
    /**
     * 检查关联关系是否存在
     */
    @Select("SELECT COUNT(*) FROM table_relationships WHERE datasource_id = #{datasourceId} AND source_table = #{sourceTable} AND source_column = #{sourceColumn} AND target_table = #{targetTable} AND target_column = #{targetColumn}")
    Integer countRelationship(@Param("datasourceId") Long datasourceId, @Param("sourceTable") String sourceTable, @Param("sourceColumn") String sourceColumn, @Param("targetTable") String targetTable, @Param("targetColumn") String targetColumn);
    
    /**
     * 保存关联关系
     */
    @Insert("INSERT INTO table_relationships (datasource_id, source_table, source_column, target_table, target_column, relationship_type, confidence, description, is_active) VALUES (#{datasourceId}, #{sourceTable}, #{sourceColumn}, #{targetTable}, #{targetColumn}, #{relationshipType}, #{confidence}, #{description}, 1)")
    void saveRelationship(@Param("datasourceId") Long datasourceId, @Param("sourceTable") String sourceTable, @Param("sourceColumn") String sourceColumn, @Param("targetTable") String targetTable, @Param("targetColumn") String targetColumn, @Param("relationshipType") String relationshipType, @Param("confidence") Integer confidence, @Param("description") String description);
    
    /**
     * 更新关联关系
     */
    @Update("UPDATE table_relationships SET relationship_type = #{relationshipType}, description = #{description}, is_active = #{isActive} WHERE id = #{id}")
    void updateRelationship(@Param("id") Long id, @Param("relationshipType") String relationshipType, @Param("description") String description, @Param("isActive") Integer isActive);
    
    /**
     * 删除关联关系
     */
    @Delete("DELETE FROM table_relationships WHERE id = #{id}")
    void deleteRelationship(@Param("id") Long id);
    
    /**
     * 查询表注释
     */
    @Select("SELECT table_comment FROM table_metadata WHERE datasource_id = #{datasourceId} AND table_name = #{tableName}")
    String getTableComment(@Param("datasourceId") Long datasourceId, @Param("tableName") String tableName);
}
