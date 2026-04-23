package com.nl2sql.core.validation;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 验证错误详情
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ValidationError {
    
    /**
     * 字段名称
     */
    private String field;
    
    /**
     * 错误类型
     */
    private ValidationErrorType errorType;
    
    /**
     * 错误消息
     */
    private String message;
    
    /**
     * 实际值（调试用）
     */
    private Object actualValue;
    
    /**
     * 期望值或限制（调试用）
     */
    private Object expectedValue;
    
    /**
     * 创建必填字段为空的错误
     */
    public static ValidationError requiredFieldEmpty(String field) {
        return ValidationError.builder()
            .field(field)
            .errorType(ValidationErrorType.REQUIRED_FIELD_EMPTY)
            .message(ValidationErrorType.REQUIRED_FIELD_EMPTY.getDefaultMessage())
            .build();
    }
    
    /**
     * 创建字段长度超出限制的错误
     */
    public static ValidationError fieldLengthExceeded(String field, int actualLength, int maxLength) {
        return ValidationError.builder()
            .field(field)
            .errorType(ValidationErrorType.FIELD_LENGTH_EXCEEDED)
            .message(String.format("字段'%s'长度%d超出最大限制%d", field, actualLength, maxLength))
            .actualValue(actualLength)
            .expectedValue(maxLength)
            .build();
    }
    
    /**
     * 创建字段格式不正确的错误
     */
    public static ValidationError invalidFieldFormat(String field, String format) {
        return ValidationError.builder()
            .field(field)
            .errorType(ValidationErrorType.INVALID_FIELD_FORMAT)
            .message(String.format("字段'%s'格式不正确，应为: %s", field, format))
            .expectedValue(format)
            .build();
    }
}
