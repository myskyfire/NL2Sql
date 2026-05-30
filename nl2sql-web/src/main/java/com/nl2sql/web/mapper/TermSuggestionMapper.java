package com.nl2sql.web.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.List;
import java.util.Map;

/**
 * TermSuggestion Mapper - 词条推荐相关查询
 */
@Mapper
public interface TermSuggestionMapper {
    
    /**
     * 查询词条推荐
     */
    @Select("SELECT term, term_type, usage_count FROM term_suggestion WHERE is_active = 1 AND term LIKE CONCAT('%', #{prefix}, '%') AND industry_code = #{industryCode} ORDER BY usage_count DESC LIMIT #{limit}")
    List<Map<String, Object>> suggestTermsByIndustry(@Param("prefix") String prefix, @Param("industryCode") String industryCode, @Param("limit") int limit);
    
    /**
     * 查询词条推荐（不区分行业）
     */
    @Select("SELECT term, term_type, usage_count FROM term_suggestion WHERE is_active = 1 AND term LIKE CONCAT('%', #{prefix}, '%') ORDER BY usage_count DESC LIMIT #{limit}")
    List<Map<String, Object>> suggestTerms(@Param("prefix") String prefix, @Param("limit") int limit);
    
    /**
     * 查询数据源行业映射
     */
    @Select("SELECT industry_code FROM datasource_industry_mapping WHERE datasource_id = #{datasourceId} ORDER BY priority LIMIT 1")
    String queryIndustryByDatasource(@Param("datasourceId") Long datasourceId);
    
    /**
     * 查询数据源业务分类
     */
    @Select("SELECT business_category FROM datasource_config WHERE id = #{datasourceId}")
    String getBusinessCategory(@Param("datasourceId") Long datasourceId);
    
    /**
     * 查询热词
     */
    @Select("SELECT term_name, usage_count FROM hot_word_library ORDER BY usage_count DESC LIMIT #{limit}")
    List<Map<String, Object>> getHotWords(@Param("limit") int limit);
    
    /**
     * 查询术语动作
     */
    @Select("SELECT action, action_label, sql_template_id, priority FROM term_action_mapping WHERE term = #{term} AND is_active = 1 ORDER BY priority DESC")
    List<Map<String, Object>> getTermActions(@Param("term") String term);
}
