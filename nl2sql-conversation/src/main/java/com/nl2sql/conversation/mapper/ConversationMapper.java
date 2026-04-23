package com.nl2sql.conversation.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 对话历史Mapper
 */
@Mapper
public interface ConversationMapper {
    
    /**
     * 插入对话消息
     */
    void insertMessage(@Param("sessionId") String sessionId,
                      @Param("userId") Long userId,
                      @Param("role") String role,
                      @Param("content") String content,
                      @Param("name") String name,
                      @Param("toolCallId") String toolCallId);
    
    /**
     * 清除会话历史
     */
    void clearHistory(@Param("sessionId") String sessionId);
}
