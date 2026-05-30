package com.nl2sql.common.util;

/**
 * Boolean 工具类
 * 兼容 Boolean 和 String 类型的转换
 */
public class BooleanUtils {
    
    /**
     * 安全地将对象转换为 Boolean
     * 支持 Boolean 和 String 类型
     * 
     * @param obj 待转换的对象
     * @return Boolean 值，如果无法转换则返回 null
     */
    public static Boolean toBoolean(Object obj) {
        if (obj == null) {
            return null;
        }
        
        if (obj instanceof Boolean) {
            return (Boolean) obj;
        }
        
        if (obj instanceof String) {
            String str = ((String) obj).trim();
            if ("true".equalsIgnoreCase(str) || "1".equals(str) || "yes".equalsIgnoreCase(str)) {
                return true;
            }
            if ("false".equalsIgnoreCase(str) || "0".equals(str) || "no".equalsIgnoreCase(str)) {
                return false;
            }
        }
        
        if (obj instanceof Number) {
            return ((Number) obj).intValue() != 0;
        }
        
        return null;
    }
    
    /**
     * 安全地将对象转换为 boolean（带默认值）
     * 
     * @param obj 待转换的对象
     * @param defaultValue 默认值
     * @return boolean 值
     */
    public static boolean toBooleanOrDefault(Object obj, boolean defaultValue) {
        Boolean result = toBoolean(obj);
        return result != null ? result : defaultValue;
    }
    
    /**
     * 判断对象是否为 true
     * 
     * @param obj 待判断的对象
     * @return 如果是 true 则返回 true，否则返回 false
     */
    public static boolean isTrue(Object obj) {
        return toBooleanOrDefault(obj, false);
    }
    
    /**
     * 判断对象是否为 false
     * 
     * @param obj 待判断的对象
     * @return 如果是 false 则返回 true，否则返回 false
     */
    public static boolean isFalse(Object obj) {
        return !isTrue(obj);
    }
}
