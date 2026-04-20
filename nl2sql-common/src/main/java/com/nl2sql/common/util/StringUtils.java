package com.nl2sql.common.util;

/**
 * 字符串工具类
 */
public class StringUtils {
    
    /**
     * 格式化列名为可读形式
     * - 下划线转空格
     * - 驼峰转空格
     * - 首字母大写
     */
    public static String formatReadable(String colName) {
        if (colName == null) return "";
        
        // 下划线转空格
        String readable = colName.replace('_', ' ');
        
        // 驼峰转空格
        readable = readable.replaceAll("([a-z])([A-Z])", "$1 $2");
        
        // 首字母大写
        if (!readable.isEmpty()) {
            readable = Character.toUpperCase(readable.charAt(0)) + readable.substring(1);
        }
        
        return readable;
    }
}
