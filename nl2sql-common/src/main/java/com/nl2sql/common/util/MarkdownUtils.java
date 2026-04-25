package com.nl2sql.common.util;

import lombok.extern.slf4j.Slf4j;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdown清洗工具类
 * 用于处理LLM返回的Markdown格式内容
 */
@Slf4j
public class MarkdownUtils {
    
    private static final Pattern MARKDOWN_CODE_BLOCK_PATTERN = 
        Pattern.compile("```(?:json)?\\s*([\\s\\S]*?)\\s*```");
    
    /**
     * 从Markdown代码块中提取纯文本/JSON
     * 
     * @param markdownContent 可能包含Markdown代码块的内容
     * @return 提取后的纯文本，如果不是Markdown格式则返回原内容
     */
    public static String extractFromMarkdown(String markdownContent) {
        if (markdownContent == null || markdownContent.trim().isEmpty()) {
            return markdownContent;
        }
        
        String trimmed = markdownContent.trim();
        
        // ✅ 关键修复：优先提取 JSON 代码块（避免误提取 SQL 代码块）
        Pattern jsonPattern = Pattern.compile("```json\\s*([\\s\\S]*?)\\s*```");
        Matcher jsonMatcher = jsonPattern.matcher(trimmed);
        if (jsonMatcher.find()) {
            String extracted = jsonMatcher.group(1).trim();
            log.debug("[Markdown清洗] 从 JSON 代码块中提取内容，长度: {}", extracted.length());
            return extracted;
        }
        
        // 降级：匹配任意代码块
        Matcher matcher = MARKDOWN_CODE_BLOCK_PATTERN.matcher(trimmed);
        if (matcher.find()) {
            String extracted = matcher.group(1).trim();
            log.debug("[Markdown清洗] 从通用代码块中提取内容，长度: {}", extracted.length());
            return extracted;
        }
        
        // 不是 Markdown 格式，返回原内容
        return trimmed;
    }
    
    /**
     * 清理SQL语句中的Markdown标记和前缀
     * 
     * @param sql 可能包含Markdown的SQL语句
     * @return 清理后的SQL，如果无效则返回安全默认值
     */
    public static String cleanSQL(String sql) {
        if (sql == null || sql.trim().isEmpty()) {
            log.warn("[SQL清洗] 输入为空，返回安全默认查询");
            return "SELECT 'no_result_found' AS info"; // ✅ 防御性处理：避免NULL
        }
        
        String original = sql;
        
        // 去除Markdown代码块
        sql = extractFromMarkdown(sql);
        
        // ✅ 关键修复：检测大模型可能输出的 NULL 字符串
        if (sql.toUpperCase().trim().equals("NULL") || 
            sql.toUpperCase().trim().equals("NONE") ||
            sql.toUpperCase().trim().equals("N/A")) {
            log.warn("[SQL清洗] 检测到LLM输出NULL/None，转换为安全默认查询: {}", original);
            return "SELECT 'no_result_found' AS info";
        }
        
        // ✅ 清理 LLM 生成的 "sql " 前缀（统一处理，兼容大小写）
        String lowerSql = sql.toLowerCase();
        if (lowerSql.startsWith("sql ")) {
            sql = sql.substring(4).trim();
            log.debug("[SQL清洗] 清理 'sql ' 前缀: {}", sql);
        } else if (lowerSql.startsWith("sql\t") || lowerSql.startsWith("sql\n")) {
            // 处理 "sql\tSELECT..." 或 "sql\nSELECT..."
            sql = sql.substring(3).trim();
            log.debug("[SQL清洗] 清理 'sql' + 空白符: {}", sql);
        }
        
        // 去除多余空白
        sql = sql.replace("\r\n", " ").replace("\n", " ").replace("\r", " ");
        sql = sql.replaceAll("\\s+", " ").trim();
        
        // 只保留第一条SQL（以分号结尾）
        if (sql.contains(";")) {
            sql = sql.substring(0, sql.indexOf(";") + 1).trim();
        }
        
        // 去除中文别名
        sql = sql.replaceAll("AS\\s+[\u4e00-\u9fa5]+", "");
        
        // ✅ 最终验证：如果清洗后仍然为空，返回安全默认值
        if (sql.trim().isEmpty()) {
            log.warn("[SQL清洗] 清洗后为空，返回安全默认查询. 原始输入: {}", original);
            return "SELECT 'no_result_found' AS info";
        }
        
        log.debug("[SQL清洗] 清洗完成: {} -> {}", original, sql);
        return sql;
    }
}
