package com.nl2sql.core.agent.validation;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.*;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * SQL 验证服务 - 使用 JSqlParser 进行语法校验和智能检查
 * 
 * 功能：
 * 1. 语法预校验（LLM 生成 SQL 后立即检查）
 * 2. 聚合查询合法性检查（GROUP BY vs SELECT）
 * 3. JOIN 条件完整性检查
 * 4. 提供详细的错误定位信息，支持 Self-Correction
 */
@Slf4j
@Service
public class SQLValidationService {
    
    /**
     * 验证 SQL 语法
     * 
     * @param sql 待验证的 SQL 语句
     * @return 验证结果
     */
    public ValidationResult validateSyntax(String sql) {
        if (sql == null || sql.trim().isEmpty()) {
            return ValidationResult.failure("SQL 语句为空", null);
        }
        
        try {
            Statement statement = CCJSqlParserUtil.parse(sql);
            log.debug("[SQLValidation] 语法校验通过: {}", sql);
            return ValidationResult.success(statement);
            
        } catch (JSQLParserException e) {
            String errorMessage = extractErrorMessage(e, sql);
            log.warn("[SQLValidation] 语法校验失败: {}", errorMessage);
            return ValidationResult.failure(errorMessage, null);
        }
    }
    
    /**
     * 检查聚合查询合法性
     * 
     * 规则：
     * - SELECT 中的非聚合字段必须出现在 GROUP BY 中
     * - HAVING 子句只能包含聚合函数或 GROUP BY 字段
     * 
     * @param sql SQL 语句
     * @return 问题列表（空列表表示合法）
     */
    public List<String> checkAggregation(String sql) {
        List<String> issues = new ArrayList<>();
        
        try {
            Statement statement = CCJSqlParserUtil.parse(sql);
            
            if (!(statement instanceof Select)) {
                return issues; // 非 SELECT 语句无需检查
            }
            
            Select select = (Select) statement;
            SelectBody selectBody = select.getSelectBody();
            
            if (!(selectBody instanceof PlainSelect)) {
                return issues; // 暂不支持复杂查询
            }
            
            PlainSelect plainSelect = (PlainSelect) selectBody;
            GroupByElement groupBy = plainSelect.getGroupBy();
            
            // ✅ 修复：只有当 SELECT 中既有聚合函数又有非聚合字段时，才需要 GROUP BY
            if (groupBy == null && hasAggregateFunction(plainSelect)) {
                // 检查是否有非聚合字段
                List<String> nonAggregateColumns = extractNonAggregateColumns(plainSelect);
                
                // 如果只有聚合函数（无非聚合字段），是合法的单行聚合查询
                if (!nonAggregateColumns.isEmpty()) {
                    issues.add("⚠️ 检测到聚合函数和非聚合字段混合，但未使用 GROUP BY，可能导致意外结果");
                    return issues;
                }
                // 否则是合法的单行聚合查询（如 SELECT COUNT(*) FROM ...），无需警告
            }
            
            if (groupBy != null) {
                // 提取 GROUP BY 字段
                Set<String> groupByColumns = extractGroupByColumns(groupBy);
                
                // 提取 SELECT 中的非聚合字段
                List<String> nonAggregateColumns = extractNonAggregateColumns(plainSelect);
                
                // 检查是否有非聚合字段不在 GROUP BY 中
                for (String column : nonAggregateColumns) {
                    String columnLower = column.toLowerCase();
                    
                    // ✅ 修复：如果是表达式（如 DATE_FORMAT(...)），检查是否在 GROUP BY 中有相同表达式
                    boolean foundInGroupBy = false;
                    for (String groupByCol : groupByColumns) {
                        // 直接匹配或去除空格后匹配
                        if (columnLower.equals(groupByCol) || 
                            columnLower.replaceAll("\\s+", "").equals(groupByCol.replaceAll("\\s+", ""))) {
                            foundInGroupBy = true;
                            break;
                        }
                    }
                    
                    if (!foundInGroupBy) {
                        issues.add(String.format(
                            "❌ 字段 '%s' 在 SELECT 中但未在 GROUP BY 中，这会导致 MySQL 报错",
                            column
                        ));
                    }
                }
            }
            
        } catch (Exception e) {
            log.warn("[SQLValidation] 聚合检查失败: {}", e.getMessage());
            issues.add("⚠️ 无法完成聚合检查: " + e.getMessage());
        }
        
        return issues;
    }
    
    /**
     * 检查 JOIN 条件完整性
     * 
     * @param sql SQL 语句
     * @return 问题列表（空列表表示完整）
     */
    public List<String> checkJoinConditions(String sql) {
        List<String> issues = new ArrayList<>();
        
        try {
            Statement statement = CCJSqlParserUtil.parse(sql);
            
            if (!(statement instanceof Select)) {
                return issues;
            }
            
            Select select = (Select) statement;
            SelectBody selectBody = select.getSelectBody();
            
            if (!(selectBody instanceof PlainSelect)) {
                return issues;
            }
            
            PlainSelect plainSelect = (PlainSelect) selectBody;
            List<Join> joins = plainSelect.getJoins();
            
            if (joins == null || joins.isEmpty()) {
                return issues; // 无 JOIN
            }
            
            // 检查每个 JOIN 是否有 ON 条件
            for (int i = 0; i < joins.size(); i++) {
                Join join = joins.get(i);
                Expression onExpression = join.getOnExpression();
                
                if (onExpression == null) {
                    String tableName = extractJoinTableName(join);
                    issues.add(String.format(
                        "❌ JOIN #%d (%s) 缺少 ON 条件，可能导致笛卡尔积",
                        i + 1, tableName
                    ));
                }
            }
            
            // 检查是否有多表查询但无显式 JOIN（隐式 CROSS JOIN 风险）
            if (joins.isEmpty() && plainSelect.getFromItem() != null) {
                // 检查 WHERE 中是否有连接条件
                Expression where = plainSelect.getWhere();
                if (where != null && !hasJoinCondition(where)) {
                    issues.add("⚠️ 多表查询但未使用显式 JOIN，建议使用 INNER JOIN ... ON 语法");
                }
            }
            
        } catch (Exception e) {
            log.warn("[SQLValidation] JOIN 检查失败: {}", e.getMessage());
            issues.add("⚠️ 无法完成 JOIN 检查: " + e.getMessage());
        }
        
        return issues;
    }
    
    /**
     * 综合验证（语法 + 聚合 + JOIN）
     * 
     * @param sql SQL 语句
     * @return 完整的验证报告
     */
    public ValidationReport comprehensiveValidate(String sql) {
        ValidationReport report = new ValidationReport();
        report.setSql(sql);
        
        // 1. 语法校验
        ValidationResult syntaxResult = validateSyntax(sql);
        report.setSyntaxValid(syntaxResult.isValid());
        report.setSyntaxError(syntaxResult.getErrorMessage());
        
        if (!syntaxResult.isValid()) {
            report.setOverallValid(false);
            report.setSuggestions(generateSyntaxFixSuggestions(syntaxResult.getErrorMessage(), sql));
            return report;
        }
        
        // 2. 聚合检查
        List<String> aggregationIssues = checkAggregation(sql);
        report.setAggregationIssues(aggregationIssues);
        
        // 3. JOIN 检查
        List<String> joinIssues = checkJoinConditions(sql);
        report.setJoinIssues(joinIssues);
        
        // 4. 总体评估
        boolean hasIssues = !aggregationIssues.isEmpty() || !joinIssues.isEmpty();
        report.setOverallValid(!hasIssues);
        
        if (hasIssues) {
            List<String> allSuggestions = new ArrayList<>();
            allSuggestions.addAll(aggregationIssues);
            allSuggestions.addAll(joinIssues);
            report.setSuggestions(allSuggestions);
        }
        
        return report;
    }
    
    /**
     * ✅ 新增：校验 SQL 中的列名是否在 Schema 白名单内
     * 
     * 目的：防止大模型幻觉生成不存在的列名
     * 
     * @param sql SQL 语句
     * @param allowedColumns 允许的列名集合（表名.列名 或 列名）
     * @return 问题列表（空列表表示合法）
     */
    public List<String> validateColumnWhitelist(String sql, Set<String> allowedColumns) {
        List<String> issues = new ArrayList<>();
        
        if (allowedColumns == null || allowedColumns.isEmpty()) {
            log.debug("[SQLValidation] 未提供 Schema 白名单，跳过列名校验");
            return issues;
        }
        
        try {
            Statement statement = CCJSqlParserUtil.parse(sql);
            
            if (!(statement instanceof Select)) {
                return issues; // 非 SELECT 语句无需检查
            }
            
            Select select = (Select) statement;
            SelectBody selectBody = select.getSelectBody();
            
            if (!(selectBody instanceof PlainSelect)) {
                return issues; // 暂不支持复杂查询
            }
            
            PlainSelect plainSelect = (PlainSelect) selectBody;
            
            // 提取 SELECT 中的所有列
            Set<String> usedColumns = extractAllColumns(plainSelect);
            
            // 将允许的列名转换为小写以便比较
            Set<String> allowedLower = allowedColumns.stream()
                .map(String::toLowerCase)
                .collect(Collectors.toSet());
            
            // 检查每个使用的列是否在白名单中
            for (String column : usedColumns) {
                String columnLower = column.toLowerCase();
                
                // 支持两种匹配方式：
                // 1. 完全匹配："user_id" in ["user_id", "orders.user_id"]
                // 2. 带表名前缀匹配："orders.user_id" in ["user_id", "orders.user_id"]
                boolean isAllowed = allowedLower.contains(columnLower) ||
                                   allowedLower.stream().anyMatch(allowed -> 
                                       allowed.endsWith("." + columnLower) ||
                                       columnLower.endsWith("." + allowed));
                
                if (!isAllowed) {
                    issues.add(String.format(
                        "❌ 列名 '%s' 不在 Schema 白名单中，可能是大模型幻觉",
                        column
                    ));
                }
            }
            
            if (!issues.isEmpty()) {
                log.warn("[SQLValidation] 发现 {} 个非法列名: {}", issues.size(), issues);
            }
            
        } catch (Exception e) {
            log.warn("[SQLValidation] 列名白名单校验失败: {}", e.getMessage());
            // 不阻断执行，仅记录警告
        }
        
        return issues;
    }
    
    // ==================== 辅助方法 ====================
    
    /**
     * 提取友好的错误消息
     */
    private String extractErrorMessage(JSQLParserException e, String sql) {
        String message = e.getMessage();
        
        // 尝试提取行号和列号
        if (message.contains("Encountered")) {
            // JSqlParser 典型错误格式
            return String.format("SQL 语法错误: %s", message);
        }
        
        return String.format("SQL 解析失败: %s", message);
    }
    
    /**
     * 检查是否有聚合函数
     */
    private boolean hasAggregateFunction(PlainSelect select) {
        // 简化实现：检查 SELECT 项中是否包含常见聚合函数
        String selectString = select.toString().toUpperCase();
        return selectString.contains("COUNT(") || 
               selectString.contains("SUM(") || 
               selectString.contains("AVG(") || 
               selectString.contains("MAX(") || 
               selectString.contains("MIN(");
    }
    
    /**
     * 提取 GROUP BY 字段
     */
    private Set<String> extractGroupByColumns(GroupByElement groupBy) {
        Set<String> columns = new HashSet<>();
        
        if (groupBy.getGroupByExpressions() != null) {
            for (Expression expr : groupBy.getGroupByExpressions()) {
                if (expr instanceof Column) {
                    columns.add(((Column) expr).getColumnName().toLowerCase());
                } else {
                    columns.add(expr.toString().toLowerCase());
                }
            }
        }
        
        return columns;
    }
    
    /**
     * 提取 SELECT 中的非聚合字段
     */
    private List<String> extractNonAggregateColumns(PlainSelect select) {
        List<String> columns = new ArrayList<>();
        
        if (select.getSelectItems() != null) {
            for (SelectItem item : select.getSelectItems()) {
                String itemStr = item.toString();
                
                // 跳过聚合函数
                if (itemStr.toUpperCase().matches(".*(COUNT|SUM|AVG|MAX|MIN)\\s*\\(.*")) {
                    continue;
                }
                
                // 提取字段名
                if (item instanceof SelectExpressionItem) {
                    SelectExpressionItem sei = (SelectExpressionItem) item;
                    Expression expr = sei.getExpression();
                    
                    if (expr instanceof Column) {
                        columns.add(((Column) expr).getColumnName());
                    } else {
                        // ✅ 关键修复：提取表达式本身（不含 AS 别名）
                        String exprStr = expr.toString().trim();
                        columns.add(exprStr);
                    }
                }
            }
        }
        
        return columns;
    }
    
    /**
     * 提取 JOIN 表名
     */
    private String extractJoinTableName(Join join) {
        if (join.getRightItem() != null) {
            return join.getRightItem().toString();
        }
        return "未知表";
    }
    
    /**
     * 检查 WHERE 中是否有连接条件（简化版）
     */
    private boolean hasJoinCondition(Expression where) {
        String whereStr = where.toString().toUpperCase();
        // 简单启发式：检查是否有 "=" 且涉及不同表的字段
        return whereStr.contains("=") && whereStr.contains(".");
    }
    
    /**
     * ✅ 新增：提取 SQL 中使用的所有列名
     */
    private Set<String> extractAllColumns(PlainSelect select) {
        Set<String> columns = new HashSet<>();
        
        // 1. 提取 SELECT 中的列（✅ 关键修复：跳过 AS 别名）
        if (select.getSelectItems() != null) {
            for (SelectItem item : select.getSelectItems()) {
                if (item instanceof SelectExpressionItem) {
                    SelectExpressionItem sei = (SelectExpressionItem) item;
                    Expression expr = sei.getExpression();
                    
                    // ✅ 只提取真实列名，跳过聚合函数和表达式
                    if (expr instanceof Column) {
                        Column col = (Column) expr;
                        String columnName = col.getColumnName();
                        if (col.getTable() != null) {
                            columnName = col.getTable().getName() + "." + columnName;
                        }
                        columns.add(columnName);
                    }
                    // ⚠️ 注意：不提取 Function/Aggregate 等表达式，因为它们是计算结果，不是真实列
                }
            }
        }
        
        // 2. 提取 WHERE 中的列（简化版，只处理简单情况）
        if (select.getWhere() != null) {
            String whereStr = select.getWhere().toString();
            // 简单正则提取表名.列名格式
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                "\\b([a-zA-Z_][a-zA-Z0-9_]*)\\.([a-zA-Z_][a-zA-Z0-9_]*)\\b"
            );
            java.util.regex.Matcher matcher = pattern.matcher(whereStr);
            while (matcher.find()) {
                columns.add(matcher.group(1) + "." + matcher.group(2));
            }
        }
        
        // 3. 提取 ORDER BY 中的列（✅ 关键修复：跳过字符串常量/别名）
        if (select.getOrderByElements() != null) {
            for (OrderByElement orderBy : select.getOrderByElements()) {
                Expression expr = orderBy.getExpression();
                
                // ✅ 只提取真实列名，跳过字符串常量（如 '销售额'）
                if (expr instanceof Column) {
                    Column col = (Column) expr;
                    String columnName = col.getColumnName();
                    
                    // ⚠️ 如果列名是带引号的字符串（如 `销售额` 或 '销售额'），跳过
                    if (columnName.startsWith("`") || columnName.startsWith("'") || 
                        columnName.startsWith("\"")) {
                        log.debug("[SQLValidation] 跳过 ORDER BY 中的别名: {}", columnName);
                        continue;
                    }
                    
                    if (col.getTable() != null) {
                        columnName = col.getTable().getName() + "." + columnName;
                    }
                    columns.add(columnName);
                }
                // ⚠️ 注意：不提取 Function/Aggregate，因为它们是计算结果
            }
        }
        
        return columns;
    }
    
    /**
     * 生成语法修复建议
     */
    private List<String> generateSyntaxFixSuggestions(String errorMessage, String sql) {
        List<String> suggestions = new ArrayList<>();
        
        if (errorMessage.contains("Encountered") && errorMessage.contains("FROM")) {
            suggestions.add("检查 FROM 子句前是否有遗漏的关键字或符号");
        }
        
        if (errorMessage.contains("WHERE")) {
            suggestions.add("检查 WHERE 子句语法是否正确");
        }
        
        if (errorMessage.contains("JOIN")) {
            suggestions.add("检查 JOIN 语法：应为 JOIN table_name ON condition");
        }
        
        if (errorMessage.contains("GROUP BY")) {
            suggestions.add("检查 GROUP BY 子句位置是否正确（应在 WHERE 之后，ORDER BY 之前）");
        }
        
        suggestions.add("可以尝试用自然语言重新描述查询需求，让系统重新生成 SQL");
        
        return suggestions;
    }
    
    // ==================== 内部类 ====================
    
    @Data
    public static class ValidationResult {
        private boolean valid;
        private String errorMessage;
        private Statement parsedStatement;
        
        public static ValidationResult success(Statement statement) {
            ValidationResult result = new ValidationResult();
            result.valid = true;
            result.parsedStatement = statement;
            return result;
        }
        
        public static ValidationResult failure(String errorMessage, Statement statement) {
            ValidationResult result = new ValidationResult();
            result.valid = false;
            result.errorMessage = errorMessage;
            result.parsedStatement = statement;
            return result;
        }
    }
    
    @Data
    public static class ValidationReport {
        private String sql;
        private boolean syntaxValid;
        private String syntaxError;
        private List<String> aggregationIssues = new ArrayList<>();
        private List<String> joinIssues = new ArrayList<>();
        private boolean overallValid;
        private List<String> suggestions = new ArrayList<>();
    }
}
