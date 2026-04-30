package com.nl2sql.web.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;
import java.util.Map;

/**
 * AgentResponseProcessor Mapper
 */
@Mapper
public interface AgentResponseProcessorMapper {
    
    // 查询对话历史
    List<Map<String, Object>> selectConversationHistory(@Param("sessionId") String sessionId, @Param("limit") int limit);
    
    // 插入对话记录
    int insertConversation(@Param("sessionId") String sessionId, @Param("query") String query, @Param("response") String response);
    
    // 更新对话评分
    int updateConversationRating(@Param("sessionId") String sessionId, @Param("rating") int rating);
    
    /**
     * 根据数据源名称查询 ID
     */
    List<Map<String, Object>> getDatasourceIdByName(@Param("name") String name);
}
