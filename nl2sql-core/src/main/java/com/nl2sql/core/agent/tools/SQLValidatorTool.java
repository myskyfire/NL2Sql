package com.nl2sql.core.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nl2sql.common.util.MarkdownUtils;
import com.nl2sql.core.agent.tool.BaseToolAdapter;
import com.nl2sql.core.agent.tool.ToolContext;
import com.nl2sql.core.agent.validation.SQLValidationService;
import com.nl2sql.core.cache.MetadataCacheService;
import com.nl2sql.core.llm.ModelRouterService;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * SQL验证与修正工具 - 提供SQL语法、语义验证及自动修正能力
 * ✅ 从NL2SQLTool拆分出的原子能力
 */
@Slf4j
@Component
public class SQLValidatorTool extends BaseToolAdapter {
    
    @Autowired
    private JdbcTemplate jdbcTemplate;
    
    @Autowired
    private ModelRouterService modelRouter;
    
    @Autowired
    private SQLValidationService sqlValidationService;
    
    @Autowired
    private MetadataCacheService metadataCacheService;
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    @Override
    public String getName() { return "sql_validator"; }
    
    @Override
    public String getDescription() { return "对生成的SQL进行多维度验证（语法、聚合、JOIN、列名幻觉），并自动修正发现的问题。支持最多3次重试修正。"; }
    
    @Override
    public Map<String, Object> getParameterSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        Map<String, Object> props = new HashMap<>();
        props.put("sql", Map.of("type", "string", "description", "待验证的SQL语句"));
        props.put("question", Map.of("type", "string", "description", "用户原始问题"));
        props.put("datasourceId", Map.of("type", "integer", "description", "数据源ID"));
        props.put("schemaInfo", Map.of("type", "string", "description", "表结构信息"));
        props.put("relationshipInfo", Map.of("type", "string", "description", "表关联关系"));
        props.put("maxRetries", Map.of("type", "integer", "description", "最大重试次数", "default", 3));
        schema.put("properties", props);
        schema.put("required", Arrays.asList("sql", "question", "datasourceId"));
        return schema;
    }
    
    @Override
    public String getApplicableScenarios() { return "SQL生成后需要验证正确性，或执行失败需要自动修正时"; }
    
    @Override
    public String getInapplicableScenarios() { return "简单查询无需验证的场景"; }
    
    @Override
    protected void validateParameters(ToolContext context) {
        parameterValidator
            .required("sql", context.getParameter("sql"))
            .required("question", context.getParameter("question"))
            .required("datasourceId", context.getParameter("datasourceId"))
            .throwIfHasErrors();
    }
    
    @Override
    protected Object doExecute(ToolContext context) throws Exception {
        String sql = context.getRequiredParameter("sql");
        String question = context.getRequiredParameter("question");
        Long datasourceId = context.getRequiredParameter("datasourceId");
        String schemaInfo = context.getParameter("schemaInfo");
        String relationshipInfo = context.getParameter("relationshipInfo");
        Integer maxRetries = context.getParameter("maxRetries");
        
        if (schemaInfo == null) schemaInfo = "";
        if (relationshipInfo == null) relationshipInfo = "";
        if (maxRetries == null) maxRetries = 3;
        
        String correctedSql = validateAndCorrectSQL(sql, question, datasourceId, schemaInfo, relationshipInfo, maxRetries);
        
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("originalSql", sql);
        result.put("correctedSql", correctedSql);
        result.put("wasModified", !sql.equals(correctedSql));
        
        return result;
    }
    
    /**
     * SQL验证与Self-Correction
     */
    private String validateAndCorrectSQL(String sql, String question, Long datasourceId, 
                                         String schemaInfo, String relationshipInfo, int maxRetries) {
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            // Schema白名单校验（防止大模型幻觉列名）
            Set<String> allowedColumns = extractColumnWhitelist(sql, datasourceId);
            if (!allowedColumns.isEmpty()) {
                List<String> whitelistIssues = sqlValidationService.validateColumnWhitelist(sql, allowedColumns);
                if (!whitelistIssues.isEmpty()) {
                    log.warn("[SQLValidator] ⚠️ 检测到幻觉列名 (attempt={}): {}", attempt, whitelistIssues);
                    
                    if (attempt >= maxRetries) {
                        log.warn("[SQLValidator] 达到最大重试次数，保留原SQL（可能存在幻觉列名）");
                        break;
                    }
                    
                    log.info("[SQLValidator] 尝试修正幻觉列名...");
                    sql = attemptHallucinationCorrection(sql, question, schemaInfo, relationshipInfo, whitelistIssues);
                    continue;
                }
            }
            
            // 综合验证
            SQLValidationService.ValidationReport report = sqlValidationService.comprehensiveValidate(sql);
            
            if (report.isOverallValid()) {
                log.info("[SQLValidator] SQL验证通过 (attempt={})", attempt);
                return sql;
            }
            
            log.warn("[SQLValidator] SQL验证失败 (attempt={}): syntaxValid={}, issues={}", 
                attempt, report.isSyntaxValid(), 
                report.getAggregationIssues().size() + report.getJoinIssues().size());
            
            // 语法错误修正
            if (!report.isSyntaxValid()) {
                log.info("[SQLValidator] 尝试修正语法错误: {}", report.getSyntaxError());
                sql = attemptSyntaxCorrection(sql, report.getSyntaxError(), question, schemaInfo, relationshipInfo);
                continue;
            }
            
            // 聚合或JOIN问题修正
            if (!report.getAggregationIssues().isEmpty() || !report.getJoinIssues().isEmpty()) {
                StringBuilder warning = new StringBuilder();
                warning.append("⚠️ SQL潜在问题:\n");
                
                for (String issue : report.getAggregationIssues()) {
                    warning.append("- ").append(issue).append("\n");
                }
                for (String issue : report.getJoinIssues()) {
                    warning.append("- ").append(issue).append("\n");
                }
                
                log.warn("[SQLValidator] {}", warning.toString());
                
                if (attempt < maxRetries) {
                    log.info("[SQLValidator] 尝试修正聚合/JOIN问题...");
                    sql = attemptAggregationCorrection(sql, question, schemaInfo, relationshipInfo, warning.toString());
                    continue;
                } else {
                    log.warn("[SQLValidator] 达到最大重试次数，返回原SQL（可能存在风险）");
                    break;
                }
            }
        }
        
        return sql;
    }
    
    /**
     * 从SQL中提取列名白名单
     */
    private Set<String> extractColumnWhitelist(String sql, Long datasourceId) {
        try {
            net.sf.jsqlparser.statement.Statement statement = 
                net.sf.jsqlparser.parser.CCJSqlParserUtil.parse(sql);
            
            if (!(statement instanceof net.sf.jsqlparser.statement.select.Select)) {
                return Collections.emptySet();
            }
            
            net.sf.jsqlparser.statement.select.PlainSelect plainSelect = 
                (net.sf.jsqlparser.statement.select.PlainSelect) 
                ((net.sf.jsqlparser.statement.select.Select) statement).getSelectBody();
            
            Set<String> tableNames = new HashSet<>();
            
            if (plainSelect.getFromItem() != null) {
                String fromTable = plainSelect.getFromItem().toString().toLowerCase();
                if (fromTable.contains(" ")) {
                    fromTable = fromTable.split("\\s+")[0];
                }
                tableNames.add(fromTable);
            }
            
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
            
            // 查缓存
            Set<String> cachedColumns = metadataCacheService.getColumnWhitelist(datasourceId, tableNames);
            if (cachedColumns != null) {
                log.debug("[SQLValidator] 列名白名单缓存命中: {} 个列", cachedColumns.size());
                return cachedColumns;
            }
            
            // 批量查询列名
            String placeholders = tableNames.stream()
                .map(t -> "?")
                .collect(Collectors.joining(", "));
            
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
            
            // 存入缓存
            metadataCacheService.putColumnWhitelist(datasourceId, tableNames, allowedColumns);
            
            log.debug("[SQLValidator] 提取到 {} 个允许的列名（已缓存）", allowedColumns.size());
            return allowedColumns;
            
        } catch (Exception e) {
            log.warn("[SQLValidator] 提取列名白名单失败: {}", e.getMessage());
            return Collections.emptySet();
        }
    }
    
    /**
     * 修正幻觉列名
     */
    private String attemptHallucinationCorrection(String sql, String question, String schemaInfo, 
                                                  String relationshipInfo, List<String> issues) {
        try {
            StringBuilder issueDesc = new StringBuilder();
            for (String issue : issues) {
                issueDesc.append("- ").append(issue).append("\n");
            }
            
            String correctionPrompt = String.format(
                "你是一个MySQL SQL专家。以下SQL语句包含不存在的列名（大模型幻觉），请修正。\n\n" +
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
                question, schemaInfo,
                relationshipInfo.isEmpty() ? "" : relationshipInfo + "\n\n",
                sql, issueDesc.toString()
            );
            
            String correctedSql = modelRouter.smartGenerateSQL(correctionPrompt, question);
            return MarkdownUtils.cleanSQL(correctedSql);
            
        } catch (Exception e) {
            log.error("[SQLValidator] 幻觉列名修正失败", e);
            return sql;
        }
    }
    
    /**
     * 修正语法错误
     */
    private String attemptSyntaxCorrection(String failedSql, String errorMessage, 
                                           String question, String schemaInfo, String relationshipInfo) {
        try {
            String correctionPrompt = String.format(
                "你是一个MySQL SQL专家。以下SQL语句存在语法错误，请修正。\n\n" +
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
                question, schemaInfo,
                relationshipInfo.isEmpty() ? "" : relationshipInfo + "\n\n",
                failedSql, errorMessage
            );
            
            String correctedSql = modelRouter.smartGenerateSQL(correctionPrompt, question);
            return MarkdownUtils.cleanSQL(correctedSql);
            
        } catch (Exception e) {
            log.error("[SQLValidator] 语法修正失败", e);
            return failedSql;
        }
    }
    
    /**
     * 修正聚合/JOIN问题
     */
    private String attemptAggregationCorrection(String sql, String question, String schemaInfo, 
                                                String relationshipInfo, String issues) {
        try {
            String correctionPrompt = String.format(
                "你是一个MySQL SQL专家。以下SQL语句存在逻辑问题，请修正。\n\n" +
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
                "   - 正确：SELECT DATE_FORMAT(o.created_at, '%%Y-%%m-%%d') AS '订单日期' ... GROUP BY DATE_FORMAT(o.created_at, '%%Y-%%m-%%d')\n" +
                "4. 确保 SELECT 和 GROUP BY 使用相同的表达式",
                question, schemaInfo,
                relationshipInfo.isEmpty() ? "" : relationshipInfo + "\n\n",
                sql, issues
            );
            
            String correctedSql = modelRouter.smartGenerateSQL(correctionPrompt, question);
            return MarkdownUtils.cleanSQL(correctedSql);
            
        } catch (Exception e) {
            log.error("[SQLValidator] 聚合/JOIN修正失败", e);
            return sql;
        }
    }
    
    @Tool("验证SQL语句的正确性并自动修正问题。输入SQL、用户问题、数据源ID等信息，返回验证结果和修正后的SQL")
    public String validateAndCorrectSQL(String sql, String question, Long datasourceId, 
                                       String schemaInfo, String relationshipInfo, Integer maxRetries) {
        try {
            ToolContext context = ToolContext.builder()
                .parameters(new HashMap<String, Object>() {{
                    put("sql", sql);
                    put("question", question);
                    put("datasourceId", datasourceId);
                    put("schemaInfo", schemaInfo != null ? schemaInfo : "");
                    put("relationshipInfo", relationshipInfo != null ? relationshipInfo : "");
                    put("maxRetries", maxRetries != null ? maxRetries : 3);
                }})
                .build();
            
            com.nl2sql.core.agent.tool.ToolResult result = execute(context);
            
            if (result.isSuccess()) {
                return objectMapper.writeValueAsString(result.getData());
            } else {
                Map<String, Object> error = new HashMap<>();
                error.put("success", false);
                error.put("error", result.getErrorMessage());
                return objectMapper.writeValueAsString(error);
            }
        } catch (Exception e) {
            log.error("[SQLValidator] 执行失败", e);
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("error", e.getMessage());
            try {
                return objectMapper.writeValueAsString(error);
            } catch (Exception ex) {
                return "{\"success\":false,\"error\":\"序列化失败\"}";
            }
        }
    }
}
