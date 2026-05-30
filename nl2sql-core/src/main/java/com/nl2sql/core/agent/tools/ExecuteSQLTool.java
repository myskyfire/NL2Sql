package com.nl2sql.core.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nl2sql.core.agent.tool.BaseTool;
import com.nl2sql.core.agent.tool.ToolContext;
import com.nl2sql.core.agent.tool.ToolResult;
import com.nl2sql.core.error.ErrorClassifier;
import com.nl2sql.core.error.ErrorType;
import com.nl2sql.core.validation.ParameterValidator;
import com.nl2sql.core.executor.SQLRiskAnalyzer;
import com.nl2sql.core.llm.LLMService;
import com.nl2sql.core.service.NL2SQLService;
import com.nl2sql.core.service.SQLCorrectionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Pattern;

/**
 * SQL 执行 Tool - 原子能力：执行SQL查询并返回结果
 * 
 * ✅ 完全等效于 Groovy 脚本的 executeWithAutoFix + assessSQLRisk 逻辑
 */
@Slf4j
@Component
public class ExecuteSQLTool implements BaseTool {
    
    @Autowired(required = false)
    private JdbcTemplate jdbcTemplate;
    
    @Autowired
    private ParameterValidator parameterValidator;
    
    @Autowired
    private ErrorClassifier errorClassifier;
    
    @Autowired(required = false)
    private SQLRiskAnalyzer riskAnalyzer;
    
    @Autowired(required = false)
    private LLMService llmService;
    
    @Autowired(required = false)
    private NL2SQLService nl2sqlService;
    
    @Autowired(required = false)
    private SQLCorrectionService correctionService;
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    private static final int MAX_RETRIES = 2;  // 最多重试2次
    
    // ==================== BaseTool接口实现 ====================
    
    @Override
    public String getName() {
        return "execute_sql";
    }
    
    @Override
    public String getDescription() {
        return "执行SQL查询并返回结果。支持自动修正（最多2次重试）、风险评估（EXPLAIN+LLM）、离线模式降级。仅支持SELECT语句。";
    }
    
    @Override
    public Map<String, Object> getParameterSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        
        Map<String, Object> properties = new HashMap<>();
        
        Map<String, Object> sqlParam = new HashMap<>();
        sqlParam.put("type", "string");
        sqlParam.put("description", "要执行的SQL查询语句，必须是SELECT语句");
        properties.put("sql", sqlParam);
        
        Map<String, Object> datasourceParam = new HashMap<>();
        datasourceParam.put("type", "integer");
        datasourceParam.put("description", "数据源ID");
        properties.put("datasourceId", datasourceParam);
        
        Map<String, Object> userIdParam = new HashMap<>();
        userIdParam.put("type", "integer");
        userIdParam.put("description", "用户ID（可选）");
        properties.put("userId", userIdParam);
        
        Map<String, Object> usernameParam = new HashMap<>();
        usernameParam.put("type", "string");
        usernameParam.put("description", "用户名（可选）");
        properties.put("username", usernameParam);
        
        Map<String, Object> sqlOnlyParam = new HashMap<>();
        sqlOnlyParam.put("type", "boolean");
        sqlOnlyParam.put("description", "是否仅生成SQL不执行（离线模式），默认false");
        properties.put("sqlOnly", sqlOnlyParam);
        
        schema.put("properties", properties);
        schema.put("required", Arrays.asList("sql", "datasourceId"));
        
        return schema;
    }
    
    @Override
    public String getApplicableScenarios() {
        return "适用于以下场景：\n" +
               "1. 执行SELECT查询获取数据\n" +
               "2. 验证生成的SQL是否正确\n" +
               "3. 预览表数据\n" +
               "4. 高风险SQL自动阻断并请求人工确认";
    }
    
    @Override
    public String getInapplicableScenarios() {
        return "不适用于以下场景：\n" +
               "1. INSERT/UPDATE/DELETE等写操作\n" +
               "2. DDL操作（CREATE/DROP/ALTER）\n" +
               "3. 系统管理命令";
    }
    
    @Override
    public ToolResult execute(ToolContext context) {
        long startTime = System.currentTimeMillis();
        
        try {
            // 1. 参数校验
            validateParameters(context);
            
            String sql = context.getRequiredParameter("sql");
            Long datasourceId = context.getRequiredParameter("datasourceId");
            Long userId = context.getParameter("userId");
            String username = context.getParameter("username");
            Boolean sqlOnly = context.getParameter("sqlOnly");
            if (sqlOnly == null) sqlOnly = false;
            
            log.info("[ExecuteSQLTool] 开始执行SQL: datasourceId={}, sqlOnly={}", datasourceId, sqlOnly);
            
            // 2. 安全检查：只允许SELECT语句
            String upperSQL = sql.trim().toUpperCase();
            if (!upperSQL.startsWith("SELECT")) {
                return ToolResult.error("只允许执行SELECT查询");
            }
            
            // ✅ 等效于 Groovy sqlOnly 模式：仅做静态风险评估，不执行SQL
            if (sqlOnly) {
                log.info("[ExecuteSQLTool] ✅ 离线模式：仅生成SQL，不执行");
                RiskAssessmentResult riskResult = staticRiskAssessment(sql);
                
                Map<String, Object> response = new LinkedHashMap<>();
                response.put("success", true);
                response.put("type", "data");
                response.put("data", new ArrayList<>());
                response.put("rowCount", 0);
                response.put("executionTime", 0.0);
                response.put("sql", sql);
                response.put("datasourceId", datasourceId);
                response.put("sqlOnly", true);
                
                if (riskResult.getOptimizationSuggestion() != null && !riskResult.getOptimizationSuggestion().trim().isEmpty()) {
                    response.put("optimizationSuggestion", riskResult.getOptimizationSuggestion());
                }
                
                return ToolResult.success(response);
            }
            
            // ✅ 3. 完整风险评估（等效于 Groovy assessSQLRisk）
            RiskAssessmentResult riskResult = assessSQLRisk(sql, datasourceId);
            
            if ("HIGH".equals(riskResult.getRiskLevel())) {
                log.warn("[ExecuteSQLTool] ⚠️ 高风险SQL需要人工确认: {}", riskResult.getReason());
                
                String sqlToReturn = riskResult.getOptimizedSql() != null ? 
                    riskResult.getOptimizedSql() : sql;
                
                Map<String, Object> approvalData = new LinkedHashMap<>();
                approvalData.put("type", "human_approval_required");
                approvalData.put("approvalId", "sql_" + System.currentTimeMillis());
                approvalData.put("riskLevel", "HIGH");
                approvalData.put("riskReason", riskResult.getReason());
                approvalData.put("sql", sqlToReturn);
                approvalData.put("message", "该SQL存在高风险，请审核后再决定是否执行");
                
                if (riskResult.getOptimizationSuggestion() != null && !riskResult.getOptimizationSuggestion().trim().isEmpty()) {
                    approvalData.put("optimizationSuggestion", riskResult.getOptimizationSuggestion());
                }
                
                return ToolResult.success(approvalData);
            }
            
            // MEDIUM风险：记录优化建议，继续执行
            String optimizationSuggestion = null;
            if ("MEDIUM".equals(riskResult.getRiskLevel()) && riskResult.getOptimizationSuggestion() != null) {
                optimizationSuggestion = riskResult.getOptimizationSuggestion();
                log.info("[ExecuteSQLTool] ⚠️ 中风险SQL，继续执行但提示: {}", optimizationSuggestion);
            }
            
            // 使用优化后的SQL（如果有）
            String sqlToExecute = riskResult.getOptimizedSql() != null ? 
                riskResult.getOptimizedSql() : sql;
            
            // ✅ 4. 执行SQL并支持自动修正（等效于 Groovy executeWithAutoFix）
            ExecutionResult execResult = executeWithAutoFix(sqlToExecute, datasourceId, userId, username, MAX_RETRIES);
            
            if (!execResult.success) {
                log.error("[ExecuteSQLTool] 执行失败: {}", execResult.error);
                
                Map<String, Object> metadata = new HashMap<>();
                metadata.put("executionTimeMs", System.currentTimeMillis() - startTime);
                return ToolResult.error(execResult.error, metadata);
            }
            
            log.info("[ExecuteSQLTool] 查询成功: rowCount={}, executionTime={}ms", 
                execResult.rowCount, execResult.executionTime);
            
            // 5. 构建响应
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("rowCount", execResult.rowCount);
            response.put("data", execResult.data);
            
            if (execResult.data != null && !execResult.data.isEmpty()) {
                response.put("columns", new ArrayList<>(execResult.data.get(0).keySet()));
            }
            
            // 6. 添加元数据
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("executionTimeMs", execResult.executionTime);
            metadata.put("rowCount", execResult.rowCount);
            
            // ✅ 只在有实际值时才添加 optimizationSuggestion
            if (optimizationSuggestion != null && !optimizationSuggestion.trim().isEmpty()) {
                metadata.put("optimizationSuggestion", optimizationSuggestion);
            }
            
            return ToolResult.success(response, metadata);
            
        } catch (IllegalArgumentException e) {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("executionTimeMs", System.currentTimeMillis() - startTime);
            return ToolResult.error(e.getMessage(), metadata);
            
        } catch (Exception e) {
            log.error("[ExecuteSQLTool] 执行异常", e);
            
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("executionTimeMs", System.currentTimeMillis() - startTime);
            metadata.put("errorType", errorClassifier.classify(e).name());
            
            return ToolResult.error("SQL执行失败: " + e.getMessage(), metadata);
        }
    }
    
    /**
     * 参数校验
     */
    private void validateParameters(ToolContext context) {
        String sql = context.getParameter("sql");
        Long datasourceId = context.getParameter("datasourceId");
        
        parameterValidator
            .required("sql", sql)
            .required("datasourceId", String.valueOf(datasourceId))
            .maxLength("sql", sql, 10000)
            .throwIfHasErrors();
    }
    
    /**
     * ✅ 完整SQL风险评估（等效于 Groovy assessSQLRisk）
     * 
     * 流程：
     * 0. 快速判断 → 简单查询直接放行（不调用 EXPLAIN）
     * 1. EXPLAIN 分析 → 获取客观风险等级
     * 2. LOW → 直接执行
     * 3. MEDIUM → 调用 LLM 获取优化建议（仅供参考，仍执行原 SQL）
     * 4. HIGH → 调用 LLM 重新生成 SQL → 重新 EXPLAIN
     *    - 如果优化后不是 HIGH → 使用新 SQL 执行
     *    - 如果优化后仍是 HIGH → 返回 human_approval_required
     */
    private RiskAssessmentResult assessSQLRisk(String sql, Long datasourceId) {
        try {
            // ✅ 关键修复：如果 SQL 是澄清消息或错误消息，直接返回低风险
            if (sql.startsWith("CLARIFICATION") || sql.startsWith("CLARIFY_") || 
                sql.startsWith("错误：") || sql.startsWith("ERROR:")) {
                log.info("[ExecuteSQLTool] SQL不是有效查询，跳过风险评估");
                return new RiskAssessmentResult("LOW", "非有效SQL，无需风险评估");
            }
            
            if (riskAnalyzer == null) {
                log.warn("[ExecuteSQLTool] ⚠️ SQLRiskAnalyzer 未注入，使用静态规则校验");
                return staticRiskAssessment(sql);
            }
            
            // Step 0: 快速判断 - 简单查询直接放行（不调用 EXPLAIN）
            if (isSimpleQuery(sql)) {
                log.info("[ExecuteSQLTool] ✅ 快速判断：简单查询，直接放行（跳过 EXPLAIN）");
                return new RiskAssessmentResult("LOW", "简单查询，无需 EXPLAIN");
            }
            
            // Step 1: 执行 EXPLAIN 分析（必做）
            log.info("[ExecuteSQLTool] Step 1: 执行 EXPLAIN 分析");
            SQLRiskAnalyzer.RiskAnalysisResult explainResult = riskAnalyzer.analyzeRisk(sql, datasourceId);
            String riskLevel = explainResult.getRiskLevel();
            
            log.info("[ExecuteSQLTool] EXPLAIN 分析结果: riskLevel={}, risks={}", 
                riskLevel, explainResult.getRisks() != null ? explainResult.getRisks().size() : 0);
            
            // Step 2: 根据风险等级处理
            if ("LOW".equals(riskLevel)) {
                log.info("[ExecuteSQLTool] ✅ EXPLAIN 评估为低风险，直接执行");
                return new RiskAssessmentResult("LOW", "EXPLAIN 分析无风险");
                
            } else if ("MEDIUM".equals(riskLevel)) {
                log.info("[ExecuteSQLTool] ⚠️ EXPLAIN 评估为中风险，调用 LLM 获取优化建议");
                
                if (llmService != null) {
                    String suggestionPrompt = buildOptimizationSuggestionPrompt(sql, explainResult);
                    String llmResponse = llmService.generateAnswer(suggestionPrompt);
                    
                    OptimizationSuggestion suggestion = parseOptimizationSuggestion(llmResponse);
                    
                    RiskAssessmentResult result = new RiskAssessmentResult("MEDIUM", 
                        explainResult.getRisks() != null ? String.join("; ", explainResult.getRisks()) : "");
                    result.setLlmSuggestion(suggestion);
                    result.setOriginalSql(sql);
                    
                    // 构建前端友好的优化建议
                    if (suggestion != null) {
                        StringBuilder sb = new StringBuilder();
                        sb.append("⚠️ 中风险SQL，继续执行但请注意以下优化建议：\n\n");
                        if (suggestion.getBottleneck() != null && !suggestion.getBottleneck().trim().isEmpty()) {
                            sb.append("🔍 性能瓶颈：\n").append(suggestion.getBottleneck()).append("\n\n");
                        }
                        if (suggestion.getSuggestion() != null && !suggestion.getSuggestion().trim().isEmpty()) {
                            sb.append("💡 优化建议：\n").append(suggestion.getSuggestion()).append("\n\n");
                        }
                        if (suggestion.getExpectedImprovement() != null && !suggestion.getExpectedImprovement().trim().isEmpty()) {
                            sb.append("📈 预期效果：\n").append(suggestion.getExpectedImprovement());
                        }
                        result.setOptimizationSuggestion(sb.toString());
                    }
                    
                    log.info("[ExecuteSQLTool] ✅ LLM 优化建议: {}", suggestion != null ? suggestion.getSuggestion() : "无");
                    return result;
                } else {
                    // 无LLM服务，直接使用EXPLAIN风险信息
                    return new RiskAssessmentResult("MEDIUM", 
                        explainResult.getRisks() != null ? String.join("; ", explainResult.getRisks()) : "");
                }
                
            } else if ("HIGH".equals(riskLevel)) {
                log.info("[ExecuteSQLTool] 🔴 EXPLAIN 评估为高风险，调用 LLM 重新生成 SQL");
                
                if (llmService != null) {
                    String regeneratePrompt = buildRegenerateSQLPrompt(sql, explainResult);
                    String regeneratedSql = llmService.generateAnswer(regeneratePrompt);
                    
                    // 提取 SQL（LLM 可能返回 Markdown 或其他格式）
                    regeneratedSql = extractSQLFromResponse(regeneratedSql);
                    
                    log.info("[ExecuteSQLTool] LLM 重新生成的 SQL: {}", regeneratedSql);
                    
                    // 重新 EXPLAIN 验证优化效果
                    log.info("[ExecuteSQLTool] Step 2: 重新 EXPLAIN 验证优化后的 SQL");
                    SQLRiskAnalyzer.RiskAnalysisResult optimizedExplain = riskAnalyzer.analyzeRisk(regeneratedSql, datasourceId);
                    
                    log.info("[ExecuteSQLTool] 优化后 EXPLAIN 结果: riskLevel={}, risks={}", 
                        optimizedExplain.getRiskLevel(), optimizedExplain.getRisks() != null ? optimizedExplain.getRisks().size() : 0);
                    
                    // 判断优化后的风险等级
                    if ("HIGH".equals(optimizedExplain.getRiskLevel())) {
                        log.warn("[ExecuteSQLTool] ⚠️ LLM 优化后仍为高风险，只返回 SQL 给前端，需要人工审核");
                        
                        RiskAssessmentResult result = new RiskAssessmentResult(
                            "HIGH", 
                            "LLM 优化后仍为高风险，需要人工介入审核。原始风险：" + 
                            (explainResult.getRisks() != null ? String.join("; ", explainResult.getRisks()) : "")
                        );
                        result.setOriginalSql(sql);
                        result.setOptimizedSql(regeneratedSql);
                        result.setOptimizationApplied(true);
                        result.setRequiresManualReview(true);
                        
                        return result;
                    } else {
                        log.info("[ExecuteSQLTool] ✅ LLM 优化成功，风险从 HIGH 降到 {}", optimizedExplain.getRiskLevel());
                        
                        RiskAssessmentResult result = new RiskAssessmentResult(
                            optimizedExplain.getRiskLevel(), 
                            optimizedExplain.getRisks() != null ? String.join("; ", optimizedExplain.getRisks()) : ""
                        );
                        result.setOriginalSql(sql);
                        result.setOptimizedSql(regeneratedSql);
                        result.setOptimizationApplied(true);
                        
                        return result;
                    }
                } else {
                    // 无LLM服务，直接阻断
                    return new RiskAssessmentResult("HIGH", 
                        "高风险SQL且无LLM服务优化，已阻断执行。风险：" + 
                        (explainResult.getRisks() != null ? String.join("; ", explainResult.getRisks()) : ""));
                }
            } else {
                log.warn("[ExecuteSQLTool] ⚠️ 未知风险等级: {}，默认低风险", riskLevel);
                return new RiskAssessmentResult("LOW", "未知风险等级，默认继续执行");
            }
            
        } catch (Exception e) {
            log.error("[ExecuteSQLTool] ❌ 风险评估失败: {}", e.getMessage(), e);
            
            // ✅ 离线模式降级：使用静态规则校验
            log.info("[ExecuteSQLTool] ✅ 降级为静态规则校验（离线模式）");
            return staticRiskAssessment(sql);
        }
    }
    
    /**
     * ✅ 静态 SQL 风险评估（无需连库，适用于离线模式）
     */
    private RiskAssessmentResult staticRiskAssessment(String sql) {
        if (sql == null || sql.isEmpty()) {
            return new RiskAssessmentResult("LOW", "空 SQL");
        }
        
        String upperSql = sql.toUpperCase().trim();
        List<String> risks = new ArrayList<>();
        String riskLevel = "LOW";
        
        // 规则1: 检测全表扫描风险（无 WHERE 条件）
        if (upperSql.startsWith("SELECT") && !upperSql.contains("WHERE")) {
            if (!upperSql.contains("LIMIT")) {
                risks.add("⚠️ 无 WHERE 条件且无 LIMIT，可能导致全表扫描");
                riskLevel = "MEDIUM";
            }
        }
        
        // 规则2: 检测多表 JOIN 复杂度
        int joinCount = 0;
        if (upperSql.contains(" JOIN ")) {
            joinCount = upperSql.split(" JOIN ").length - 1;
            if (joinCount >= 3) {
                risks.add("🔴 多表 JOIN（" + joinCount + "个），性能风险高");
                riskLevel = "HIGH";
            } else if (joinCount >= 2) {
                risks.add("⚠️ 多表 JOIN（" + joinCount + "个），建议优化");
                if ("MEDIUM".compareTo(riskLevel) > 0) {
                    riskLevel = "MEDIUM";
                }
            }
        }
        
        // 规则3: 检测子查询嵌套
        int selectCount = 0;
        for (int i = 0; i < upperSql.length(); i++) {
            if (upperSql.substring(i).startsWith("SELECT")) {
                selectCount++;
            }
        }
        if (selectCount >= 3) {
            risks.add("🔴 多层子查询嵌套（" + selectCount + "层），性能差");
            riskLevel = "HIGH";
        } else if (selectCount == 2) {
            risks.add("⚠️ 包含子查询，建议优化为 JOIN");
            if ("MEDIUM".compareTo(riskLevel) > 0) {
                riskLevel = "MEDIUM";
            }
        }
        
        // 规则4: 检测危险操作
        if (upperSql.contains("DROP ") || upperSql.contains("TRUNCATE ") || 
            upperSql.contains("DELETE FROM") || upperSql.contains("UPDATE ")) {
            risks.add("🔴 包含数据修改/删除操作，禁止执行");
            riskLevel = "HIGH";
        }
        
        // 规则5: 检测大结果集风险（无 LIMIT 的复杂查询）
        if ((joinCount >= 2 || selectCount >= 2) && !upperSql.contains("LIMIT")) {
            risks.add("⚠️ 复杂查询无 LIMIT，可能返回大量数据");
            if ("MEDIUM".compareTo(riskLevel) > 0) {
                riskLevel = "MEDIUM";
            }
        }
        
        String reason = risks.isEmpty() ? "静态校验通过" : String.join("; ", risks);
        log.info("[ExecuteSQLTool] [静态校验] riskLevel={}, risks={}", riskLevel, risks.size());
        
        return new RiskAssessmentResult(riskLevel, reason);
    }
    
    /**
     * 快速判断是否为简单查询（无需 EXPLAIN）
     */
    private boolean isSimpleQuery(String sql) {
        if (sql == null || sql.isEmpty()) {
            return false;
        }
        
        String upperSql = sql.toUpperCase().trim();
        
        // 只处理 SELECT 语句
        if (!upperSql.startsWith("SELECT")) {
            return false;
        }
        
        // 规则1: 无 JOIN
        if (upperSql.contains(" JOIN ") || upperSql.contains("JOIN\n") || upperSql.contains("JOIN ")) {
            return false;
        }
        
        // 规则2: 无子查询（检查是否有嵌套 SELECT）
        int selectCount = 0;
        for (int i = 0; i < upperSql.length(); i++) {
            if (upperSql.substring(i).startsWith("SELECT")) {
                selectCount++;
            }
        }
        if (selectCount > 1) {
            return false;
        }
        
        // 规则3: 无聚合函数
        if (upperSql.contains("COUNT(") || 
            upperSql.contains("SUM(") || 
            upperSql.contains("AVG(") || 
            upperSql.contains("MAX(") || 
            upperSql.contains("MIN(") ||
            upperSql.contains("GROUP BY")) {
            return false;
        }
        
        // 规则4: 无 ORDER BY / DISTINCT
        if (upperSql.contains("ORDER BY") || upperSql.contains("DISTINCT")) {
            return false;
        }
        
        // 规则5: WHERE 条件包含主键等值查询（id = ? 或 id = 数字）
        Pattern primaryKeyPattern = Pattern.compile("WHERE\\s+\\w*_?id\\s*=\\s*\\d+", Pattern.CASE_INSENSITIVE);
        if (!primaryKeyPattern.matcher(sql).find()) {
            return false;
        }
        
        return true;
    }
    
    /**
     * ✅ 执行SQL并支持自动修正（等效于 Groovy executeWithAutoFix）
     */
    private ExecutionResult executeWithAutoFix(String sql, Long datasourceId, Long userId, 
                                                String username, int maxRetries) {
        String currentSql = sql;
        
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                if (attempt > 0) {
                    log.info("[ExecuteSQLTool] 🔄 第{}次重试...", attempt);
                }
                
                long queryStartTime = System.currentTimeMillis();
                List<Map<String, Object>> results = jdbcTemplate.queryForList(currentSql);
                long executionTime = System.currentTimeMillis() - queryStartTime;
                
                return new ExecutionResult(true, results, results.size(), 
                    executionTime, null, currentSql, false, null);
                
            } catch (Exception e) {
                log.error("[ExecuteSQLTool] 执行失败 (attempt={}): {}", attempt + 1, e.getMessage());
                
                // 如果还有重试次数，尝试自动修正
                if (attempt < maxRetries) {
                    log.info("[ExecuteSQLTool] 尝试自动修正 (第{}次)", attempt + 1);
                    
                    boolean isOfflineError = errorClassifier.classify(e) == ErrorType.DATABASE_CONNECTION_ERROR;
                    
                    if (isOfflineError) {
                        log.warn("[ExecuteSQLTool] [离线模式] 无法连接远程数据库，返回生成的SQL");
                        return new ExecutionResult(true, new ArrayList<>(), 0, 0.0, 
                            null, currentSql, true, "离线模式：无法连接远程数据库，请手动执行以下SQL");
                    }
                    
                    // 自动修正SQL
                    String correctedSql = autoCorrectSQL(currentSql, e.getMessage());
                    if (correctedSql != null && !correctedSql.equals(currentSql)) {
                        currentSql = correctedSql;
                        log.info("[ExecuteSQLTool] 修正后的SQL: {}", currentSql);
                    } else {
                        log.warn("[ExecuteSQLTool] 自动修正失败，保持原SQL");
                    }
                } else {
                    // 达到最大重试次数
                    return new ExecutionResult(false, null, 0, 0.0, 
                        "SQL执行失败，已尝试" + maxRetries + "次修正: " + e.getMessage(), currentSql, false, null);
                }
            }
        }
        
        return new ExecutionResult(false, null, 0, 0.0, "达到最大重试次数", sql, false, null);
    }
    
    /**
     * 自动修正SQL
     */
    private String autoCorrectSQL(String failedSql, String errorMessage) {
        // ✅ 优先使用 SQLCorrectionService
        if (correctionService != null) {
            try {
                SQLCorrectionService.CorrectionResult correctionResult = 
                    correctionService.autoCorrect(failedSql, errorMessage, 1);
                
                if (correctionResult != null && correctionResult.isSuccess()) {
                    log.info("[ExecuteSQLTool] ✅ SQLCorrectionService 修正成功");
                    return correctionResult.getCorrectedSQL();
                } else {
                    log.warn("[ExecuteSQLTool] ⚠️ SQLCorrectionService 修正失败，降级为 LLM 修正");
                }
            } catch (Exception e) {
                log.warn("[ExecuteSQLTool] ⚠️ SQLCorrectionService 执行异常，降级为 LLM 修正: {}", e.getMessage());
            }
        }
        
        // ✅ 降级：使用 NL2SQLService.autoFixSQL
        if (nl2sqlService != null) {
            try {
                String correctedSql = nl2sqlService.autoFixSQL(failedSql, errorMessage, null);
                if (correctedSql != null && !correctedSql.trim().isEmpty()) {
                    log.info("[ExecuteSQLTool] ✅ NL2SQLService 修正成功");
                    return correctedSql;
                }
            } catch (Exception e) {
                log.warn("[ExecuteSQLTool] ⚠️ NL2SQLService 修正失败: {}", e.getMessage());
            }
        }
        
        log.warn("[ExecuteSQLTool] ⚠️ 所有修正方式均失败");
        return failedSql;
    }
    
    /**
     * 构建优化建议 Prompt（中风险）
     */
    private String buildOptimizationSuggestionPrompt(String sql, SQLRiskAnalyzer.RiskAnalysisResult explainResult) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个数据库优化专家。以下SQL存在中等风险，请提供优化建议。\n\n");
        sb.append("原始 SQL：\n").append(sql).append("\n\n");
        sb.append("EXPLAIN 分析结果：\n");
        sb.append("- 风险等级：").append(explainResult.getRiskLevel()).append("\n");
        if (explainResult.getRisks() != null && !explainResult.getRisks().isEmpty()) {
            sb.append("- 发现的风险点：\n");
            for (String risk : explainResult.getRisks()) {
                sb.append("  - ").append(risk).append("\n");
            }
        }
        sb.append("\n请提供优化建议（不要重新生成 SQL，只给建议）：\n");
        sb.append("1. 指出主要性能瓶颈\n");
        sb.append("2. 给出具体的优化建议（如添加索引、改写 WHERE 条件等）\n");
        sb.append("3. 说明预期优化效果\n\n");
        sb.append("返回JSON格式：\n");
        sb.append("{\n");
        sb.append("  \"bottleneck\": \"主要性能瓶颈\",\n");
        sb.append("  \"suggestion\": \"具体优化建议\",\n");
        sb.append("  \"expected_improvement\": \"预期优化效果\"\n");
        sb.append("}");
        
        return sb.toString();
    }
    
    /**
     * 解析 LLM 优化建议
     */
    private OptimizationSuggestion parseOptimizationSuggestion(String response) {
        try {
            int jsonStart = response.indexOf("{");
            int jsonEnd = response.lastIndexOf("}");
            if (jsonStart >= 0 && jsonEnd > jsonStart) {
                String jsonStr = response.substring(jsonStart, jsonEnd + 1);
                Map<String, Object> json = objectMapper.readValue(jsonStr, Map.class);
                
                OptimizationSuggestion suggestion = new OptimizationSuggestion();
                suggestion.setBottleneck((String) json.getOrDefault("bottleneck", ""));
                suggestion.setSuggestion((String) json.getOrDefault("suggestion", ""));
                suggestion.setExpectedImprovement((String) json.getOrDefault("expected_improvement", ""));
                return suggestion;
            }
        } catch (Exception e) {
            log.warn("[ExecuteSQLTool] 解析LLM优化建议失败: {}", e.getMessage());
        }
        
        return null;
    }
    
    /**
     * 构建重新生成 SQL 的 Prompt（高风险）
     */
    private String buildRegenerateSQLPrompt(String originalSql, SQLRiskAnalyzer.RiskAnalysisResult explainResult) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个数据库专家。之前生成的SQL存在高风险，请重新生成一个更优化的SQL。\n\n");
        sb.append("❌ 原始 SQL（有高风险）：\n").append(originalSql).append("\n\n");
        sb.append("⚠️ EXPLAIN 分析发现的风险：\n");
        if (explainResult.getRisks() != null && !explainResult.getRisks().isEmpty()) {
            for (String risk : explainResult.getRisks()) {
                sb.append("  - ").append(risk).append("\n");
            }
        }
        if (explainResult.getSuggestions() != null && !explainResult.getSuggestions().isEmpty()) {
            sb.append("\n💡 优化建议：\n");
            for (String suggestion : explainResult.getSuggestions()) {
                sb.append("  - ").append(suggestion).append("\n");
            }
        }
        sb.append("\n🎯 任务：重新生成一个SQL，要求：\n");
        sb.append("1. 避免上述风险（如全表扫描、缺少索引等）\n");
        sb.append("2. 保持查询语义不变\n");
        sb.append("3. 如果无法优化，请说明原因并返回原 SQL\n");
        sb.append("4. **只输出 SQL 语句，不要包含其他内容**\n\n");
        sb.append("新 SQL：");
        
        return sb.toString();
    }
    
    /**
     * 从 LLM 响应中提取 SQL
     */
    private String extractSQLFromResponse(String response) {
        if (response == null || response.isEmpty()) {
            return null;
        }
        
        // 去除 Markdown 代码块标记
        String cleaned = response.trim();
        cleaned = cleaned.replaceAll("```sql\\s*", "");
        cleaned = cleaned.replaceAll("```\\s*$", "");
        cleaned = cleaned.trim();
        
        // 如果包含多行，取第一行完整的 SQL
        if (cleaned.contains("\n")) {
            String[] lines = cleaned.split("\n");
            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.toUpperCase().startsWith("SELECT") || 
                    trimmed.toUpperCase().startsWith("WITH") ||
                    trimmed.toUpperCase().startsWith("INSERT") ||
                    trimmed.toUpperCase().startsWith("UPDATE") ||
                    trimmed.toUpperCase().startsWith("DELETE")) {
                    return trimmed;
                }
            }
        }
        
        return cleaned;
    }
    
    // ==================== 内部类 ====================
    
    static class RiskAssessmentResult {
        private String riskLevel;
        private String reason;
        private String originalSql;
        private String optimizedSql;
        private Boolean optimizationApplied = false;
        private Boolean requiresManualReview = false;
        private OptimizationSuggestion llmSuggestion;
        private String optimizationSuggestion;  // 前端友好的优化建议文本
        
        RiskAssessmentResult(String riskLevel, String reason) {
            this.riskLevel = riskLevel;
            this.reason = reason;
        }
        
        public String getRiskLevel() { return riskLevel; }
        public String getReason() { return reason; }
        public String getOriginalSql() { return originalSql; }
        public void setOriginalSql(String sql) { this.originalSql = sql; }
        public String getOptimizedSql() { return optimizedSql; }
        public void setOptimizedSql(String sql) { this.optimizedSql = sql; }
        public Boolean getOptimizationApplied() { return optimizationApplied; }
        public void setOptimizationApplied(Boolean applied) { this.optimizationApplied = applied; }
        public Boolean getRequiresManualReview() { return requiresManualReview; }
        public void setRequiresManualReview(Boolean review) { this.requiresManualReview = review; }
        public OptimizationSuggestion getLlmSuggestion() { return llmSuggestion; }
        public void setLlmSuggestion(OptimizationSuggestion suggestion) { this.llmSuggestion = suggestion; }
        public String getOptimizationSuggestion() { return optimizationSuggestion; }
        public void setOptimizationSuggestion(String suggestion) { this.optimizationSuggestion = suggestion; }
    }
    
    static class OptimizationSuggestion {
        private String bottleneck;
        private String suggestion;
        private String expectedImprovement;
        
        public String getBottleneck() { return bottleneck; }
        public void setBottleneck(String bottleneck) { this.bottleneck = bottleneck; }
        public String getSuggestion() { return suggestion; }
        public void setSuggestion(String suggestion) { this.suggestion = suggestion; }
        public String getExpectedImprovement() { return expectedImprovement; }
        public void setExpectedImprovement(String improvement) { this.expectedImprovement = improvement; }
    }
    
    @lombok.Data
    @lombok.NoArgsConstructor(force = true, access = lombok.AccessLevel.PUBLIC)
    @lombok.AllArgsConstructor(access = lombok.AccessLevel.PUBLIC)
    static class ExecutionResult {
        boolean success;
        List<Map<String, Object>> data;
        int rowCount;
        double executionTime;
        String error;
        String sql;
        boolean offlineMode;
        String message;
    }
    
    // ==================== 保留旧方法以兼容LangChain4j @Tool注解 ====================
    
    public String executeSQL(String sql, Long datasourceId) {
        // 委托给BaseTool接口
        Map<String, Object> params = new HashMap<>();
        params.put("sql", sql);
        params.put("datasourceId", datasourceId);
        
        ToolContext context = ToolContext.builder()
            .parameters(params)
            .build();
        
        ToolResult result = execute(context);
        
        try {
            if (result.isSuccess()) {
                return objectMapper.writeValueAsString(result.getData());
            } else {
                Map<String, Object> errorResponse = new HashMap<>();
                errorResponse.put("success", false);
                errorResponse.put("error", result.getErrorMessage());
                return objectMapper.writeValueAsString(errorResponse);
            }
        } catch (Exception e) {
            log.error("[ExecuteSQLTool] 序列化结果失败", e);
            return "{\"success\":false,\"error\":\"序列化失败\"}";
        }
    }
}
