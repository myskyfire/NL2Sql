package com.nl2sql.core.rag.dto;

import lombok.Data;

import java.util.ArrayList;

/**
 * QA对批量导入结果
 */
@Data
public class BatchImportResult {
    
    /**
     * 成功数量
     */
    private int successCount;
    
    /**
     * 失败数量
     */
    private int failedCount;
    
    /**
     * 失败详情
     */
    private java.util.List<String> errors;
    
    public BatchImportResult() {
        this.successCount = 0;
        this.failedCount = 0;
        this.errors = new ArrayList<>();
    }
    
    public void addSuccess() {
        this.successCount++;
    }
    
    public void addFailure(String error) {
        this.failedCount++;
        this.errors.add(error);
    }
}
