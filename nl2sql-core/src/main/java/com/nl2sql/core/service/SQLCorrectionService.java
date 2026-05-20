package com.nl2sql.core.service;

import com.nl2sql.common.util.MarkdownUtils;
import com.nl2sql.core.agent.validation.SQLValidationService;
import com.nl2sql.core.cache.MetadataCacheService;
import com.nl2sql.core.llm.ModelRouterService;
import com.nl2sql.core.mapper.MetadataMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * SQL修正服务 - 负责SQL验证与Self-Correction
 * 
 * 核心能力：
 * 1. Schema白名单校验（防止大模型幻觉列名）
 * 2. 语法错误修正
 * 3. 聚合/JOIN问题修正
 * 4. 多轮迭代修正（最多3次重试）
 */
@Slf4j
@Service
public class SQLCorrectionService {
    
    @Autowired
    private JdbcTemplate jdbcTemplate;
    
    @Autowired
    private SQLValidationService sqlValidationService;
    
    @Autowired
    private ModelRouterService modelRouter;
    
    @Autowired
    private MetadataCacheService metadataCacheService;
    
    @Autowired
    private MetadataMapper metadataMapper;
    
    /**
     * SQL 验证与 Self-Correction
     * 
     * @param sql 原始 SQL
     * @param question 用户问题
     * @param datasourceId 数据源ID
     * @param schemaInfo 表结构信息
     * @param relationshipInfo 关联关系
     * @param maxRetries 最大重试次数
     * @return 修正后的 SQL
     */
    public String validateAndCorrectSQL(String sql, String question, Long datasourceId, 
                                        String schemaInfo, String relationshipInfo, int maxRetries) {
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            // ✅ 新增：Schema白名单校验（防止大模型幻觉列名）
            Set<String> allowedColumns = extractColumnWhitelist(sql, datasourceId);
            if (!allowedColumns.isEmpty()) {
                List<String> whitelistIssues = sqlValidationService.validateColumnWhitelist(sql, allowedColumns);
                if (!whitelistIssues.isEmpty()) {
                    log.warn("[SQLCorrection] ⚠️ 检测到幻觉列名 (attempt={}): {}", attempt, whitelistIssues);
                    
                    // 如果是最后一次尝试，记录警告但继续执行
                    if (attempt >= maxRetries) {
                        log.warn("[SQLCorrection] 达到最大重试次数，保留原SQL（可能存在幻觉列名）");
                        break;
                    }
                    
                    // 触发重新生成
                    log.info("[SQLCorrection] 尝试修正幻觉列名...");
                    sql = attemptHallucinationCorrection(sql, question, schemaInfo, relationshipInfo, whitelistIssues, datasourceId);
                    continue; // 重新验证
                }
            }
            
            // 1. 综合验证
            SQLValidationService.ValidationReport report = sqlValidationService.comprehensiveValidate(sql);
            
            if (report.isOverallValid()) {
                log.info("[SQLCorrection] SQL验证通过 (attempt={})", attempt);
                return sql;
            }
            
            log.warn("[SQLCorrection] SQL验证失败 (attempt={}): syntaxValid={}, issues={}", 
                attempt, report.isSyntaxValid(), 
                report.getAggregationIssues().size() + report.getJoinIssues().size());
            
            // 2. 如果是语法错误，尝试修正
            if (!report.isSyntaxValid()) {
                log.info("[SQLCorrection] 尝试修正语法错误: {}", report.getSyntaxError());
                sql = attemptSyntaxCorrection(sql, report.getSyntaxError(), question, schemaInfo, relationshipInfo, datasourceId);
                continue;
            }
            
            // 3. 如果是聚合或JOIN问题，尝试修正
            if (!report.getAggregationIssues().isEmpty() || !report.getJoinIssues().isEmpty()) {
                StringBuilder warning = new StringBuilder();
                warning.append("⚠️ SQL潜在问题:\n");
                
                for (String issue : report.getAggregationIssues()) {
                    warning.append("- ").append(issue).append("\n");
                }
                for (String issue : report.getJoinIssues()) {
                    warning.append("- ").append(issue).append("\n");
                }
                
                log.warn("[SQLCorrection] {}", warning.toString());
                
                // ✅ 关键修复：如果是严重问题（如 GROUP BY 不匹配），尝试修正
                if (attempt < maxRetries) {
                    log.info("[SQLCorrection] 尝试修正聚合/JOIN问题...");
                    sql = attemptAggregationCorrection(sql, question, schemaInfo, relationshipInfo, warning.toString(), datasourceId);
                    continue; // 重新验证
                } else {
                    log.warn("[SQLCorrection] 达到最大重试次数，返回原SQL（可能存在风险）");
                    break;
                }
            }
        }
        
        return sql;
    }
    
    /**
     * 从 SQL 中提取表名，并查询对应的列名白名单（带缓存）
     */
    public Set<String> extractColumnWhitelist(String sql, Long datasourceId) {
        try {
            // 1. 使用 JSqlParser 提取 SQL 中使用的表名
            net.sf.jsqlparser.statement.Statement statement = 
                net.sf.jsqlparser.parser.CCJSqlParserUtil.parse(sql);
            
            if (!(statement instanceof net.sf.jsqlparser.statement.select.Select)) {
                return Collections.emptySet(); // 非 SELECT 语句
            }
            
            net.sf.jsqlparser.statement.select.Select selectStmt = 
                (net.sf.jsqlparser.statement.select.Select) statement;
            net.sf.jsqlparser.statement.select.SelectBody selectBody = selectStmt.getSelectBody();
            
            if (!(selectBody instanceof net.sf.jsqlparser.statement.select.PlainSelect)) {
                return Collections.emptySet();
            }
            
            net.sf.jsqlparser.statement.select.PlainSelect plainSelect = 
                (net.sf.jsqlparser.statement.select.PlainSelect) selectBody;
            
            // 2. 提取所有表名（FROM + JOIN）
            Set<String> tableNames = new HashSet<>();
            
            // FROM 表
            if (plainSelect.getFromItem() != null) {
                String fromTable = plainSelect.getFromItem().toString().toLowerCase();
                // 去除别名
                if (fromTable.contains(" ")) {
                    fromTable = fromTable.split("\\s+")[0];
                }
                tableNames.add(fromTable);
            }
            
            // JOIN 表
            if (plainSelect.getJoins() != null) {
                for (net.sf.jsqlparser.statement.select.Join join : plainSelect.getJoins()) {
                    if (join.getRightItem() != null) {
                        String joinTable = join.getRightItem().toString().toLowerCase();
                        if (joinTable.contains(" ")) {
                            joinTable = joinTable.split("\\s+")[0];
                        }
                        tableNames.add(joinTable);
                    }
                }
            }
            
            if (tableNames.isEmpty()) {
                return Collections.emptySet();
            }
            
            log.debug("[SQLCorrection] 从 SQL 中提取到表名: {}", tableNames);
            
            // ✅ 关键优化：先查缓存
            Set<String> cachedColumns = metadataCacheService.getColumnWhitelist(datasourceId, tableNames);
            if (cachedColumns != null) {
                log.debug("[SQLCorrection] 列名白名单缓存命中: {} 个列", cachedColumns.size());
                return cachedColumns;
            }
            
            // 3. 缓存未命中，批量查询这些表的所有列名
            String placeholders = tableNames.stream()
                .map(t -> "?")
                .collect(java.util.stream.Collectors.joining(", "));
            
            String querySql = String.format(
                "SELECT DISTINCT column_name FROM column_metadata WHERE datasource_id = ? AND table_name IN (%s)",
                placeholders
            );
            
            Object[] params = new Object[tableNames.size() + 1];
            params[0] = datasourceId;
            int i = 1;
            for (String tableName : tableNames) {
                params[i++] = tableName;
            }
            
            List<Map<String, Object>> columns = jdbcTemplate.queryForList(querySql, params);
            
            Set<String> allowedColumns = new HashSet<>();
            for (Map<String, Object> col : columns) {
                String columnName = (String) col.get("column_name");
                if (columnName != null) {
                    allowedColumns.add(columnName.toLowerCase());
                }
            }
            
            // ✅ 存入缓存
            metadataCacheService.putColumnWhitelist(datasourceId, tableNames, allowedColumns);
            
            log.debug("[SQLCorrection] 提取到 {} 个允许的列名（已缓存）", allowedColumns.size());
            
            return allowedColumns;
            
        } catch (Exception e) {
            log.warn("[SQLCorrection] 提取列名白名单失败: {}", e.getMessage());
            return Collections.emptySet();
        }
    }
    
    /**
     * 尝试修正幻觉列名
     */
    public String attemptHallucinationCorrection(String sql, String question, String schemaInfo, 
                                                 String relationshipInfo, List<String> issues, Long datasourceId) {
        try {
            String dbType = getDbType(datasourceId);
            
            StringBuilder issueDesc = new StringBuilder();
            for (String issue : issues) {
                issueDesc.append("- ").append(issue).append("\n");
            }
            
            String correctionPrompt = String.format(
                "你是" + dbType.toUpperCase() + " SQL专家。以下SQL语句包含不存在的列名（大模型幻觉），请修正。\n\n" +
                "数据库类型：" + dbType.toUpperCase() + "\n\n" +
                "用户问题：%s\n\n" +
                "数据库表结构：\n%s\n\n" +
                "%s" +
                "有问题的SQL:\n%s\n\n" +
                "检测到的问题:\n%s\n\n" +
                "要求：\n" +
                "1. 只输出修正后的SQL语句\n" +
                "2. 不要包含```sql或其他标记\n" +
                "3. **严格基于上述表结构中的列名**，不要臆造不存在的列\n" +
                "4. 如果不确定列名，可以使用表中已有的其他相关字段\n" +
                "5. 保持原有查询意图不变",
                question,
                schemaInfo,
                relationshipInfo.isEmpty() ? "" : relationshipInfo + "\n\n",
                sql,
                issueDesc.toString()
            );
            
            String correctedSql = modelRouter.smartGenerateSQL(correctionPrompt, question);
            correctedSql = cleanSQL(correctedSql);
            
            log.info("[SQLCorrection] 幻觉列名修正后SQL: {}", correctedSql);
            return correctedSql;
            
        } catch (Exception e) {
            log.error("[SQLCorrection] 幻觉列名修正失败", e);
            return sql; // 返回原SQL
        }
    }
    
    /**
     * 尝试修正语法错误
     */
    public String attemptSyntaxCorrection(String failedSql, String errorMessage, 
                                          String question, String schemaInfo, String relationshipInfo, Long datasourceId) {
        try {
            String dbType = getDbType(datasourceId);
            
            String correctionPrompt = String.format(
                "你是" + dbType.toUpperCase() + " SQL专家。以下SQL语句存在语法错误，请修正。\n\n" +
                "数据库类型：" + dbType.toUpperCase() + "\n\n" +
                "用户问题：%s\n\n" +
                "数据库表结构：\n%s\n\n" +
                "%s" +
                "失败的SQL:\n%s\n\n" +
                "错误信息:\n%s\n\n" +
                "要求：\n" +
                "1. 只输出修正后的SQL语句\n" +
                "2. 不要包含```sql或其他标记\n" +
                "3. 保持原有查询意图不变\n" +
                "4. 仔细检查括号、关键字、字段名是否正确",
                question,
                schemaInfo,
                relationshipInfo.isEmpty() ? "" : relationshipInfo + "\n\n",
                failedSql,
                errorMessage
            );
            
            String correctedSql = modelRouter.smartGenerateSQL(correctionPrompt, question);
            correctedSql = cleanSQL(correctedSql);
            
            // ✅ 关键校验：检查是否为 LLM 错误信息
            if (correctedSql.contains("LLM调用失败") || 
                correctedSql.contains("API调用失败") || 
                correctedSql.contains("request timed out") ||
                correctedSql.contains("timeout")) {
                log.error("[SQLCorrection] LLM 返回错误信息，非有效 SQL: {}", correctedSql);
                return failedSql; // 返回原 SQL，避免死循环
            }
            
            log.info("[SQLCorrection] 修正后SQL: {}", correctedSql);
            return correctedSql;
            
        } catch (Exception e) {
            log.error("[SQLCorrection] 语法修正失败", e);
            return failedSql; // 返回原SQL
        }
    }
    
    /**
     * 尝试修正聚合/JOIN问题
     */
    public String attemptAggregationCorrection(String sql, String question, String schemaInfo, 
                                               String relationshipInfo, String issues, Long datasourceId) {
        try {
            String dbType = getDbType(datasourceId);
            
            String correctionPrompt = String.format(
                "你是" + dbType.toUpperCase() + " SQL专家。以下SQL语句存在逻辑问题，请修正。\n\n" +
                "数据库类型：" + dbType.toUpperCase() + "\n\n" +
                "用户问题：%s\n\n" +
                "数据库表结构：\n%s\n\n" +
                "%s" +
                "有问题的SQL:\n%s\n\n" +
                "检测到的问题:\n%s\n\n" +
                "要求：\n" +
                "1. 只输出修正后的SQL语句\n" +
                "2. 不要包含```sql或其他标记\n" +
                "3. **重要：SELECT 中的非聚合字段必须出现在 GROUP BY 中**\n" +
                "   - 错误：SELECT o.created_at ... GROUP BY DATE_FORMAT(o.created_at, ...)\n" +
                "   - 正确：SELECT和GROUP BY使用相同的原始表达式（如日期格式化函数）\n" +
                "4. 确保 SELECT 和 GROUP BY 使用相同的表达式",
                question,
                schemaInfo,
                relationshipInfo.isEmpty() ? "" : relationshipInfo + "\n\n",
                sql,
                issues
            );
            
            String correctedSql = modelRouter.smartGenerateSQL(correctionPrompt, question);
            correctedSql = cleanSQL(correctedSql);
            
            // ✅ 关键校验：检查是否为 LLM 错误信息
            if (correctedSql.contains("LLM调用失败") || 
                correctedSql.contains("API调用失败") || 
                correctedSql.contains("request timed out") ||
                correctedSql.contains("timeout")) {
                log.error("[SQLCorrection] LLM 返回错误信息，非有效 SQL: {}", correctedSql);
                return sql; // 返回原 SQL
            }
            
            log.info("[SQLCorrection] 聚合/JOIN修正后SQL: {}", correctedSql);
            return correctedSql;
            
        } catch (Exception e) {
            log.error("[SQLCorrection] 聚合/JOIN修正失败", e);
            return sql; // 返回原SQL
        }
    }
    
    /**
     * 清洗SQL（移除Markdown标记）
     */
    public String cleanSQL(String sql) {
        return MarkdownUtils.cleanSQL(sql);
    }
    
    // ==================== 向后兼容方法（保持API稳定性）====================
    
    /**
     * SQL纠错结果（兼容旧版API）
     */
    @lombok.Data
    public static class CorrectionResult {
        private boolean success;
        private String correctedSQL;
        private String originalError;
        private java.util.List<String> suggestions;
        private int retryCount;
        
        public CorrectionResult() {
            this.suggestions = new java.util.ArrayList<>();
            this.retryCount = 0;
        }
    }
    
    /**
     * 尝试自动修正SQL错误（兼容旧版API）
     * 
     * @param sql 原始SQL
     * @param error 错误信息
     * @param maxRetries 最大重试次数
     * @return 修正结果
     */
    public CorrectionResult autoCorrect(String sql, String error, int maxRetries) {
        CorrectionResult result = new CorrectionResult();
        result.setOriginalError(error);
        result.setRetryCount(1);
        
        log.info("[SQLCorrection] 开始SQL纠错: sql={}, error={}", sql, error);
        
        // 优先尝试语法修正
        String correctedSql = attemptSyntaxCorrection(sql, error, "", "", "", null);
        
        if (correctedSql != null && !correctedSql.trim().isEmpty() && !correctedSql.equals(sql)) {
            result.setSuccess(true);
            result.setCorrectedSQL(correctedSql);
            log.info("[SQLCorrection] ✅ 语法修正成功: {}", correctedSql);
            return result;
        }
        
        // 尝试幻觉列名修正
        correctedSql = attemptHallucinationCorrection(sql, "", "", "", java.util.Collections.singletonList(error), null);
        
        if (correctedSql != null && !correctedSql.trim().isEmpty() && !correctedSql.equals(sql)) {
            result.setSuccess(true);
            result.setCorrectedSQL(correctedSql);
            log.info("[SQLCorrection] ✅ 幻觉列名修正成功: {}", correctedSql);
            return result;
        }
        
        // 修正失败
        result.setSuccess(false);
        result.setCorrectedSQL(sql);
        result.getSuggestions().add("自动修正失败，建议手动检查SQL");
        log.warn("[SQLCorrection] ❌ SQL纠错失败");
        
        return result;
    }
    
    /**
     * 根据数据源ID获取数据库类型
     */
    private String getDbType(Long datasourceId) {
        if (datasourceId == null) {
            return "MySQL";
        }
        try {
            String dbType = metadataMapper.getDbType(datasourceId);
            return dbType != null ? dbType : "MySQL";
        } catch (Exception e) {
            log.warn("[SQLCorrection] 获取数据库类型失败，默认使用MySQL: {}", e.getMessage());
            return "MySQL";
        }
    }
}
