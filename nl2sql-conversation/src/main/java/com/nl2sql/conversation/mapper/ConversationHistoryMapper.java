package com.nl2sql.conversation.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import java.util.List;
import java.util.Map;

/**
 * ConversationHistory Mapper - 会话历史相关
 */
@Mapper
public interface ConversationHistoryMapper {
    
    @Select("SELECT * FROM conversation_history WHERE conversation_id = #{conversationId}")
    List<Map<String, Object>> getConversationHistory(@Param("conversationId") String conversationId);
    
    @Insert("INSERT INTO conversation_history (conversation_id, user_id, query, response) VALUES (#{conversationId}, #{userId}, #{query}, #{response})")
    void saveConversation(@Param("conversationId") String conversationId, @Param("userId") Long userId,
                         @Param("query") String query, @Param("response") String response);
    
    // ==================== 新增方法 ====================
    
    /**
     * 查询会话历史消息（按时间排序）
     */
    List<Map<String, Object>> selectMessagesBySessionId(@Param("sessionId") String sessionId,
                                                       @Param("limit") Integer limit);
    
    /**
     * 插入单条消息
     */
    int insertMessage(@Param("sessionId") String sessionId,
                     @Param("userId") Long userId,
                     @Param("role") String role,
                     @Param("content") String content,
                     @Param("name") String name,
                     @Param("toolCallId") String toolCallId);
    
    /**
     * 清除会话历史
     */
    int clearHistory(@Param("sessionId") String sessionId);
}
