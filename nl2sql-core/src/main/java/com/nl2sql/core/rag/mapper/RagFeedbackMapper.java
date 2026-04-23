package com.nl2sql.core.rag.mapper;

import com.nl2sql.core.rag.LowRatingExampleService.LowRatingExample;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * RAG反馈Mapper
 */
@Mapper
public interface RagFeedbackMapper {
    
    /**
     * 查找相似的低分示例
     */
    List<LowRatingExample> findSimilarLowRatingExamples(
        @Param("question") String question,
        @Param("limit") int limit
    );
    
    /**
     * 检查是否有相同问题的低分反馈
     */
    LowRatingExample checkExactMatchLowRating(@Param("question") String question);
    
    /**
     * 更新知识库质量评分（降级相似示例）
     */
    int degradeKnowledgeQuality(
        @Param("degradation") float degradation,
        @Param("tableNamePattern") String tableNamePattern,
        @Param("keywordsPattern") String keywordsPattern
    );
    
    /**
     * 标记负面示例（追加错误分类）
     */
    int markNegativeExample(
        @Param("categoriesStr") String categoriesStr,
        @Param("feedbackId") Long feedbackId
    );
}
