package com.nl2sql.core.agent;

import com.nl2sql.core.agent.skills.GroovySkillExecutor;
import com.nl2sql.core.agent.skills.SkillContext;
import com.nl2sql.core.agent.skills.SkillsMetadataLoader;
import com.nl2sql.core.agent.tools.*;
import com.nl2sql.core.llm.LLMService;
import com.nl2sql.core.metadata.MetadataService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.*;

/**
 * Agent 配置 - 使用自定义 ReAct Agent 实现
 * 
 * 关键特性：
 * 1. 自主决策：LLM根据SystemMessage自主决定调用哪些Tool
 * 2. 多轮推理：支持Thought-Action-Observation循环
 * 3. 对话记忆：在ReActAgent内部管理
 * 4. 工具注册：所有Tools对Agent可见
 */
@Slf4j
@Configuration
public class AgentConfig {
    
    @Autowired
    private LLMService llmService;
    
    // 核心Tools（Skills内部依赖这些工具）
    @Autowired
    private NL2SQLTool nl2sqlTool;
    
    @Autowired
    private SQLExecutionTool sqlExecutionTool;
    
    @Autowired
    private DatasourceClarificationTool datasourceClarificationTool;
    
    // Skills 元数据加载器
    @Autowired(required = false)
    private SkillsMetadataLoader skillsMetadataLoader;
    
    // Groovy Skill 执行器
    @Autowired(required = false)
    private GroovySkillExecutor groovySkillExecutor;
    
    @Autowired
    private ApplicationContext applicationContext;
    
    // 可选的增强Tools（根据需要启用）
    @Autowired(required = false)
    private ContextSummarizerTool contextSummarizerTool;
    
    @Autowired(required = false)
    private ConversationMemoryTool conversationMemoryTool;
    
    @Autowired(required = false)
    private IntentClassifierTool intentClassifierTool;
    
    @Autowired(required = false)
    private SQLOptimizerTool sqlOptimizerTool;
    
    @Autowired(required = false)
    private SQLRiskAnalysisTool sqlRiskAnalysisTool;
    
    @Autowired(required = false)
    private ReportGeneratorTool reportGeneratorTool;
    
    @Autowired(required = false)
    private AISummaryTool aiSummaryTool;
    
    @Autowired(required = false)
    private TableRelationshipDetectionTool tableRelationshipDetectionTool;
    
    // 原子 Tool（供 Skill 调用）
    @Autowired(required = false)
    private ExecuteSQLTool executeSQLTool;
    
    @Autowired(required = false)
    private GetTableMetadataTool getTableMetadataTool;
    
    @Autowired(required = false)
    private ValidateSQLTool validateSQLTool;
    
    @Autowired(required = false)
    private AnalyzeQueryPlanTool analyzeQueryPlanTool;
    
    @Autowired(required = false)
    private GetDatabaseStatsTool getDatabaseStatsTool;
    
    @Autowired(required = false)
    private CheckIndexTool checkIndexTool;
    
    @Autowired(required = false)
    private GetTableRelationshipsTool getTableRelationshipsTool;
    
    @Autowired(required = false)
    private FormatSQLTool formatSQLTool;
    
    @Autowired(required = false)
    private CompareSQLTool compareSQLTool;
    
    @Autowired(required = false)
    private EstimateCostTool estimateCostTool;
    
    @Autowired(required = false)
    private MetadataService metadataService;
    
    /**
     * 创建 NL2SQL ReAct Agent
     * 
     * 这个Agent会：
     * 1. 自主决定调用哪些Tool（基于SystemMessage指导）
     * 2. 根据Tool返回结果动态调整策略（ReAct循环）
     * 3. 最多迭代10次，防止无限循环
     */
    @Bean
    public ReActAgent reActAgent() {
        log.info("初始化 NL2SQL ReAct Agent（原生 Tool Calling）...");
        
        // ✅ 使用 LLMService 而非 ChatModel
        ReActAgent agent = new ReActAgent(llmService);
        
        // 注入 Skills 元数据加载器（如果存在）
        if (skillsMetadataLoader != null) {
            log.info("✅ 已注入 Skills 元数据加载器");
        } else {
            log.warn("⚠️ 未找到 Skills 元数据加载器，将使用默认 SystemMessage");
        }
        
        // 初始化 GroovySkillExecutor
        if (groovySkillExecutor != null) {
            groovySkillExecutor.setApplicationContext(applicationContext);
            log.info("✅ 已初始化 GroovySkillExecutor");
        }
        
        // 注册核心工具
        agent.registerTool("clarify_datasource", (args, dsId, userId, username, userMessage) -> {
            String userQuery = (String) args.get("userQuery");
            // 如果LLM没有传递userQuery，使用原始的userMessage
            if (userQuery == null || userQuery.trim().isEmpty()) {
                log.warn("[clarify_datasource] LLM未传递userQuery参数，使用原始用户消息");
                userQuery = userMessage;
            }
            return datasourceClarificationTool.clarifyDatasource(userQuery);
        }, "当用户未指定数据源时，用于获取可用数据源列表。⚠️ 调用时必须传递参数：{\"userQuery\": \"用户的原始问题\"}");
        
        // 注册高级技能（动态扫描并注册所有 Groovy Skills）
        if (groovySkillExecutor != null) {
            List<GroovySkillExecutor.SkillInfo> skills = groovySkillExecutor.getDiscoveredSkills();
            
            for (GroovySkillExecutor.SkillInfo skill : skills) {
                agent.registerTool(skill.getToolName(), (args, dsId, userId, username, userMessage) -> {
                    try {
                        // ✅ 自动校验必需参数（从 Skill 元数据中获取）
                        Set<String> requiredParams = skill.getRequiredParams();
                        if (requiredParams != null && !requiredParams.isEmpty()) {
                            for (String requiredParam : requiredParams) {
                                Object value = args.get(requiredParam);
                                if (value == null || "null".equals(String.valueOf(value))) {
                                    log.warn("[{}] 缺少必需参数: {}", skill.getToolName(), requiredParam);
                                    
                                    // ⚠️ 关键修复：如果是 datasourceId 缺失，引导 LLM 调用 clarify_datasource
                                    if ("datasourceId".equals(requiredParam)) {
                                        return "Observation: 你尝试调用 " + skill.getToolName() + " 但没有提供 datasourceId。\n" +
                                               "根据规则，你必须先调用 clarify_datasource(userQuery=\"" + 
                                               (args.get("question") != null ? args.get("question") : userMessage) + 
                                               "\") 获取数据源信息。\n" +
                                               "请立即调用 clarify_datasource 工具！";
                                    }
                                    
                                    return "{\"status\":\"error\",\"message\":\"缺少必需参数: " + requiredParam + "，请先调用 clarify_datasource 工具\"}";
                                }
                            }
                        }
                        
                        // ✅ 统一规范：Groovy Skill 使用平铺参数 {question, datasourceId}
                        String question = (String) args.get("question");
                        
                        Object datasourceIdObj = args.get("datasourceId");
                        Long datasourceId = null;
                        if (datasourceIdObj != null) {
                            datasourceId = ((Number) datasourceIdObj).longValue();
                        } else {
                            // 如果 LLM 没有传 datasourceId，使用传入的 dsId
                            datasourceId = dsId;
                        }
                        
                        // 如果最终 datasourceId 还是 null，说明用户未指定数据源，需要澄清
                        if (datasourceId == null) {
                            log.warn("[{}] 数据源ID为空，需要先澄清数据源", skill.getToolName());
                            return "{\"status\":\"clarification_needed\",\"clarificationType\":\"general_clarification\",\"message\":\"请先选择数据源，您可以输入'列出所有数据源'查看可用数据源\"}";
                        }
                        
                        SkillContext context = new SkillContext();
                        context.setParameter("question", question);
                        context.setParameter("datasourceId", datasourceId);
                        context.setParameter("userId", userId);
                        context.setParameter("username", username);
                        
                        // ✅ 从 NL2SQLTool 获取当前会话ID（用于流式事件推送）
                        if (nl2sqlTool != null) {
                            String currentSessionId = nl2sqlTool.getCurrentSessionId();
                            if (currentSessionId != null) {
                                context.setParameter("sessionId", currentSessionId);
                                log.debug("[{}] 已设置 sessionId: {}", skill.getToolName(), currentSessionId);
                            }
                        }
                        
                        Object result = groovySkillExecutor.executeSkill(skill.getSkillPath(), context);
                        
                        // 转换为 JSON
                        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                        String resultJson = mapper.writeValueAsString(result);
                        
                        // ✅ 检测是否需要补充表关系
                        if (resultJson.contains("\"clarificationType\":\"table_missing\"") || 
                            resultJson.contains("\"missing_tables\"")) {
                            log.info("[{}] 检测到缺失表，尝试自动补充表关系", skill.getToolName());
                            
                            try {
                                // 解析 missing_tables
                                Map<String, Object> resultMap = mapper.readValue(resultJson, Map.class);
                                List<String> missingTables = (List<String>) resultMap.get("missing_tables");
                                String reason = (String) resultMap.get("reason");
                                
                                if (missingTables != null && !missingTables.isEmpty()) {
                                    log.info("[{}] 缺失表: {}, 原因: {}", skill.getToolName(), missingTables, reason);
                                    
                                    // 查询元数据服务获取表结构
                                    StringBuilder tableInfo = new StringBuilder();
                                    for (String tableName : missingTables) {
                                        try {
                                            com.nl2sql.core.metadata.TableMetadata tableMeta = metadataService.getTableMetadata(tableName);
                                            if (tableMeta != null) {
                                                tableInfo.append("表名: ").append(tableName).append("\n");
                                                tableInfo.append("注释: ").append(tableMeta.getTableComment()).append("\n");
                                                tableInfo.append("字段: ").append(tableMeta.getColumns()).append("\n\n");
                                            }
                                        } catch (Exception e) {
                                            log.warn("[{}] 获取表 {} 元数据失败: {}", skill.getToolName(), tableName, e.getMessage());
                                        }
                                    }
                                    
                                    // 将表信息添加到上下文，重新执行
                                    context.setParameter("additionalTableInfo", tableInfo.toString());
                                    log.info("[{}] 已补充表信息，重新执行 Skill", skill.getToolName());
                                    
                                    Object retryResult = groovySkillExecutor.executeSkill(skill.getSkillPath(), context);
                                    return mapper.writeValueAsString(retryResult);
                                }
                            } catch (Exception e) {
                                log.error("[{}] 自动补充表关系失败", skill.getToolName(), e);
                            }
                        }
                        
                        return resultJson;
                        
                    } catch (Exception e) {
                        log.error("[{}] 执行失败", skill.getToolName(), e);
                        return "{\"status\":\"error\",\"message\":\"" + e.getMessage() + "\"}";
                    }
                }, skill.getDescription());
                
                log.info("启用 {} (Groovy)", skill.getToolName());
            }
        }
        
        // 注册 AI 总结工具（处理 [INTENT:AI_SUMMARY] 意图）
        if (aiSummaryTool != null) {
            agent.registerTool("summarize_result", (args, dsId, userId, username, userMessage) -> {
                String lastQuery;
                String generatedSQL;
                
                // ✅ 关键修复：优先从 NL2SQLTool 获取最新的 SQL
                if (nl2sqlTool != null) {
                    generatedSQL = nl2sqlTool.getCurrentSQL();
                    lastQuery = nl2sqlTool.getCurrentQuery();
                    log.info("[summarize_result] 从 NL2SQLTool 获取上下文: sql={}, query={}", generatedSQL, lastQuery);
                } else {
                    // 降级：从扁平参数中获取
                    lastQuery = (String) args.get("lastQuery");
                    generatedSQL = (String) args.get("generatedSQL");
                    if (lastQuery == null || generatedSQL == null) {
                        return "{\"status\":\"error\",\"message\":\"缺少 lastQuery 或 generatedSQL 参数\"}";
                    }
                }
                
                if (generatedSQL == null || generatedSQL.trim().isEmpty()) {
                    return "{\"status\":\"error\",\"message\":\"缺少 SQL 语句\"}";
                }
                
                try {
                    // 使用 SQLExecutionTool 重新执行查询，获取最新数据
                    log.info("[summarize_result] 重新执行 SQL 获取数据: {}", generatedSQL);
                    log.info("[summarize_result] datasourceId={}, userId={}, username={}", dsId, userId, username);
                    
                    SQLExecutionTool.ExecutionResult execResult = sqlExecutionTool.executeSQL(
                        generatedSQL, dsId, userId, username
                    );
                    
                    log.info("[summarize_result] 执行结果: success={}, rowCount={}", 
                        execResult.isSuccess(), 
                        execResult.getData() != null ? execResult.getData().size() : 0);
                    
                    if (!execResult.isSuccess()) {
                        log.error("[summarize_result] SQL执行失败: {}", execResult.getError());
                        return "{\"status\":\"error\",\"message\":\"SQL执行失败: " + execResult.getError() + "\"}";
                    }
                    
                    if (execResult.getData() == null || execResult.getData().isEmpty()) {
                        return "{\"status\":\"error\",\"message\":\"查询结果为空，无法生成总结\"}";
                    }
                    
                    List<Map<String, Object>> queryData = execResult.getData();
                    log.info("[summarize_result] 获取到 {} 行数据", queryData.size());
                    
                    // 转换为 JSON
                    com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                    String dataJson = mapper.writeValueAsString(queryData);
                    
                    // 调用 AISummaryTool
                    String summary = aiSummaryTool.summarize(lastQuery, generatedSQL, dataJson);
                    
                    // 返回结构化结果
                    Map<String, Object> result = new HashMap<>();
                    result.put("status", "success");
                    result.put("summary", summary);
                    result.put("datasourceId", dsId);  // ⚠️ 重要：返回 datasourceId 供前端后续使用
                    return mapper.writeValueAsString(result);
                } catch (Exception e) {
                    log.error("[summarize_result] 执行失败", e);
                    return "{\"status\":\"error\",\"message\":\"总结失败: " + e.getMessage() + "\"}";
                }
            }, "对查询结果进行AI分析和总结。当用户消息包含 [INTENT:AI_SUMMARY] 时调用此工具，从 context 参数中获取 lastQuery 和 generatedSQL，工具会自动重新执行 SQL 获取最新数据");
            log.info("启用 AISummaryTool");
        }
        
        // 注册图表生成工具（处理 [INTENT:GENERATE_CHART] 意图）
        agent.registerTool("generate_chart", (args, dsId, userId, username, userMessage) -> {
            log.info("[generate_chart] 收到参数: args={}", args);
            
            String chartType;
            String generatedSQL;
            
            // ✅ 关键修复：优先从 NL2SQLTool 获取最新的 SQL
            if (nl2sqlTool != null) {
                generatedSQL = nl2sqlTool.getCurrentSQL();
                chartType = (String) args.get("chartType");
                log.info("[generate_chart] 从 NL2SQLTool 获取上下文: sql={}, chartType={}", generatedSQL, chartType);
            } else {
                // 降级：从扁平参数中获取
                chartType = (String) args.get("chartType");
                generatedSQL = (String) args.get("generatedSQL");
                if (generatedSQL == null) {
                    log.error("[generate_chart] generatedSQL 为空");
                    return "{\"status\":\"error\",\"message\":\"缺少 generatedSQL 参数\"}";
                }
            }
            
            log.info("[generate_chart] chartType={}, generatedSQL={}", chartType, generatedSQL);
            
            if (generatedSQL == null || generatedSQL.trim().isEmpty()) {
                log.error("[generate_chart] generatedSQL 为空");
                return "{\"status\":\"error\",\"message\":\"缺少 SQL 语句\"}";
            }
            
            try {
                // 使用 SQLExecutionTool 重新执行查询，获取最新数据
                log.info("[generate_chart] 重新执行 SQL 获取数据: {}", generatedSQL);
                log.info("[generate_chart] datasourceId={}, userId={}, username={}", dsId, userId, username);
                
                SQLExecutionTool.ExecutionResult execResult = sqlExecutionTool.executeSQL(
                    generatedSQL, dsId, userId, username
                );
                
                log.info("[generate_chart] 执行结果: success={}, data={}", 
                    execResult.isSuccess(), 
                    execResult.getData() != null ? execResult.getData().size() : "null");
                
                if (!execResult.isSuccess()) {
                    log.error("[generate_chart] SQL执行失败: {}", execResult.getError());
                    return "{\"status\":\"error\",\"message\":\"SQL执行失败: " + execResult.getError() + "\"}";
                }
                
                if (execResult.getData() == null || execResult.getData().isEmpty()) {
                    log.warn("[generate_chart] 查询结果为空");
                    return "{\"status\":\"error\",\"message\":\"查询结果为空，无法生成图表\"}";
                }
                
                List<Map<String, Object>> queryData = execResult.getData();
                
                // ⚠️ 关键：如果没有指定 chartType，分析数据并推荐适合的图表类型
                if (chartType == null || chartType.trim().isEmpty()) {
                    log.info("[generate_chart] 未指定图表类型，分析数据特征");
                    List<String> recommendedTypes = analyzeAndRecommendChartTypes(queryData);
                    
                    Map<String, Object> result = new HashMap<>();
                    result.put("status", "chart_recommendation");
                    result.put("recommendedCharts", recommendedTypes);
                    result.put("datasourceId", dsId);
                    
                    com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                    return mapper.writeValueAsString(result);
                }
                
                // 生成 ECharts 配置
                Map<String, Object> echartsConfig = generateEChartsConfig(chartType, queryData);
                
                Map<String, Object> result = new java.util.HashMap<>();
                result.put("status", "chart_generated");
                result.put("chartType", getChartTypeName(chartType));
                result.put("echartsConfig", echartsConfig);
                result.put("data", queryData);
                result.put("datasourceId", dsId);  // ⚠️ 重要：返回 datasourceId 供前端后续使用
                
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                return mapper.writeValueAsString(result);
            } catch (Exception e) {
                log.error("[generate_chart] 执行失败", e);
                return "{\"status\":\"error\",\"message\":\"图表生成失败: " + e.getMessage() + "\"}";
            }
        }, "生成可视化图表。当用户消息包含 [INTENT:GENERATE_CHART] 时调用此工具，从 context 参数中获取 chartType 和 generatedSQL，工具会自动重新执行 SQL 获取数据并生成图表");
        log.info("启用 generate_chart 工具");
        
        // 注册 SQL 风险分析工具
        if (sqlRiskAnalysisTool != null) {
            agent.registerTool("analyze_sql_risk", (args, dsId, userId, username, userMessage) -> {
                String sql = (String) args.get("sql");
                // 使用传入的 datasourceId，如果没有则使用 dsId
                Long datasourceId = args.get("datasourceId") != null ? 
                    ((Number) args.get("datasourceId")).longValue() : dsId;
                return sqlRiskAnalysisTool.analyzeSQLRisk(sql, datasourceId);
            }, "在执行复杂SQL之前，先分析其执行计划和潜在风险。适用于：多表JOIN、子查询、大数据量查询等场景。返回风险等级(LOW/MEDIUM/HIGH)、风险点和优化建议。调用时必须传递参数：{\"sql\": \"SQL语句\", \"datasourceId\": 数据源ID}");
            log.info("启用 SQLRiskAnalysisTool");
        }
        
        // ✅ 注册 SQL 优化工具
        if (sqlOptimizerTool != null) {
            agent.registerTool("optimize_sql", (args, dsId, userId, username, userMessage) -> {
                String sql = (String) args.get("sql");
                Long datasourceId = args.get("datasourceId") != null ? 
                    ((Number) args.get("datasourceId")).longValue() : dsId;
                return sqlOptimizerTool.analyzeAndOptimize(sql, datasourceId);
            }, "分析SQL查询的性能问题并提供优化建议。输入SQL语句和数据源ID，返回优化建议和可能的优化后SQL。调用时必须传递参数：{\"sql\": \"SQL语句\", \"datasourceId\": 数据源ID}");
            log.info("启用 SQLOptimizerTool");
        }
        
        // ✅ 注册报告生成工具
        if (reportGeneratorTool != null) {
            agent.registerTool("generate_report", (args, dsId, userId, username, userMessage) -> {
                Map<String, Object> context = (Map<String, Object>) args.get("context");
                if (context == null) {
                    return "{\"status\":\"error\",\"message\":\"缺少上下文数据\"}";
                }
                
                String userQuery = (String) context.get("userQuery");
                String sql = (String) context.get("generatedSQL");
                String dataJson = (String) context.get("dataJson");
                
                if (sql == null || dataJson == null) {
                    return "{\"status\":\"error\",\"message\":\"缺少 SQL 或数据\"}";
                }
                
                try {
                    String report = reportGeneratorTool.generateReport(userQuery, sql, dataJson);
                    
                    Map<String, Object> result = new HashMap<>();
                    result.put("status", "success");
                    result.put("report", report);
                    result.put("datasourceId", dsId);
                    
                    com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                    return mapper.writeValueAsString(result);
                } catch (Exception e) {
                    log.error("[generate_report] 执行失败", e);
                    return "{\"status\":\"error\",\"message\":\"报告生成失败: " + e.getMessage() + "\"}";
                }
            }, "根据查询结果生成结构化的数据分析报告。当用户需要深度分析时调用此工具，从 context 参数中获取 userQuery、generatedSQL 和 dataJson，返回包含摘要、关键发现、趋势分析和业务建议的完整报告");
            log.info("启用 ReportGeneratorTool");
        }
        
        // ✅ 注册表关联关系推断工具
        if (tableRelationshipDetectionTool != null) {
            agent.registerTool("detect_table_relationships", (args, dsId, userId, username, userMessage) -> {
                Long datasourceId = args.get("datasourceId") != null ? 
                    ((Number) args.get("datasourceId")).longValue() : dsId;
                return tableRelationshipDetectionTool.detectTableRelationships(datasourceId);
            }, "自动推断数据库表之间的关联关系。结合规则引擎和LLM语义分析，返回可能的表关联。当用户询问表之间的关系或需要理解数据结构时调用。输入数据源ID");
            log.info("启用 TableRelationshipDetectionTool");
            
            agent.registerTool("quick_detect_table_relationships", (args, dsId, userId, username, userMessage) -> {
                Long datasourceId = args.get("datasourceId") != null ? 
                    ((Number) args.get("datasourceId")).longValue() : dsId;
                return tableRelationshipDetectionTool.quickDetectTableRelationships(datasourceId);
            }, "快速推断数据库表之间的关联关系（仅使用规则引擎，速度快但可能遗漏复杂关联）。适用于需要快速获取基础关联的场景。输入数据源ID");
            log.info("启用 QuickDetectTableRelationships Tool");
        }
        
        // ✅ 注册原子 Tool（供 Skill 调用）
        if (executeSQLTool != null) {
            agent.registerTool("execute_sql", (args, dsId, userId, username, userMessage) -> {
                String sql = (String) args.get("sql");
                Long datasourceId = args.get("datasourceId") != null ? 
                    ((Number) args.get("datasourceId")).longValue() : dsId;
                return executeSQLTool.executeSQL(sql, datasourceId);
            }, "执行SQL查询并返回结果。这是原子能力，供Skill内部调用。输入SQL语句和数据源ID，返回JSON格式的查询结果");
            log.info("启用 ExecuteSQLTool");
        }
        
        if (getTableMetadataTool != null) {
            agent.registerTool("get_table_metadata", (args, dsId, userId, username, userMessage) -> {
                String tableName = (String) args.get("tableName");
                Long datasourceId = args.get("datasourceId") != null ? 
                    ((Number) args.get("datasourceId")).longValue() : dsId;
                return getTableMetadataTool.getTableMetadata(tableName, datasourceId);
            }, "获取指定表的元数据信息，包括表注释、字段列表、数据类型等。这是原子能力，供Skill内部调用。输入表名和数据源ID");
            log.info("启用 GetTableMetadataTool");
        }
        
        // 注册其他原子 Tool
        if (validateSQLTool != null) {
            agent.registerTool("validate_sql", (args, dsId, userId, username, userMessage) -> {
                String sql = (String) args.get("sql");
                Long datasourceId = args.get("datasourceId") != null ? 
                    ((Number) args.get("datasourceId")).longValue() : dsId;
                return validateSQLTool.validateSQL(sql, datasourceId);
            }, "验证SQL语法和安全性。输入SQL语句和数据源ID，返回是否有效、错误信息、风险等级");
            log.info("启用 ValidateSQLTool");
        }
        
        if (analyzeQueryPlanTool != null) {
            agent.registerTool("analyze_query_plan", (args, dsId, userId, username, userMessage) -> {
                String sql = (String) args.get("sql");
                Long datasourceId = args.get("datasourceId") != null ? 
                    ((Number) args.get("datasourceId")).longValue() : dsId;
                return analyzeQueryPlanTool.analyzeQueryPlan(sql, datasourceId);
            }, "分析SQL执行计划（EXPLAIN），返回性能风险评估。输入SQL和数据源ID，返回风险等级、风险点、优化建议");
            log.info("启用 AnalyzeQueryPlanTool");
        }
        
        if (getDatabaseStatsTool != null) {
            agent.registerTool("get_database_stats", (args, dsId, userId, username, userMessage) -> {
                Long datasourceId = args.get("datasourceId") != null ? 
                    ((Number) args.get("datasourceId")).longValue() : dsId;
                String tableNames = (String) args.get("tableNames");
                return getDatabaseStatsTool.getDatabaseStats(datasourceId, tableNames);
            }, "获取数据库表统计信息，包括表大小、行数、索引数量等。输入数据源ID和可选的表名列表");
            log.info("启用 GetDatabaseStatsTool");
        }
        
        if (checkIndexTool != null) {
            agent.registerTool("check_index", (args, dsId, userId, username, userMessage) -> {
                String tableName = (String) args.get("tableName");
                Long datasourceId = args.get("datasourceId") != null ? 
                    ((Number) args.get("datasourceId")).longValue() : dsId;
                return checkIndexTool.checkIndex(tableName, datasourceId);
            }, "检查指定表的索引情况。输入表名和数据源ID，返回索引列表、字段覆盖情况");
            log.info("启用 CheckIndexTool");
        }
        
        if (getTableRelationshipsTool != null) {
            agent.registerTool("get_table_relationships", (args, dsId, userId, username, userMessage) -> {
                Long datasourceId = args.get("datasourceId") != null ? 
                    ((Number) args.get("datasourceId")).longValue() : dsId;
                String tableName = (String) args.get("tableName");
                return getTableRelationshipsTool.getTableRelationships(datasourceId, tableName);
            }, "查询已配置的表关联关系。输入数据源ID和可选的表名，返回外键关联信息");
            log.info("启用 GetTableRelationshipsTool");
        }
        
        if (formatSQLTool != null) {
            agent.registerTool("format_sql", (args, dsId, userId, username, userMessage) -> {
                String sql = (String) args.get("sql");
                return formatSQLTool.formatSQL(sql);
            }, "格式化SQL语句提高可读性。输入SQL，返回格式化后的SQL（关键字大写、适当换行缩进）");
            log.info("启用 FormatSQLTool");
        }
        
        if (compareSQLTool != null) {
            agent.registerTool("compare_sql", (args, dsId, userId, username, userMessage) -> {
                String originalSql = (String) args.get("originalSql");
                String newSql = (String) args.get("newSql");
                return compareSQLTool.compareSQL(originalSql, newSql);
            }, "对比两个SQL的差异。输入原始SQL和新SQL，返回差异分析包括字段变化、条件变化等");
            log.info("启用 CompareSQLTool");
        }
        
        if (estimateCostTool != null) {
            agent.registerTool("estimate_cost", (args, dsId, userId, username, userMessage) -> {
                String sql = (String) args.get("sql");
                Long datasourceId = args.get("datasourceId") != null ? 
                    ((Number) args.get("datasourceId")).longValue() : dsId;
                return estimateCostTool.estimateCost(sql, datasourceId);
            }, "估算SQL查询成本，包括预计扫描行数、执行时间等。输入SQL和数据源ID，返回成本评估");
            log.info("启用 EstimateCostTool");
        }
        
        log.info("NL2SQL ReAct Agent 初始化完成");
        log.info("已注册工具数量: {}", agent.getToolCount());
        
        return agent;
    }
    
    /**
     * 分析数据特征并推荐适合的图表类型
     */
    private List<String> analyzeAndRecommendChartTypes(List<Map<String, Object>> data) {
        List<String> recommendations = new ArrayList<>();
        
        if (data == null || data.isEmpty()) {
            return recommendations;
        }
        
        // 分析数据特征
        int rowCount = data.size();
        int columnCount = data.get(0).size();
        
        // 检查是否有数值列和分类列
        boolean hasNumericColumn = false;
        boolean hasCategoryColumn = false;
        String categoryKey = null;
        
        for (Map.Entry<String, Object> entry : data.get(0).entrySet()) {
            if (entry.getValue() instanceof Number) {
                hasNumericColumn = true;
            } else if (entry.getValue() instanceof String) {
                hasCategoryColumn = true;
                if (categoryKey == null) {
                    categoryKey = entry.getKey();
                }
            }
        }
        
        // 根据数据特征推荐
        if (hasCategoryColumn && hasNumericColumn) {
            // 有分类和数值：适合柱状图、饼图
            recommendations.add("bar");  // 柱状图
            
            if (rowCount <= 10) {
                recommendations.add("pie");  // 数据量少时适合饼图
            }
            
            if (rowCount >= 3) {
                recommendations.add("line");  // 数据点多时适合折线图
            }
        } else if (hasNumericColumn && !hasCategoryColumn) {
            // 只有数值：适合折线图（趋势）
            recommendations.add("line");
        } else if (hasCategoryColumn && !hasNumericColumn) {
            // 只有分类：适合饼图（占比）
            if (rowCount <= 10) {
                recommendations.add("pie");
            }
        }
        
        // 默认至少返回柱状图
        if (recommendations.isEmpty()) {
            recommendations.add("bar");
        }
        
        log.info("[analyzeAndRecommendChartTypes] 推荐图表类型: {}", recommendations);
        return recommendations;
    }
    
    /**
     * 生成 ECharts 配置
     */
    private Map<String, Object> generateEChartsConfig(String chartType, List<Map<String, Object>> data) {
        if (data == null || data.isEmpty()) {
            return new HashMap<>();
        }
        
        Map<String, Object> config = new HashMap<>();
        config.put("type", chartType);
        
        // 提取 categories 和 values
        List<String> categories = new ArrayList<>();
        List<Object> values = new ArrayList<>();
        
        // 假设第一列是分类，第二列是数值
        String categoryKey = null;
        String valueKey = null;
        
        if (!data.isEmpty()) {
            Set<String> keys = data.get(0).keySet();
            Iterator<String> iterator = keys.iterator();
            if (iterator.hasNext()) categoryKey = iterator.next();
            if (iterator.hasNext()) valueKey = iterator.next();
        }
        
        if (categoryKey != null && valueKey != null) {
            for (Map<String, Object> row : data) {
                categories.add(String.valueOf(row.get(categoryKey)));
                values.add(row.get(valueKey));
            }
        }
        
        config.put("categories", categories);
        config.put("values", values);
        config.put("title", getChartTypeName(chartType));
        
        return config;
    }
    
    /**
     * 获取图表类型中文名
     */
    private String getChartTypeName(String chartType) {
        switch (chartType) {
            case "bar": return "柱状图";
            case "line": return "折线图";
            case "pie": return "饼图";
            case "area": return "面积图";
            default: return "图表";
        }
    }
}
