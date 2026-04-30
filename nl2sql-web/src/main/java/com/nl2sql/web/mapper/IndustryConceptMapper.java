package com.nl2sql.web.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;
import java.util.Map;

/**
 * IndustryConcept Mapper - 行业概念管理相关
 */
@Mapper
public interface IndustryConceptMapper {
    
    /**
     * 获取所有行业模板
     */
    List<Map<String, Object>> getTemplates();
    
    /**
     * 获取指定行业的概念列表
     */
    List<Map<String, Object>> getConcepts(
        @Param("industryCode") String industryCode,
        @Param("type") String type,
        @Param("status") String status
    );
    
    /**
     * 获取单个概念详情
     */
    Map<String, Object> getConcept(@Param("id") Long id);
    
    /**
     * 添加新概念
     */
    int insertConcept(
        @Param("industryCode") String industryCode,
        @Param("conceptType") String conceptType,
        @Param("conceptKey") String conceptKey,
        @Param("aliases") String aliases,
        @Param("description") String description,
        @Param("examples") String examples,
        @Param("relatedTables") String relatedTables,
        @Param("relatedColumns") String relatedColumns,
        @Param("sqlPatterns") String sqlPatterns
    );
    
    /**
     * 更新概念
     */
    int updateConcept(
        @Param("id") Long id,
        @Param("conceptKey") String conceptKey,
        @Param("aliases") String aliases,
        @Param("description") String description,
        @Param("examples") String examples,
        @Param("relatedTables") String relatedTables,
        @Param("relatedColumns") String relatedColumns,
        @Param("sqlPatterns") String sqlPatterns
    );
    
    /**
     * 删除概念
     */
    int deleteConcept(@Param("id") Long id);
    
    /**
     * 更新概念状态
     */
    int updateConceptStatus(@Param("id") Long id, @Param("status") String status);
    
    /**
     * 增加使用次数
     */
    int incrementUsageCount(@Param("id") Long id);
    
    /**
     * 根据行业代码获取 ID
     */
    String getIndustryCodeById(@Param("id") Long id);
    
    /**
     * 根据行业代码获取业务分类
     */
    String getBusinessCategoryByIndustryCode(@Param("industryCode") String industryCode);
    
    /**
     * 插入学习记录
     */
    int insertLearningRecord(
        @Param("industryCode") String industryCode,
        @Param("conceptKey") String conceptKey,
        @Param("question") String question,
        @Param("sqlText") String sqlText
    );
    
    /**
     * 获取学习记录
     */
    List<Map<String, Object>> getLearningRecords(@Param("industryCode") String industryCode);
}
