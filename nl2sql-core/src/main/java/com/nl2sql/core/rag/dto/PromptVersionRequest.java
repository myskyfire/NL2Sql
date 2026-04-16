package com.nl2sql.core.rag.dto;

import lombok.Data;

/**
 * Prompt版本创建/更新请求
 */
@Data
public class PromptVersionRequest {
    
    /**
     * 版本ID（更新时必填）
     */
    private Long id;
    
    /**
     * 版本名称
     */
    private String versionName;
    
    /**
     * Prompt类型: sql_generation, clarification, etc.
     */
    private String promptType;
    
    /**
     * Prompt内容
     */
    private String promptContent;
    
    /**
     * 版本描述
     */
    private String description;
    
    /**
     * 是否设为默认版本
     */
    private Boolean isDefault;
}
