package com.nl2sql.web.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import java.util.List;
import java.util.Map;

/**
 * AgentChat Mapper - Agent 聊天相关
 */
@Mapper
public interface AgentChatMapper {
    
    @Select("SELECT * FROM agent_chat_history WHERE chat_id = #{chatId}")
    List<Map<String, Object>> getChatHistory(@Param("chatId") String chatId);
    
    @Insert("INSERT INTO agent_chat_history (chat_id, user_id, message, response) VALUES (#{chatId}, #{userId}, #{message}, #{response})")
    void saveChatMessage(@Param("chatId") String chatId, @Param("userId") Long userId,
                        @Param("message") String message, @Param("response") String response);
}
