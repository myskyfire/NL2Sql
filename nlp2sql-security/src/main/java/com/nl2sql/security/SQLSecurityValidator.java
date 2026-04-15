package com.nl2sql.security;

import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.drop.Drop;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.truncate.Truncate;
import net.sf.jsqlparser.statement.update.Update;
import net.sf.jsqlparser.statement.alter.Alter;
import net.sf.jsqlparser.statement.create.table.CreateTable;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
public class SQLSecurityValidator {
    
    // DDL危险操作
    private static final Set<String> DDL_KEYWORDS = new HashSet<>(Arrays.asList(
        "DROP", "ALTER", "CREATE", "TRUNCATE", "RENAME", "GRANT", "REVOKE"
    ));
    
    // DML危险操作(写操作)
    private static final Set<String> DML_WRITE_KEYWORDS = new HashSet<>(Arrays.asList(
        "DELETE", "UPDATE", "INSERT", "MERGE", "UPSERT"
    ));
    
    // 其他危险操作
    private static final Set<String> OTHER_DANGEROUS = new HashSet<>(Arrays.asList(
        "EXEC", "EXECUTE", "CALL", "LOAD_FILE", "INTO OUTFILE", "INTO DUMPFILE",
        "BENCHMARK", "SLEEP", "WAITFOR", "DBMS_LOCK"
    ));
    
    private static final Set<String> SENSITIVE_COLUMNS = new HashSet<>(Arrays.asList(
        "phone", "id_card", "mobile", "email", "password", "address", "idcard"
    ));
    
    private static final int MAX_JOIN_COUNT = 2;
    
    public ValidationResult validate(String sql) {
        ValidationResult result = new ValidationResult();
        
        if (sql == null || sql.trim().isEmpty()) {
            result.setValid(false);
            result.setMessage("SQL不能为空");
            return result;
        }
        
        String trimmedSQL = sql.trim();
        String upperSQL = trimmedSQL.toUpperCase();
        
        // 第一层防护：检查是否包含分号(防止多语句执行)
        if (trimmedSQL.contains(";")) {
            // 允许末尾分号，但不允许中间有分号
            String withoutTrailingSemicolon = trimmedSQL.replaceAll(";+\\s*$", "");
            if (withoutTrailingSemicolon.contains(";")) {
                result.setValid(false);
                result.setMessage("禁止执行多条SQL语句");
                return result;
            }
        }
        
        // 第二层防护：检查注释中是否隐藏危险操作
        if (upperSQL.contains("--") || upperSQL.contains("/*") || upperSQL.contains("#")) {
            // 移除注释后再检查
            String sqlWithoutComments = removeComments(trimmedSQL);
            if (containsDangerousOperations(sqlWithoutComments)) {
                result.setValid(false);
                result.setMessage("检测到注释中隐藏的危险操作");
                return result;
            }
        }
        
        // 第三层防护：关键词检测(DDL + DML写操作 + 其他危险操作)
        String dangerCheck = checkDangerousKeywords(upperSQL);
        if (dangerCheck != null) {
            result.setValid(false);
            result.setMessage(dangerCheck);
            return result;
        }
        
        // 第四层防护：只允许SELECT/SHOW/DESC/EXPLAIN开头
        if (!upperSQL.startsWith("SELECT") && !upperSQL.startsWith("SHOW") && 
            !upperSQL.startsWith("DESC") && !upperSQL.startsWith("DESCRIBE") && 
            !upperSQL.startsWith("EXPLAIN")) {
            result.setValid(false);
            result.setMessage("仅允许查询操作(SELECT/SHOW/DESC/EXPLAIN)，禁止所有DML和DDL操作");
            return result;
        }
        
        // 第五层防护：JSqlParser AST解析验证(最严格)
        try {
            Statement statement = CCJSqlParserUtil.parse(trimmedSQL);
            
            // 强制检查Statement类型，只允许Select
            if (!(statement instanceof Select)) {
                result.setValid(false);
                result.setMessage("SQL解析结果不是SELECT语句，类型: " + statement.getClass().getSimpleName());
                return result;
            }
            
            // 检查SELECT语句内部结构
            Select selectStatement = (Select) statement;
            PlainSelect plainSelect = selectStatement.getSelectBody() instanceof PlainSelect 
                ? (PlainSelect) selectStatement.getSelectBody() 
                : null;
            
            if (plainSelect != null) {
                // 检查JOIN数量
                List<Join> joins = plainSelect.getJoins();
                if (joins != null && joins.size() > MAX_JOIN_COUNT) {
                    result.setValid(false);
                    result.setMessage("JOIN表数量超过限制(最多" + MAX_JOIN_COUNT + "张)");
                    return result;
                }
                
                // 检查是否有全表扫描
                if (plainSelect.getWhere() == null && plainSelect.getLimit() == null) {
                    result.setValid(false);
                    result.setMessage("检测到全表扫描，请添加WHERE条件或LIMIT限制");
                    return result;
                }
            }
            
        } catch (JSQLParserException e) {
            // 解析失败直接拒绝，宁可错杀不可放过
            result.setValid(false);
            result.setMessage("SQL语法解析失败，可能存在安全隐患: " + e.getMessage());
            log.error("SQL解析失败: {}, SQL: {}", e.getMessage(), sql);
            return result;
        }
        
        result.setValid(true);
        result.setMessage("验证通过");
        return result;
    }
    
    /**
     * 移除SQL注释
     */
    private String removeComments(String sql) {
        // 移除单行注释 --
        sql = sql.replaceAll("--[^\n]*", "");
        // 移除多行注释 /* */
        sql = sql.replaceAll("/\\*.*?\\*/", "");
        // 移除MySQL注释 #
        sql = sql.replaceAll("#[^\n]*", "");
        return sql;
    }
    
    /**
     * 检查是否包含危险操作
     */
    private boolean containsDangerousOperations(String sql) {
        String upperSQL = sql.toUpperCase();
        
        for (String keyword : DDL_KEYWORDS) {
            Pattern pattern = Pattern.compile("\\b" + keyword + "\\b");
            if (pattern.matcher(upperSQL).find()) {
                return true;
            }
        }
        
        for (String keyword : DML_WRITE_KEYWORDS) {
            Pattern pattern = Pattern.compile("\\b" + keyword + "\\b");
            if (pattern.matcher(upperSQL).find()) {
                return true;
            }
        }
        
        for (String keyword : OTHER_DANGEROUS) {
            if (upperSQL.contains(keyword)) {
                return true;
            }
        }
        
        return false;
    }
    
    /**
     * 检查危险关键词
     */
    private String checkDangerousKeywords(String upperSQL) {
        // 检查DDL操作
        for (String keyword : DDL_KEYWORDS) {
            Pattern pattern = Pattern.compile("\\b" + keyword + "\\b");
            Matcher matcher = pattern.matcher(upperSQL);
            if (matcher.find()) {
                return "检测到DDL危险操作: " + keyword + "，禁止执行";
            }
        }
        
        // 检查DML写操作
        for (String keyword : DML_WRITE_KEYWORDS) {
            Pattern pattern = Pattern.compile("\\b" + keyword + "\\b");
            Matcher matcher = pattern.matcher(upperSQL);
            if (matcher.find()) {
                return "检测到DML写操作: " + keyword + "，仅允许查询(SELECT)";
            }
        }
        
        // 检查其他危险操作
        for (String keyword : OTHER_DANGEROUS) {
            if (upperSQL.contains(keyword)) {
                return "检测到危险函数/操作: " + keyword;
            }
        }
        
        return null;
    }
    
    public String desensitizeData(List<Map<String, Object>> data) {
        if (data == null || data.isEmpty()) {
            return "";
        }
        
        for (Map<String, Object> row : data) {
            for (Map.Entry<String, Object> entry : row.entrySet()) {
                String columnName = entry.getKey().toLowerCase();
                if (SENSITIVE_COLUMNS.contains(columnName) && entry.getValue() != null) {
                    row.put(entry.getKey(), "***");
                }
            }
        }
        
        return "";
    }
    
    @lombok.Data
    public static class ValidationResult {
        private boolean valid;
        private String message;
    }
}
