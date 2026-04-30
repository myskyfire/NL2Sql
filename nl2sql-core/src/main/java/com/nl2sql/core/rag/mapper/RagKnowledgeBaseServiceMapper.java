package com.nl2sql.core.rag.mapper;

import com.nl2sql.core.rag.RagKnowledgeBaseService;
import com.nl2sql.core.rag.dto.RagQAPairDTO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * RAG 知识库服务 Mapper
 */
@Mapper
public interface RagKnowledgeBaseServiceMapper {
    
    /**
     * 插入问答对（返回自增 ID）
     */
    void insertQAPair(RagQAPairDTO qaPair);
    
    /**
     * 删除指定ID
     */
    int deleteById(@Param("id") Long id);
    
    /**
     * MySQL全文检索（降级方案）
     */
    List<RagKnowledgeBaseService.KnowledgeItem> searchByFullText(
        @Param("question") String question,
        @Param("threshold") float threshold,
        @Param("maxResults") int maxResults
    );
    
    /**
     * 获取高质量样本
     */
    List<RagKnowledgeBaseService.KnowledgeItem> getHighQualitySamples(
        @Param("category") String category,
        @Param("limit") int limit
    );
    
    /**
     * 按分类统计
     */
    List<Map<String, Object>> getCategoryStatistics();
    
    /**
     * 最近添加的条目
     */
    List<RagKnowledgeBaseService.KnowledgeItem> getRecentAdded(@Param("limit") int limit);
    
    /**
     * 低质量条目
     */
    List<RagKnowledgeBaseService.KnowledgeItem> getLowQualityItems(@Param("threshold") float threshold);
    
    /**
     * 总数统计
     */
    Long getTotalCount();
    
    /**
     * 增加使用次数
     */
    int incrementUsageCount(@Param("id") Long id);
    
    /**
     * 更新质量分数
     */
    int updateQualityScore(@Param("id") Long id, @Param("score") float score);
    
    /**
     * 批量更新分类
     */
    int batchUpdateCategory(@Param("ids") List<Long> ids, @Param("category") String category);
    
    /**
     * 批量删除
     */
    int batchDelete(@Param("ids") List<Long> ids);
}
