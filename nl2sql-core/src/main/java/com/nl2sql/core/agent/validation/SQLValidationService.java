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
            log.debug("[SQLValidation] 语法校验通过: {}", sql.substring(0, Math.min(100, sql.length())));
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
            
            // 如果没有 GROUP BY，但 SELECT 中有聚合函数，需要警告
            if (groupBy == null && hasAggregateFunction(plainSelect)) {
                issues.add("⚠️ 检测到聚合函数但未使用 GROUP BY，可能导致意外结果");
                return issues;
            }
            
            if (groupBy != null) {
                // 提取 GROUP BY 字段
                Set<String> groupByColumns = extractGroupByColumns(groupBy);
                
                // 提取 SELECT 中的非聚合字段
                List<String> nonAggregateColumns = extractNonAggregateColumns(plainSelect);
                
                // 检查是否有非聚合字段不在 GROUP BY 中
                for (String column : nonAggregateColumns) {
                    if (!groupByColumns.contains(column.toLowerCase())) {
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
                        columns.add(itemStr);
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
