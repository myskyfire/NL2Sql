package com.nl2sql.core.error;

import lombok.Getter;

/**
 * 错误类型枚举
 * 
 * 定义系统中所有可能的错误类型，用于统一的错误处理和重试策略
 */
@Getter
public enum ErrorType {
    
    // ==================== 用户输入错误（不可重试）====================
    
    /**
     * 用户输入错误
     */
    USER_INPUT_ERROR(false, "用户输入有误，请检查后重试"),
    
    /**
     * 缺少必填参数
     */
    MISSING_PARAMETER(false, "缺少必填参数"),
    
    /**
     * 参数格式错误
     */
    INVALID_PARAMETER_FORMAT(false, "参数格式不正确"),
    
    // ==================== 权限错误（不可重试）====================
    
    /**
     * 权限不足
     */
    PERMISSION_DENIED(false, "权限不足，请联系管理员"),
    
    /**
     * 表访问权限不足
     */
    TABLE_PERMISSION_DENIED(false, "无权访问该表，请联系管理员添加权限"),
    
    // ==================== 数据库错误（部分可重试）====================
    
    /**
     * 表不存在
     */
    TABLE_NOT_FOUND(false, "表不存在，请检查表名是否正确"),
    
    /**
     * 字段不存在
     */
    COLUMN_NOT_FOUND(false, "字段不存在，请检查字段名是否正确"),
    
    /**
     * SQL语法错误（可修正后重试）
     */
    SYNTAX_ERROR(true, "SQL语法错误，正在尝试自动修正"),
    
    /**
     * 字段歧义
     */
    AMBIGUOUS_COLUMN(true, "字段名存在歧义，正在尝试修正"),
    
    /**
     * 数据库连接错误（可重试）
     */
    DATABASE_CONNECTION_ERROR(true, "数据库连接异常，正在重试"),
    
    /**
     * 查询超时（可重试）
     */
    QUERY_TIMEOUT(true, "查询超时，正在重试"),
    
    /**
     * 死锁（可重试）
     */
    DEADLOCK(true, "数据库死锁，正在重试"),
    
    // ==================== LLM错误（部分可重试）====================
    
    /**
     * LLM调用超时（可重试）
     */
    LLM_TIMEOUT(true, "LLM调用超时，正在重试"),
    
    /**
     * LLM返回格式错误（不可重试）
     */
    LLM_INVALID_RESPONSE(false, "LLM返回格式错误"),
    
    /**
     * LLM服务不可用（可重试）
     */
    LLM_SERVICE_UNAVAILABLE(true, "LLM服务暂时不可用，正在重试"),
    
    // ==================== 网络错误（可重试）====================
    
    /**
     * 网络连接错误（可重试）
     */
    NETWORK_ERROR(true, "网络连接异常，正在重试"),
    
    /**
     * 请求超时（可重试）
     */
    REQUEST_TIMEOUT(true, "请求超时，正在重试"),
    
    // ==================== 系统错误（不可重试，需告警）====================
    
    /**
     * 系统内部错误
     */
    SYSTEM_ERROR(false, "系统内部错误，请联系技术支持"),
    
    /**
     * 未知错误
     */
    UNKNOWN(false, "发生未知错误");
    
    /**
     * 是否可重试
     */
    private final boolean retryable;
    
    /**
     * 默认错误消息
     */
    private final String defaultMessage;
    
    ErrorType(boolean retryable, String defaultMessage) {
        this.retryable = retryable;
        this.defaultMessage = defaultMessage;
    }
}
