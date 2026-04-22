package com.nl2sql.core.rag.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * RAG知识库Mapper(MySQL降级)
 */
@Mapper
public interface RagKnowledgeMapper {
    
    /**
     * 插入RAG知识
     */
    void insertRagKnowledge(@Param("question") String question,
                            @Param("answer") String answer,
                            @Param("sqlExample") String sqlExample,
                            @Param("category") String category);
    
    /**
     * 查询相似知识(全文搜索)
     */
    java.util.List<java.util.Map<String, Object>> findSimilarKnowledge(
        @Param("question") String question,
        @Param("limit") int limit
    );
}
