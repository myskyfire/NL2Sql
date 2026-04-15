package com.nl2sql.core.executor;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class SQLCorrectionService {
    
    /**
     * SQL纠错结果
     */
    @Data
    public static class CorrectionResult {
        private boolean success;
        private String correctedSQL;
        private String originalError;
        private List<String> suggestions;
        private int retryCount;
        
        public CorrectionResult() {
            this.suggestions = new ArrayList<>();
            this.retryCount = 0;
        }
    }
    
    /**
     * 尝试自动纠正SQL错误
     * 
     * @param sql 原始SQL
     * @param error 错误信息
     * @param maxRetries 最大重试次数
     * @return 纠正结果
     */
    public CorrectionResult autoCorrect(String sql, String error, int maxRetries) {
        CorrectionResult result = new CorrectionResult();
        result.setOriginalError(error);
        
        log.info("开始SQL纠错: sql={}, error={}", sql, error);
        
        String currentSQL = sql;
        
        for (int i = 0; i < maxRetries; i++) {
            result.setRetryCount(i + 1);
            
            // 分析错误类型
            ErrorType errorType = analyzeError(error);
            log.info("第{}次尝试，错误类型: {}", i + 1, errorType);
            
            // 根据错误类型尝试修复
            String correctedSQL = tryFix(currentSQL, error, errorType);
            
            if (correctedSQL != null && !correctedSQL.equals(currentSQL)) {
                log.info("SQL已修正: {} -> {}", currentSQL, correctedSQL);
                currentSQL = correctedSQL;
                
                // 验证修正后的SQL
                ValidationResult validation = validateSyntax(currentSQL);
                if (validation.isValid()) {
                    result.setSuccess(true);
                    result.setCorrectedSQL(currentSQL);
                    log.info("SQL纠错成功: {}", currentSQL);
                    return result;
                } else {
                    result.getSuggestions().add("尝试" + (i + 1) + ": " + validation.getMessage());
                }
            } else {
                result.getSuggestions().add("尝试" + (i + 1) + ": 无法自动修复");
                break;
            }
        }
        
        // 如果自动修复失败，提供建议
        List<String> finalSuggestions = generateSuggestions(sql, error);
        result.getSuggestions().addAll(finalSuggestions);
        
        log.warn("SQL纠错失败，提供{}条建议", finalSuggestions.size());
        return result;
    }
    
    /**
     * 分析错误类型
     */
    private ErrorType analyzeError(String error) {
        if (error == null) {
            return ErrorType.UNKNOWN;
        }
        
        String lowerError = error.toLowerCase();
        
        if (lowerError.contains("table") && lowerError.contains("doesn't exist")) {
            return ErrorType.TABLE_NOT_FOUND;
        }
        if (lowerError.contains("unknown column") || lowerError.contains("column") && lowerError.contains("not found")) {
            return ErrorType.COLUMN_NOT_FOUND;
        }
        if (lowerError.contains("syntax error") || lowerError.contains("sql syntax")) {
            return ErrorType.SYNTAX_ERROR;
        }
        if (lowerError.contains("ambiguous")) {
            return ErrorType.AMBIGUOUS_COLUMN;
        }
        if (lowerError.contains("limit") || lowerError.contains("fetch")) {
            return ErrorType.LIMIT_ERROR;
        }
        
        return ErrorType.UNKNOWN;
    }
    
    /**
     * 尝试修复SQL
     */
    private String tryFix(String sql, String error, ErrorType errorType) {
        switch (errorType) {
            case TABLE_NOT_FOUND:
                return fixTableNotFound(sql, error);
            case COLUMN_NOT_FOUND:
                return fixColumnNotFound(sql, error);
            case SYNTAX_ERROR:
                return fixSyntaxError(sql, error);
            case AMBIGUOUS_COLUMN:
                return fixAmbiguousColumn(sql, error);
            default:
                return null;
        }
    }
    
    /**
     * 修复表不存在错误
     */
    private String fixTableNotFound(String sql, String error) {
        // 提取错误的表名
        Pattern pattern = Pattern.compile("Table '([^']+)' doesn't exist");
        Matcher matcher = pattern.matcher(error);
        
        if (matcher.find()) {
            String wrongTable = matcher.group(1);
            log.warn("检测到不存在的表: {}", wrongTable);
            
            // 常见错误：复数形式、大小写、下划线
            String[] possibleTables = {
                wrongTable.endsWith("s") ? wrongTable.substring(0, wrongTable.length() - 1) : wrongTable + "s",
                wrongTable.toUpperCase(),
                wrongTable.toLowerCase(),
                wrongTable.replace("_", ""),
                wrongTable.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase()
            };
            
            // 这里应该查询数据库获取真实表名列表
            // 暂时返回null，由上层处理
            log.info("可能的表名: {}", String.join(", ", possibleTables));
        }
        
        return null;
    }
    
    /**
     * 修复字段不存在错误
     */
    private String fixColumnNotFound(String sql, String error) {
        // 提取错误的字段名
        Pattern pattern = Pattern.compile("Unknown column '([^']+)'");
        Matcher matcher = pattern.matcher(error);
        
        if (matcher.find()) {
            String wrongColumn = matcher.group(1);
            log.warn("检测到不存在的字段: {}", wrongColumn);
            
            // 常见错误：大小写、下划线、驼峰转换
            String correctedColumn = wrongColumn.toLowerCase();
            String camelCase = toCamelCase(wrongColumn);
            String snakeCase = toSnakeCase(wrongColumn);
            
            log.info("可能的字段名: {}, {}, {}", correctedColumn, camelCase, snakeCase);
            
            // 尝试替换
            return sql.replace(wrongColumn, correctedColumn);
        }
        
        return null;
    }
    
    /**
     * 修复语法错误
     */
    private String fixSyntaxError(String sql, String error) {
        String fixed = sql.trim();
        
        // 移除末尾多余的分号
        if (fixed.endsWith(";;")) {
            fixed = fixed.replaceAll(";+$", ";");
            return fixed;
        }
        
        // 检查是否有未闭合的括号
        int openCount = countChar(fixed, '(');
        int closeCount = countChar(fixed, ')');
        if (openCount > closeCount) {
            // Java 8兼容：手动重复字符串
            StringBuilder sb = new StringBuilder(fixed);
            for (int i = 0; i < openCount - closeCount; i++) {
                sb.append(')');
            }
            return sb.toString();
        }
        
        // LIMIT语法修正
        if (error.toLowerCase().contains("limit")) {
            Pattern pattern = Pattern.compile("LIMIT\\s+\\d+\\s*,\\s*\\d+", Pattern.CASE_INSENSITIVE);
            if (!pattern.matcher(fixed).find()) {
                // 可能是OFFSET语法问题
                fixed = fixed.replaceAll("(?i)LIMIT\\s+(\\d+)\\s+OFFSET\\s+(\\d+)", "LIMIT $2, $1");
                return fixed;
            }
        }
        
        return null;
    }
    
    /**
     * 修复歧义字段
     */
    private String fixAmbiguousColumn(String sql, String error) {
        // 提取歧义字段名
        Pattern pattern = Pattern.compile("Column '([^']+)' in field list is ambiguous");
        Matcher matcher = pattern.matcher(error);
        
        if (matcher.find()) {
            String ambiguousColumn = matcher.group(1);
            log.warn("检测到歧义字段: {}", ambiguousColumn);
            
            // 为字段添加表别名前缀（简单策略：使用第一个表）
            // 这需要更复杂的SQL解析，这里简化处理
            return sql.replace(ambiguousColumn, "t1." + ambiguousColumn);
        }
        
        return null;
    }
    
    /**
     * 验证SQL语法
     */
    private ValidationResult validateSyntax(String sql) {
        ValidationResult result = new ValidationResult();
        
        // 基本语法检查
        if (sql == null || sql.trim().isEmpty()) {
            result.setValid(false);
            result.setMessage("SQL为空");
            return result;
        }
        
        // 检查括号匹配
        int openCount = countChar(sql, '(');
        int closeCount = countChar(sql, ')');
        if (openCount != closeCount) {
            result.setValid(false);
            result.setMessage("括号不匹配: 左括号" + openCount + "个，右括号" + closeCount + "个");
            return result;
        }
        
        // 检查是否以SELECT/SHOW/DESC/EXPLAIN开头
        String upperSQL = sql.trim().toUpperCase();
        if (!upperSQL.startsWith("SELECT") && !upperSQL.startsWith("SHOW") && 
            !upperSQL.startsWith("DESC") && !upperSQL.startsWith("EXPLAIN")) {
            result.setValid(false);
            result.setMessage("SQL必须以SELECT/SHOW/DESC/EXPLAIN开头");
            return result;
        }
        
        result.setValid(true);
        return result;
    }
    
    /**
     * 生成修复建议
     */
    private List<String> generateSuggestions(String sql, String error) {
        List<String> suggestions = new ArrayList<>();
        
        if (error == null) {
            return suggestions;
        }
        
        String lowerError = error.toLowerCase();
        
        if (lowerError.contains("table") && lowerError.contains("exist")) {
            suggestions.add("检查表名是否正确，注意大小写和下划线");
            suggestions.add("确认表是否存在于数据库中");
        }
        
        if (lowerError.contains("column") || lowerError.contains("field")) {
            suggestions.add("检查字段名是否正确");
            suggestions.add("如果是多表查询，为字段添加表名前缀");
        }
        
        if (lowerError.contains("syntax")) {
            suggestions.add("检查SQL语法是否正确");
            suggestions.add("确认括号是否匹配");
            suggestions.add("检查关键字拼写");
        }
        
        if (lowerError.contains("limit")) {
            suggestions.add("检查LIMIT语法: LIMIT offset, count 或 LIMIT count OFFSET offset");
        }
        
        suggestions.add("可以尝试用自然语言重新描述查询需求");
        
        return suggestions;
    }
    
    // 工具方法
    
    private int countChar(String str, char ch) {
        int count = 0;
        for (char c : str.toCharArray()) {
            if (c == ch) count++;
        }
        return count;
    }
    
    private String toCamelCase(String snakeCase) {
        StringBuilder result = new StringBuilder();
        boolean nextUpper = false;
        for (char c : snakeCase.toCharArray()) {
            if (c == '_') {
                nextUpper = true;
            } else {
                result.append(nextUpper ? Character.toUpperCase(c) : c);
                nextUpper = false;
            }
        }
        return result.toString();
    }
    
    private String toSnakeCase(String camelCase) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < camelCase.length(); i++) {
            char c = camelCase.charAt(i);
            if (Character.isUpperCase(c) && i > 0) {
                result.append('_');
            }
            result.append(Character.toLowerCase(c));
        }
        return result.toString();
    }
    
    @Data
    private static class ValidationResult {
        private boolean valid;
        private String message;
    }
    
    enum ErrorType {
        TABLE_NOT_FOUND,
        COLUMN_NOT_FOUND,
        SYNTAX_ERROR,
        AMBIGUOUS_COLUMN,
        LIMIT_ERROR,
        UNKNOWN
    }
}
