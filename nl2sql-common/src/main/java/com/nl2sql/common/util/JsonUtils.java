package com.nl2sql.common.util;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

/**
 * JSON工具类 - LLM响应专用
 * 
 * 功能：
 * 1. 修复LLM生成的无效JSON（未转义引号、缺失值、多余逗号等）
 * 2. 安全的JSON解析（带容错处理）
 * 3. 统一的错误处理
 * 
 * @author DataMind AI
 * @since 1.0.0
 */
@Slf4j
public class JsonUtils {
    
    private static final ObjectMapper objectMapper = new ObjectMapper();
    
    /**
     * ✅ 安全解析JSON字符串为Map（带轻量级修复）
     * 
     * @param json JSON字符串
     * @return 解析后的Map，失败返回null
     */
    public static Map<String, Object> parseToJsonMap(String json) {
        if (json == null || json.trim().isEmpty()) {
            log.warn("[JsonUtils] JSON字符串为空");
            return null;
        }
        
        try {
            // 1. 清理Markdown代码块
            String cleaned = cleanMarkdownCodeBlock(json);
            
            // 2. 修复中文引号
            cleaned = fixChineseQuotes(cleaned);
            
            // 3. 修复常见简单错误
            cleaned = fixSimpleJsonErrors(cleaned);
            
            // 4. 解析JSON
            return objectMapper.readValue(cleaned, Map.class);
            
        } catch (Exception e) {
            log.warn("[JsonUtils] JSON解析失败: {}", e.getMessage());
            log.debug("[JsonUtils] 原始JSON: {}", json);
            return null;
        }
    }
    
    /**
     * ✅ 安全解析JSON字符串为指定类型（带轻量级修复）
     * 
     * @param json JSON字符串
     * @param clazz 目标类型
     * @return 解析后的对象，失败返回null
     */
    public static <T> T parseJson(String json, Class<T> clazz) {
        if (json == null || json.trim().isEmpty()) {
            log.warn("[JsonUtils] JSON字符串为空");
            return null;
        }
        
        try {
            // 1. 清理Markdown代码块
            String cleaned = cleanMarkdownCodeBlock(json);
            
            // 2. 修复中文引号
            cleaned = fixChineseQuotes(cleaned);
            
            // 3. 修复常见简单错误
            cleaned = fixSimpleJsonErrors(cleaned);
            
            // 4. 解析JSON
            return objectMapper.readValue(cleaned, clazz);
            
        } catch (Exception e) {
            log.warn("[JsonUtils] JSON解析失败: {}", e.getMessage());
            log.debug("[JsonUtils] 原始JSON: {}", json);
            return null;
        }
    }
    
    /**
     * 清理Markdown代码块标记
     */
    private static String cleanMarkdownCodeBlock(String json) {
        String cleaned = json.trim();
        if (cleaned.startsWith("```") && cleaned.endsWith("```")) {
            cleaned = cleaned.substring(3, cleaned.length() - 3).trim();
            if (cleaned.startsWith("json")) {
                cleaned = cleaned.substring(4).trim();
            }
        }
        return cleaned;
    }
    
    /**
     * 修复中文引号和字符串内部的英文引号
     */
    private static String fixChineseQuotes(String json) {
        // 1. 删除所有中文引号
        String fixed = json.replace("“", "").replace("”", "");
        
        // 2. ✅ 关键修复：转义字符串值内部的未转义英文引号
        // 问题案例："reason": "包含"order"等标签"
        // 解决：将字符串值内部的 " 替换为空格（保留语义）
        // 策略：找到 "key": " 和下一个 " 之间的内容，清理内部的引号
        StringBuilder result = new StringBuilder();
        boolean inStringValue = false;
        int i = 0;
        
        while (i < fixed.length()) {
            char c = fixed.charAt(i);
            
            if (c == '"') {
                // 检查是否是字符串值的开始/结束
                if (!inStringValue) {
                    // 检查前一个非空白字符是否是 : （表示这是字符串值的开始）
                    int j = i - 1;
                    while (j >= 0 && Character.isWhitespace(fixed.charAt(j))) j--;
                    if (j >= 0 && fixed.charAt(j) == ':') {
                        inStringValue = true;
                        result.append(c); // 保留开始的引号
                    } else {
                        result.append(c); // JSON结构的引号（key或字符串结束）
                    }
                } else {
                    // 在字符串值内部，检查下一个非空白字符是否是 , 或 } 或 ]
                    int j = i + 1;
                    while (j < fixed.length() && Character.isWhitespace(fixed.charAt(j))) j++;
                    if (j < fixed.length() && (fixed.charAt(j) == ',' || fixed.charAt(j) == '}' || fixed.charAt(j) == ']')) {
                        inStringValue = false;
                        result.append(c); // 字符串值结束的引号
                    } else {
                        // 字符串值内部的引号，替换为空格
                        result.append(' ');
                    }
                }
            } else {
                result.append(c);
            }
            
            i++;
        }
        
        return result.toString();
    }
    
    /**
     * ✅ 修复常见简单JSON错误
     * 1. 缺少值："key": } → "key": null}
     * 2. 多余逗号：,} → }
     */
    private static String fixSimpleJsonErrors(String json) {
        if (json == null || json.isEmpty()) {
            return json;
        }
        
        String fixed = json;
        
        // 1. 修复缺少值的情况："key": } → "key": null}
        fixed = fixed.replaceAll(":\\s*}", ": null}");
        
        // 2. 修复缺少值的情况："key": , → "key": null,
        fixed = fixed.replaceAll(":\\s*,", ": null,");
        
        // 3. 移除多余逗号：,} → }
        fixed = fixed.replaceAll(",\\s*}", "}");
        
        // 4. 移除多余逗号：,] → ]
        fixed = fixed.replaceAll(",\\s*]", "]");
        
        return fixed;
    }
    
    /**
     * ✅ 将对象序列化为JSON字符串
     */
    public static String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            log.error("[JsonUtils] JSON序列化失败", e);
            return "{}";
        }
    }
    
    /**
     * ✅ 将对象序列化为格式化的JSON字符串（便于调试）
     */
    public static String toPrettyJson(Object obj) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(obj);
        } catch (Exception e) {
            log.error("[JsonUtils] JSON格式化失败", e);
            return "{}";
        }
    }
}
