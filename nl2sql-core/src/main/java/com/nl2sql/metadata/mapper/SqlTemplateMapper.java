package com.nl2sql.metadata.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.List;
import java.util.Map;

/**
 * SqlTemplate Mapper - SQL 模板相关查询
 */
@Mapper
public interface SqlTemplateMapper {
    
    @Select("SELECT * FROM sql_templates WHERE id = #{id}")
    Map<String, Object> getTemplateById(@Param("id") Long id);
    
    @Select("SELECT * FROM sql_templates WHERE category = #{category}")
    List<Map<String, Object>> getTemplatesByCategory(@Param("category") String category);
    
    @Select("SELECT template_content FROM sql_templates WHERE id = #{id}")
    String getTemplateContent(@Param("id") Long id);
    
    /**
     * 查询 SQL 模板内容
     */
    @Select("SELECT template_content FROM sql_template WHERE template_id = #{templateId} AND is_active = 1")
    String queryTemplate(@Param("templateId") String templateId);
}
