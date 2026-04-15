package com.nl2sql.core.agent;

import com.nl2sql.core.agent.skills.GroovySkillExecutor;
import com.nl2sql.core.agent.skills.SkillContext;
import com.nl2sql.core.agent.skills.SkillsMetadataLoader;
import com.nl2sql.core.agent.tools.*;
import com.nl2sql.core.llm.LLMService;
import dev.langchain4j.model.chat.ChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;
import java.util.Set;

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
    private com.nl2sql.core.monitor.PerformanceMonitor performanceMonitor;
    
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
        log.info("初始化 NL2SQL ReAct Agent...");
        
        ChatModel model = llmService.getChatModel();
        ReActAgent agent = new ReActAgent(model);
        
        // 注入 Skills 元数据加载器（如果存在）
        if (skillsMetadataLoader != null) {
            agent.setSkillsMetadataLoader(skillsMetadataLoader);
            log.info("✅ 已注入 Skills 元数据加载器");
        } else {
            log.warn("⚠️ 未找到 Skills 元数据加载器，将使用默认 SystemMessage");
        }
        
        // ⚠️ P0优化：注入性能监控器
        if (performanceMonitor != null) {
            agent.setPerformanceMonitor(performanceMonitor);
            log.info("✅ 已注入性能监控器");
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
                        
                        String question = (String) args.get("question");
                        
                        // ⚠️ 关键修复：处理 datasourceId 为 null 的情况
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
                        
                        Object result = groovySkillExecutor.executeSkill(skill.getSkillPath(), context);
                        
                        // 转换为 JSON
                        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                        return mapper.writeValueAsString(result);
                        
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
                // 从 context 中获取 SQL
                Map<String, Object> context = (Map<String, Object>) args.get("context");
                if (context == null) {
                    return "{\"status\":\"error\",\"message\":\"缺少上下文数据\"}";
                }
                
                String lastQuery = (String) context.get("lastQuery");
                String generatedSQL = (String) context.get("generatedSQL");
                
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
                    Map<String, Object> result = new java.util.HashMap<>();
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
            
            // 从 context 中获取图表类型和 SQL
            Map<String, Object> context = (Map<String, Object>) args.get("context");
            if (context == null) {
                log.error("[generate_chart] context 为 null");
                return "{\"status\":\"error\",\"message\":\"缺少上下文数据\"}";
            }
            
            String chartType = (String) context.get("chartType");
            String generatedSQL = (String) context.get("generatedSQL");
            
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
                    
                    Map<String, Object> result = new java.util.HashMap<>();
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
        
        log.info("NL2SQL ReAct Agent 初始化完成");
        log.info("已注册工具数量: {}", agent.getToolCount());
        
        return agent;
    }
    
    /**
     * 分析数据特征并推荐适合的图表类型
     */
    private List<String> analyzeAndRecommendChartTypes(List<Map<String, Object>> data) {
        List<String> recommendations = new java.util.ArrayList<>();
        
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
            return new java.util.HashMap<>();
        }
        
        Map<String, Object> config = new java.util.HashMap<>();
        config.put("type", chartType);
        
        // 提取 categories 和 values
        List<String> categories = new java.util.ArrayList<>();
        List<Object> values = new java.util.ArrayList<>();
        
        // 假设第一列是分类，第二列是数值
        String categoryKey = null;
        String valueKey = null;
        
        if (!data.isEmpty()) {
            java.util.Set<String> keys = data.get(0).keySet();
            java.util.Iterator<String> iterator = keys.iterator();
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
