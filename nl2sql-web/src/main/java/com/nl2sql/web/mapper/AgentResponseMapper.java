package com.nl2sql.web.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import java.util.List;
import java.util.Map;

/**
 * AgentResponse Mapper - Agent 响应相关
 */
@Mapper
public interface AgentResponseMapper {
    
    @Select("SELECT * FROM agent_responses WHERE query_id = #{queryId}")
    List<Map<String, Object>> getResponseByQuery(@Param("queryId") Long queryId);
    
    @Insert("INSERT INTO agent_responses (query_id, response_type, response_content) VALUES (#{queryId}, #{responseType}, #{responseContent})")
    void saveResponse(@Param("queryId") Long queryId, @Param("responseType") String responseType,
                     @Param("responseContent") String responseContent);
}
