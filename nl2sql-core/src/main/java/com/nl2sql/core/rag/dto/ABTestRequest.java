package com.nl2sql.core.rag.dto;

import lombok.Data;

/**
 * A/B测试创建请求
 */
@Data
public class ABTestRequest {
    
    /**
     * 测试名称
     */
    private String testName;
    
    /**
     * Prompt类型
     */
    private String promptType;
    
    /**
     * 版本A ID
     */
    private Long versionAId;
    
    /**
     * 版本B ID
     */
    private Long versionBId;
    
    /**
     * 流量分配比例（版本A占比%），默认50
     */
    private Double trafficSplit;
    
    /**
     * 最小样本数，默认100
     */
    private Integer minSamples;
}
