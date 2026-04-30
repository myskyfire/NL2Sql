package com.nl2sql.metadata.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.List;
import java.util.Map;

/**
 * TermAutoGenerate Mapper - 词条自动生成相关
 */
@Mapper
public interface TermAutoGenerateMapper {
    
    @Select("SELECT * FROM term_dictionary WHERE industry = #{industry}")
    List<Map<String, Object>> getTermsByIndustry(@Param("industry") String industry);
    
    @Select("SELECT * FROM term_synonyms WHERE term_id = #{termId}")
    List<Map<String, Object>> getTermSynonyms(@Param("termId") Long termId);
    
    /**
     * 查询所有表结构
     */
    @Select("SELECT table_name, table_comment FROM information_schema.tables WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE'")
    List<Map<String, Object>> queryAllTables();
    
    /**
     * 查询表的字段信息
     */
    @Select("SELECT column_name, data_type, column_comment FROM information_schema.columns WHERE table_name = #{tableName} ORDER BY ordinal_position")
    List<Map<String, Object>> queryTableColumns(@Param("tableName") String tableName);
    
    /**
     * 插入术语建议
     */
    @Insert("INSERT INTO term_suggestion (term, term_type, industry_code, usage_count, description, is_active) VALUES (#{term}, #{termType}, #{industryCode}, #{usageCount}, #{description}, 1) ON DUPLICATE KEY UPDATE usage_count = usage_count + VALUES(usage_count)")
    void insertTermSuggestion(@Param("term") String term, @Param("termType") String termType, @Param("industryCode") String industryCode, @Param("usageCount") Integer usageCount, @Param("description") String description);
}
