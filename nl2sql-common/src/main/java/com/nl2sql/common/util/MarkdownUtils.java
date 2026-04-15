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
        
        // 尝试匹配Markdown代码块
        Matcher matcher = MARKDOWN_CODE_BLOCK_PATTERN.matcher(trimmed);
        if (matcher.find()) {
            String extracted = matcher.group(1).trim();
            log.debug("[Markdown清洗] 从代码块中提取内容，长度: {}", extracted.length());
            return extracted;
        }
        
        // 不是Markdown格式，返回原内容
        return trimmed;
    }
    
    /**
     * 清理SQL语句中的Markdown标记和前缀
     * 
     * @param sql 可能包含Markdown的SQL语句
     * @return 清理后的SQL
     */
    public static String cleanSQL(String sql) {
        if (sql == null) return null;
        
        // 去除Markdown代码块
        sql = extractFromMarkdown(sql);
        
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
        
        return sql;
    }
}
