package com.nl2sql.metadata.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.List;
import java.util.Map;

/**
 * IndustryConceptAdmin Mapper - 行业概念管理相关查询
 */
@Mapper
public interface IndustryConceptAdminMapper {
    
    /**
     * 查询所有行业概念
     */
    @Select("SELECT * FROM industry_concept_dictionary")
    List<Map<String, Object>> getAllConcepts();
    
    /**
     * 查询指定行业的概念
     */
    @Select("SELECT * FROM industry_concept_dictionary WHERE industry = #{industry}")
    List<Map<String, Object>> getConceptsByIndustry(@Param("industry") String industry);
    
    /**
     * 查询概念详情
     */
    @Select("SELECT * FROM industry_concept_dictionary WHERE id = #{id}")
    Map<String, Object> getConceptById(@Param("id") Long id);
    
    /**
     * 查询概念同义词
     */
    @Select("SELECT synonym FROM industry_concept_synonyms WHERE concept_id = #{conceptId}")
    List<String> getConceptSynonyms(@Param("conceptId") Long conceptId);
    
    // ==================== 新增方法 ====================
    
    /**
     * 删除概念
     */
    int deleteConceptById(@Param("id") Long id);
    
    /**
     * 批量审核概念
     */
    int batchUpdateStatus(@Param("status") String status, @Param("ids") List<Long> ids);
    
    /**
     * 关联数据源到行业
     */
    int upsertDatasourceIndustry(@Param("datasourceId") Long datasourceId,
                                 @Param("industryCode") String industryCode,
                                 @Param("priority") Integer priority);
    
    /**
     * 查询数据源的行业代码
     */
    String selectIndustryCodeByDatasourceId(@Param("datasourceId") Long datasourceId);
    
    /**
     * 查询数据源的业务类别
     */
    String selectBusinessCategoryByDatasourceId(@Param("datasourceId") Long datasourceId);
    
    /**
     * 插入同义词关系（忽略重复）
     */
    int insertConceptRelationIgnore(@Param("industryCode") String industryCode,
                                    @Param("sourceConcept") String sourceConcept,
                                    @Param("targetConcept") String targetConcept);
    
    /**
     * 插入待审核概念（重复时合并别名）
     */
    int insertPendingConcept(@Param("industryCode") String industryCode,
                            @Param("conceptKey") String conceptKey,
                            @Param("aliases") String aliases,
                            @Param("description") String description,
                            @Param("aliasToAdd") String aliasToAdd);
    
    /**
     * 查询已审核的行业概念（entity/metric）
     */
    List<Map<String, Object>> selectApprovedConceptsByType(@Param("industryCode") String industryCode);
}
