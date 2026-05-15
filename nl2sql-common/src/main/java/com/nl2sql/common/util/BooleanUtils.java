package com.nl2sql.common.util;

/**
 * 布尔工具类
 * 提供布尔值转换和判断的实用方法
 */
public class BooleanUtils {
    
    /**
     * 将对象转换为布尔值
     * 
     * @param value 要转换的对象
     * @return 转换后的布尔值
     */
    public static Boolean toBoolean(Object value) {
        if (value == null) {
            return false;
        }
        
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        
        if (value instanceof String) {
            String str = ((String) value).trim().toLowerCase();
            return "true".equals(str) || "1".equals(str) || "yes".equals(str) || "on".equals(str);
        }
        
        if (value instanceof Number) {
            return ((Number) value).intValue() != 0;
        }
        
        return false;
    }
    
    /**
     * 判断对象是否为true
     * 
     * @param value 要判断的对象
     * @return 如果为true则返回true，否则返回false
     */
    public static boolean isTrue(Object value) {
        return Boolean.TRUE.equals(toBoolean(value));
    }
    
    /**
     * 判断对象是否为false
     * 
     * @param value 要判断的对象
     * @return 如果为false则返回true，否则返回false
     */
    public static boolean isFalse(Object value) {
        return !isTrue(value);
    }
}