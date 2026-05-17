package com.nl2sql.core.validation;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 参数验证器
 * 
 * 提供统一的参数校验功能，支持链式调用
 */
@Slf4j
@Component
public class ParameterValidator {
    
    private List<ValidationError> errors;
    
    public ParameterValidator() {
        this.errors = new ArrayList<>();
    }
    
    /**
     * 创建新的验证器实例
     */
    public static ParameterValidator create() {
        return new ParameterValidator();
    }
    
    // ==================== 必填字段校验 ====================
    
    /**
     * 校验字符串字段不为空
     */
    public ParameterValidator required(String field, String value) {
        if (!StringUtils.hasText(value)) {
            errors.add(ValidationError.requiredFieldEmpty(field));
        }
        return this;
    }
    
    /**
     * 校验对象字段不为null（通用版本，支持任意类型）
     */
    public ParameterValidator required(String field, Object value) {
        if (value == null) {
            errors.add(ValidationError.requiredFieldEmpty(field));
        }
        return this;
    }
    
    /**
     * 校验Long字段不为null且大于0
     */
    public ParameterValidator requiredPositive(String field, Long value) {
        if (value == null || value <= 0) {
            errors.add(ValidationError.builder()
                .field(field)
                .errorType(ValidationErrorType.REQUIRED_FIELD_EMPTY)
                .message(String.format("字段'%s'必须为正整数", field))
                .build());
        }
        return this;
    }
    
    // ==================== 长度校验 ====================
    
    /**
     * 校验字符串长度不超过最大值
     */
    public ParameterValidator maxLength(String field, String value, int maxLength) {
        if (StringUtils.hasText(value) && value.length() > maxLength) {
            errors.add(ValidationError.fieldLengthExceeded(field, value.length(), maxLength));
        }
        return this;
    }
    
    /**
     * 校验字符串长度在范围内
     */
    public ParameterValidator lengthRange(String field, String value, int minLength, int maxLength) {
        if (StringUtils.hasText(value)) {
            int length = value.length();
            if (length < minLength || length > maxLength) {
                errors.add(ValidationError.builder()
                    .field(field)
                    .errorType(ValidationErrorType.FIELD_LENGTH_EXCEEDED)
                    .message(String.format("字段'%s'长度应在%d-%d之间，当前为%d", field, minLength, maxLength, length))
                    .actualValue(length)
                    .expectedValue(String.format("%d-%d", minLength, maxLength))
                    .build());
            }
        }
        return this;
    }
    
    // ==================== 格式校验 ====================
    
    /**
     * 校验邮箱格式
     */
    public ParameterValidator email(String field, String value) {
        if (StringUtils.hasText(value)) {
            Pattern pattern = Pattern.compile("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+$");
            if (!pattern.matcher(value).matches()) {
                errors.add(ValidationError.invalidFieldFormat(field, "email"));
            }
        }
        return this;
    }
    
    /**
     * 校验手机号格式（中国大陆）
     */
    public ParameterValidator phone(String field, String value) {
        if (StringUtils.hasText(value)) {
            Pattern pattern = Pattern.compile("^1[3-9]\\d{9}$");
            if (!pattern.matcher(value).matches()) {
                errors.add(ValidationError.invalidFieldFormat(field, "phone"));
            }
        }
        return this;
    }
    
    /**
     * 校验正则表达式
     */
    public ParameterValidator matchesPattern(String field, String value, Pattern pattern, String formatDesc) {
        if (StringUtils.hasText(value) && !pattern.matcher(value).matches()) {
            errors.add(ValidationError.invalidFieldFormat(field, formatDesc));
        }
        return this;
    }
    
    // ==================== 范围校验 ====================
    
    /**
     * 校验数值在范围内
     */
    public ParameterValidator inRange(String field, Integer value, int min, int max) {
        if (value != null && (value < min || value > max)) {
            errors.add(ValidationError.builder()
                .field(field)
                .errorType(ValidationErrorType.FIELD_VALUE_OUT_OF_RANGE)
                .message(String.format("字段'%s'的值%d不在允许范围[%d, %d]内", field, value, min, max))
                .actualValue(value)
                .expectedValue(String.format("[%d, %d]", min, max))
                .build());
        }
        return this;
    }
    
    /**
     * 校验值在枚举范围内
     */
    public ParameterValidator inEnum(String field, String value, Enum<?>[] enumValues) {
        if (StringUtils.hasText(value)) {
            boolean valid = false;
            for (Enum<?> e : enumValues) {
                if (e.name().equals(value)) {
                    valid = true;
                    break;
                }
            }
            if (!valid) {
                errors.add(ValidationError.builder()
                    .field(field)
                    .errorType(ValidationErrorType.FIELD_VALUE_OUT_OF_RANGE)
                    .message(String.format("字段'%s'的值'%s'不在允许的枚举值范围内", field, value))
                    .actualValue(value)
                    .expectedValue(enumValues)
                    .build());
            }
        }
        return this;
    }
    
    // ==================== 自定义校验 ====================
    
    /**
     * 自定义校验逻辑
     */
    public ParameterValidator custom(String field, boolean condition, String message) {
        if (!condition) {
            errors.add(ValidationError.builder()
                .field(field)
                .errorType(ValidationErrorType.INVALID_FIELD_FORMAT)
                .message(message)
                .build());
        }
        return this;
    }
    
    // ==================== 结果检查 ====================
    
    /**
     * 是否有验证错误
     */
    public boolean hasErrors() {
        return !errors.isEmpty();
    }
    
    /**
     * 获取所有验证错误
     */
    public List<ValidationError> getErrors() {
        return new ArrayList<>(errors);
    }
    
    /**
     * 如果有错误则抛出异常
     */
    public void throwIfHasErrors() {
        if (hasErrors()) {
            String errorMsg = errors.stream()
                .map(e -> String.format("[%s] %s", e.getField(), e.getMessage()))
                .reduce((a, b) -> a + "; " + b)
                .orElse("参数验证失败");
            
            log.warn("[ParameterValidator] 参数验证失败: {}", errorMsg);
            throw new IllegalArgumentException(errorMsg);
        }
    }
    
    /**
     * 清空错误列表（用于复用验证器）
     */
    public void clear() {
        errors.clear();
    }
}
