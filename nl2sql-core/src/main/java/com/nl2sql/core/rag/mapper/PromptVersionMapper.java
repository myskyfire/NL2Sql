package com.nl2sql.core.rag.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * Prompt版本Mapper
 */
@Mapper
public interface PromptVersionMapper {
    
    /**
     * 插入Prompt版本
     */
    Long insertPromptVersion(@Param("versionName") String versionName,
                             @Param("promptType") String promptType,
                             @Param("promptContent") String promptContent,
                             @Param("description") String description,
                             @Param("createdBy") String createdBy);
    
    /**
     * 更新Prompt内容
     */
    void updatePromptContent(@Param("id") Long id,
                             @Param("promptContent") String promptContent,
                             @Param("description") String description);
    
    /**
     * 重置默认版本
     */
    void resetDefaultVersion(@Param("promptType") String promptType);
    
    /**
     * 激活版本
     */
    void activateVersion(@Param("id") Long id);
    
    /**
     * 查询默认Prompt
     */
    String findDefaultPrompt(@Param("promptType") String promptType);
    
    /**
     * 根据ID查询版本
     */
    Map<String, Object> findVersionById(@Param("id") Long id);
    
    /**
     * 查询所有版本
     */
    List<Map<String, Object>> findAllVersions();
    
    /**
     * 插入AB测试记录
     */
    Long insertAbTest(@Param("testName") String testName,
                      @Param("promptType") String promptType,
                      @Param("versionAId") Long versionAId,
                      @Param("versionBId") Long versionBId,
                      @Param("createdBy") String createdBy);
    
    /**
     * 完成AB测试
     */
    void completeAbTest(@Param("id") Long id, @Param("winnerVersionId") Long winnerVersionId);
}
