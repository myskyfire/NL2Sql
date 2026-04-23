package com.nl2sql.core.validation;

import lombok.Getter;

/**
 * 验证错误类型
 */
@Getter
public enum ValidationErrorType {
    
    /**
     * 必填字段为空
     */
    REQUIRED_FIELD_EMPTY("必填字段不能为空"),
    
    /**
     * 字段长度超出限制
     */
    FIELD_LENGTH_EXCEEDED("字段长度超出限制"),
    
    /**
     * 字段格式不正确
     */
    INVALID_FIELD_FORMAT("字段格式不正确"),
    
    /**
     * 字段值不在允许范围内
     */
    FIELD_VALUE_OUT_OF_RANGE("字段值不在允许范围内"),
    
    /**
     * 字段值重复
     */
    DUPLICATE_FIELD_VALUE("字段值已存在"),
    
    /**
     * 依赖字段缺失
     */
    DEPENDENT_FIELD_MISSING("缺少依赖字段");
    
    private final String defaultMessage;
    
    ValidationErrorType(String defaultMessage) {
        this.defaultMessage = defaultMessage;
    }
}
