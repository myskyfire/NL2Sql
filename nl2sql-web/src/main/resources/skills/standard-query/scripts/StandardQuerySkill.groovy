import com.nl2sql.core.agent.skills.SkillContext
import com.nl2sql.core.service.NL2SQLService
import com.nl2sql.core.service.SchemaRetrievalService
import com.nl2sql.core.agent.tools.SQLExecutionTool
import com.nl2sql.core.executor.SQLRiskAnalyzer
import com.nl2sql.core.llm.LLMService
import com.nl2sql.common.event.StreamProgressEvent
import dev.langchain4j.model.chat.ChatModel
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher

/**
 * @Deprecated Groovy skill scripts are deprecated. Use YAML workflow instead.
 * 
 * 标准查询技能
 * 
 * 封装完整的查询生命周期：
 * 1. 检索表结构
 * 2. 生成SQL
 * 3. SQL优化与风险评估
 * 4. 执行SQL（带自动修正）
 */
@Deprecated
class StandardQuerySkill {
    
    private static final def log = LoggerFactory.getLogger(StandardQuerySkill.class)
    
    /**
     * 执行标准查询
     * 
     * @param context 执行上下文
     * @return 查询结果
     */
    def execute(SkillContext context) {
        String question = context.getParameter("question")
        Long datasourceId = context.getParameter("datasourceId")
        Long userId = context.getParameter("userId")
        String username = context.getParameter("username")
        String sessionId = context.getParameter("sessionId")
        Boolean sqlOnly = context.getParameter("sqlOnly") != null ? 
            (Boolean) context.getParameter("sqlOnly") : false  // ✅ 默认执行SQL
        
        println "[StandardQuerySkill] 开始执行标准查询: question=${question}, datasourceId=${datasourceId}"
        
        // ✅ 用于保存中风险结果，后续添加到返回结果中
        RiskAssessmentResult mediumRiskResult = null
        
        // ✅ 关键检查：如果缺少 datasourceId，返回澄清请求
        if (datasourceId == null) {
            println "[StandardQuerySkill] 缺少 datasourceId，返回澄清请求"
            publishEvent(context, sessionId, "clarification_needed", "⚠️ 请先选择数据源")
            return createClarificationResult("请先选择数据源")
        }
        
        try {
            // ✅ 新架构：通过 callTool() 调用原子能力，而不是直接获取 Bean
            // 保留旧方式作为兼容（后续逐步迁移）
            NL2SQLService nl2sqlService = context.getBean(NL2SQLService.class)
            SchemaRetrievalService schemaRetrievalService = context.getBean(SchemaRetrievalService.class)
            SQLExecutionTool sqlExecutionTool = context.getBean(SQLExecutionTool.class)
            LLMService llmService = context.getBean(LLMService.class)
            SQLRiskAnalyzer riskAnalyzer = context.getBean(SQLRiskAnalyzer.class)
            
            // ✅ 行业概念扩展点（可选）
            def conceptExtensions = []

            def appContext = context.getClass().getDeclaredField("applicationContext")
            appContext.setAccessible(true)
            def applicationContext = appContext.get(context)
            conceptExtensions = applicationContext.getBeansOfType(com.nl2sql.core.llm.extension.IndustryConceptExtension.class).values()
            println "[StandardQuerySkill] 加载到 ${conceptExtensions.size()} 个行业扩展点"

            // ✅ 检测用户是否要求生成图表（如“并生成柱状图”）
            String chartType = extractChartTypeFromQuestion(question)
            if (chartType != null) {
                println "[StandardQuerySkill] 检测到图表生成意图: ${chartType}"
                publishEvent(context, sessionId, "chart_intent_detected", "📊 检测到图表需求: ${getChartTypeName(chartType)}")
                // 从问题中移除图表相关描述，保留纯查询部分
                question = removeChartDescription(question)
                println "[StandardQuerySkill] 清理后的问题: ${question}"
            }
                        
            // Step 1: 检索表结构
            println "[StandardQuerySkill] Step 1: 检索表结构"
            publishEvent(context, sessionId, "retrieving_schema", "🔍 检索表结构...")
            String schema = schemaRetrievalService.retrieveSchema(question, datasourceId)
            publishEvent(context, sessionId, "schema_retrieved", "✅ 表结构检索完成")
            
            // ✅ 关键优化：从schema中提取表名，设置到ThreadLocal供TableSelectionOrchestrator复用
            List<String> retrievedTables = extractTableNamesFromSchema(schema)
            if (retrievedTables != null && !retrievedTables.isEmpty()) {
                com.nl2sql.core.service.TableSelectionOrchestrator.setPreRetrievedTables(retrievedTables)
                println "[StandardQuerySkill] ⚡ 已设置预检索表列表: ${retrievedTables}"
            }
                        
            // Step 2: 生成SQL
            println "[StandardQuerySkill] Step 2: 生成SQL"
            publishEvent(context, sessionId, "generating_sql", "🤖 AI生成SQL...")
            
            // ✅ 关键修复：从用户问题中提取表名偏好
            String tableHint = extractTablePreference(question)
            if (tableHint) {
                println "[StandardQuerySkill] 检测到用户指定表: ${tableHint}"
                question = "${question} [优先使用表: ${tableHint}]"
            }
            
            // ✅ 调用行业扩展点：在LLM生成SQL前注入行业特定的提示词
            String enhancedQuestion = question
            if (!conceptExtensions.isEmpty()) {
                for (def extension : conceptExtensions) {
                    try {
                        String enhancedPrompt = extension.enhancePromptBeforeGeneration(null, question, datasourceId)
                        if (enhancedPrompt != null && !enhancedPrompt.trim().isEmpty()) {
                            println "[StandardQuerySkill] 行业扩展点增强Prompt: ${extension.getClass().getSimpleName()}"
                            // 将增强信息附加到问题中
                            enhancedQuestion = "${question}\n\n${enhancedPrompt}"
                            break // 只应用第一个有效的扩展
                        }
                    } catch (Exception e) {
                        println "[StandardQuerySkill] 行业扩展点执行失败: ${extension.getClass().getSimpleName()}, error: ${e.message}"
                    }
                }
            }
            
            String sql = nl2sqlService.generateSQL(enhancedQuestion, datasourceId)
            
            // ✅ 关键修复：检查 SQL 生成是否失败或需要澄清
            boolean hasSyntaxError = false
            boolean needsClarification = false
            
            if (sql == null || sql.trim().isEmpty() || sql.startsWith("错误：") || sql.startsWith("ERROR:")) {
                log.warn("SQL生成失败，将尝试自动修正: {}", sql)
                hasSyntaxError = true
                publishEvent(context, sessionId, "sql_generation_failed", "⚠️ SQL生成失败，尝试修正...")
            } else if (sql.startsWith("CLARIFY_") || sql.startsWith("CLARIFICATION")) {
                log.info("SQL需要澄清，直接返回: {}", sql)
                needsClarification = true
                publishEvent(context, sessionId, "clarification_needed", "⚠️ " + sql)
            } else {
                publishEvent(context, sessionId, "sql_generated", "✅ SQL生成完成")
            }
            
            // 如果需要澄清，直接返回
            if (needsClarification) {
                return createClarificationResult(sql)
            }
            
            // SQL后处理：检测并修复IN子查询关联（仅当SQL有效时）
            if (!hasSyntaxError) {
                sql = optimizeSQL(sql)
            }
            
            // Step 2.5: LLM自主评估SQL风险（仅当SQL有效时）
            if (!hasSyntaxError) {
                log.info("Step 2.5: 评估SQL风险")
                publishEvent(context, sessionId, "assessing_risk", "🔍 评估SQL风险...")
                
                RiskAssessmentResult riskResult
                if (sqlOnly) {
                    // ✅ 离线模式：仅使用静态规则校验，不执行 EXPLAIN
                    log.info("✅ 离线模式：跳过 EXPLAIN，使用静态规则校验")
                    riskResult = staticRiskAssessment(sql)
                } else {
                    // ✅ 在线模式：完整风险评估（EXPLAIN + LLM）
                    riskResult = assessSQLRisk(sql, question, datasourceId, llmService, riskAnalyzer, nl2sqlService, context, sessionId)
                }
                
                if ("HIGH".equals(riskResult.getRiskLevel())) {
                    log.warn("SQL风险评估为高风险，进入人机协同确认: {}", riskResult.getReason())
                    publishEvent(context, sessionId, "risk_blocked", "⚠️ 高风险SQL需要人工确认")
                    
                    // ✅ 关键修复：如果经过 LLM 优化，返回优化后的 SQL；否则返回原始 SQL
                    String sqlToReturn = riskResult.getOptimizedSql() != null ? riskResult.getOptimizedSql() : sql
                    
                    // ✅ 构建优化建议/风险原因
                    String optimizationSuggestion = buildOptimizationSuggestionForFrontend(riskResult)
                    
                    // ✅ 改为等待用户确认（而非直接阻断）
                    return createHumanApprovalRequiredResult(riskResult.getReason(), sqlToReturn, optimizationSuggestion, sessionId)
                } else if ("MEDIUM".equals(riskResult.getRiskLevel())) {
                    log.info("SQL风险评估为中风险，继续执行但提示用户: {}", riskResult.getReason())
                    publishEvent(context, sessionId, "risk_medium", "⚠️ 中风险SQL，继续执行")
                    
                    // ✅ 保存风险结果，用于后续添加到返回结果中
                    mediumRiskResult = riskResult
                } else {
                    log.debug("SQL风险评估为低风险，直接执行")
                    publishEvent(context, sessionId, "risk_low", "✅ 风险评估通过")
                }
            } else {
                log.info("SQL生成失败，跳过风险评估，直接进入修正流程")
            }
            
            // 检查是否需要澄清
            if (sql.startsWith("CLARIFY_") || sql.startsWith("CLARIFICATION")) {
                log.info("需要澄清: {}", sql)
                return createClarificationResult(sql)
            }
            
            // Step 3: 执行SQL（带自动修正，最多2次）
            if (sqlOnly) {
                // ✅ 离线模式：仅返回SQL，不执行
                log.info("✅ 离线模式：仅生成SQL，不执行")
                publishEvent(context, sessionId, "sql_generated_only", "✅ SQL生成完成（离线模式）")
                
                // ✅ 如果有中风险结果，构建优化建议
                String optimizationSuggestion = mediumRiskResult != null ? buildOptimizationSuggestionForFrontend(mediumRiskResult) : null
                
                return createSuccessResult(null, 0, 0, sql, datasourceId, optimizationSuggestion)
            }
            
            log.info("Step 3: 执行SQL")
            publishEvent(context, sessionId, "executing_sql", "⚙️ 执行SQL查询...")
            
            def execResult = executeWithAutoFix(sql, datasourceId, userId, username, 2, nl2sqlService, sqlExecutionTool, context, sessionId)
            
            if (!execResult.success) {
                log.error("执行失败: {}", execResult.error)
                publishEvent(context, sessionId, "execution_failed", "❌ 执行失败: " + execResult.error)
                return createExecutionFailedResult(execResult.error)
            }
            
            log.info("查询成功: rowCount={}", execResult.rowCount)
            publishEvent(context, sessionId, "query_completed", "✅ 查询完成，共 " + execResult.rowCount + " 条结果")
            
            // ✅ 如果用户要求生成图表，直接在返回中包含图表配置
            if (chartType != null && execResult.data != null && !execResult.data.isEmpty()) {
                log.info("生成图表配置: type={}", chartType)
                publishEvent(context, sessionId, "generating_chart", "📊 生成" + getChartTypeName(chartType) + "...")
                Map<String, Object> echartsConfig = generateEChartsConfig(chartType, execResult.data)
                publishEvent(context, sessionId, "chart_generated", "✅ 图表生成完成")
                
                // ✅ 如果有中风险结果，构建优化建议
                String optimizationSuggestion = mediumRiskResult != null ? buildOptimizationSuggestionForFrontend(mediumRiskResult) : null
                
                return createSuccessResultWithChart(
                    execResult.data, 
                    execResult.rowCount, 
                    execResult.executionTime, 
                    sql, 
                    datasourceId,
                    chartType,
                    echartsConfig,
                    optimizationSuggestion
                )
            }
            
            // ✅ 如果有中风险结果，构建优化建议
            String optimizationSuggestion = mediumRiskResult != null ? buildOptimizationSuggestionForFrontend(mediumRiskResult) : null
            
            return createSuccessResult(execResult.data, execResult.rowCount, execResult.executionTime, sql, datasourceId, optimizationSuggestion)
            
        } catch (Exception e) {
            log.error("执行异常: {}", e.message, e)
            publishEvent(context, sessionId, "error_occurred", "❌ 执行异常: " + e.message)
            return createErrorResult(e.message)
        }
    }
    
    /**
     * SQL后处理：检测并修复IN子查询关联
     */
    private String optimizeSQL(String sql) {
        if (sql == null || !sql.contains(" IN (") || !sql.contains("SELECT")) {
            return sql
        }
        
        // 简单检测：如果ON条件中有IN子查询，记录警告
        if (sql.matches("(?s).*JOIN.*ON.*\\bIN\\s*\\(\\s*SELECT.*")) {
            log.warn("检测到ON条件中使用IN子查询，建议改为直接JOIN")
        }
        
        return sql
    }
    
    /**
     * ✅ 为前端构建优化建议/风险原因
     * 
     * @param riskResult 风险评估结果
     * @return 前端友好的优化建议文本
     */
    private String buildOptimizationSuggestionForFrontend(RiskAssessmentResult riskResult) {
        if (riskResult == null) {
            return null
        }
        
        StringBuilder suggestion = new StringBuilder()
        
        if ("HIGH".equals(riskResult.getRiskLevel())) {
            // 🔴 高风险：返回风险原因和优化后的 SQL（如果有）
            suggestion.append("⚠️ 高风险SQL，已阻断执行\n\n")
            suggestion.append("📋 风险原因：\n")
            suggestion.append(riskResult.getReason()).append("\n\n")
            
            if (riskResult.getOptimizedSql() != null && !riskResult.getOptimizedSql().trim().isEmpty()) {
                suggestion.append("💡 LLM 已尝试优化，生成新 SQL：\n")
                suggestion.append(riskResult.getOptimizedSql()).append("\n\n")
                suggestion.append("👉 请人工审核上述优化后的 SQL，确认安全后再执行。")
            } else {
                suggestion.append("👉 请人工审核原始 SQL，确认安全后再执行。")
            }
            
        } else if ("MEDIUM".equals(riskResult.getRiskLevel())) {
            // ⚠️ 中风险：返回 LLM 优化建议
            suggestion.append("⚠️ 中风险SQL，继续执行但请注意以下优化建议：\n\n")
            
            if (riskResult.getLlmSuggestion() != null) {
                OptimizationSuggestion llmSuggestion = riskResult.getLlmSuggestion()
                
                if (llmSuggestion.getBottleneck() != null && !llmSuggestion.getBottleneck().trim().isEmpty()) {
                    suggestion.append("🔍 性能瓶颈：\n")
                    suggestion.append(llmSuggestion.getBottleneck()).append("\n\n")
                }
                
                if (llmSuggestion.getSuggestion() != null && !llmSuggestion.getSuggestion().trim().isEmpty()) {
                    suggestion.append("💡 优化建议：\n")
                    suggestion.append(llmSuggestion.getSuggestion()).append("\n\n")
                }
                
                if (llmSuggestion.getExpectedImprovement() != null && !llmSuggestion.getExpectedImprovement().trim().isEmpty()) {
                    suggestion.append("📈 预期效果：\n")
                    suggestion.append(llmSuggestion.getExpectedImprovement())
                }
            } else {
                // 如果没有 LLM 建议，使用 EXPLAIN 的风险信息
                suggestion.append("📋 风险点：\n")
                suggestion.append(riskResult.getReason())
            }
        }
        
        return suggestion.toString()
    }
    
    /**
     * 评估SQL风险（EXPLAIN优先 + LLM辅助优化）
     * 
     * 完整流程：
     * 0. 快速判断 → 简单查询直接放行（不调用 EXPLAIN）
     * 1. EXPLAIN 分析 → 获取客观风险等级
     * 2. LOW → 直接执行
     * 3. MEDIUM → 调用 LLM 获取优化建议（仅供参考，仍执行原 SQL）
     * 4. HIGH → 调用 LLM 重新生成 SQL（告知原 SQL 和风险）→ 重新 EXPLAIN
     *    - 如果优化后不是 HIGH → 使用新 SQL 执行
     *    - 如果优化后仍是 HIGH → 只返回优化后的 SQL 给前端，标注高风险，需要人工介入，绝对不执行
     */
    private RiskAssessmentResult assessSQLRisk(String sql, String question, Long datasourceId, 
                                                LLMService llmService, SQLRiskAnalyzer riskAnalyzer,
                                                NL2SQLService nL2SQLService, SkillContext context, String sessionId) {
        try {
            // ✅ 关键修复：如果 SQL 是澄清消息或错误消息，直接返回低风险
            if (sql.startsWith("CLARIFICATION") || sql.startsWith("CLARIFY_") || 
                sql.startsWith("错误：") || sql.startsWith("ERROR:")) {
                log.info("SQL不是有效查询，跳过风险评估")
                return new RiskAssessmentResult("LOW", "非有效SQL，无需风险评估")
            }
            
            if (riskAnalyzer == null) {
                log.warn("⚠️ SQLRiskAnalyzer 未注入，跳过风险评估")
                return new RiskAssessmentResult("LOW", "SQLRiskAnalyzer未配置，默认低风险")
            }
            
            // Step 0: 快速判断 - 简单查询直接放行（不调用 EXPLAIN）
            if (isSimpleQuery(sql)) {
                log.info("✅ 快速判断：简单查询，直接放行（跳过 EXPLAIN）")
                return new RiskAssessmentResult("LOW", "简单查询，无需 EXPLAIN")
            }
            
            // Step 1: 执行 EXPLAIN 分析（必做）
            log.info("Step 1: 执行 EXPLAIN 分析")
            SQLRiskAnalyzer.RiskAnalysisResult explainResult = riskAnalyzer.analyzeRisk(sql, datasourceId)
            String riskLevel = explainResult.getRiskLevel()
            
            log.info("EXPLAIN 分析结果: riskLevel={}, risks={}", riskLevel, explainResult.getRisks()?.size() ?: 0)
            
            // Step 2: 根据风险等级处理
            if ("LOW".equals(riskLevel)) {
                // ✅ 低风险，直接执行
                log.info("✅ EXPLAIN 评估为低风险，直接执行")
                return new RiskAssessmentResult("LOW", "EXPLAIN 分析无风险")
            } else if ("MEDIUM".equals(riskLevel)) {
                // ⚠️ 中风险，调用 LLM 获取优化建议（仅供参考）
                log.info("⚠️ EXPLAIN 评估为中风险，调用 LLM 获取优化建议")
                
                String suggestionPrompt = buildOptimizationSuggestionPrompt(sql, question, explainResult)
                String llmResponse = llmService.generateAnswer(suggestionPrompt)
                
                OptimizationSuggestion suggestion = parseOptimizationSuggestion(llmResponse)
                
                RiskAssessmentResult result = new RiskAssessmentResult("MEDIUM", explainResult.getRisks().join("; "))
                result.setLlmSuggestion(suggestion)
                result.setOriginalSql(sql)
                
                log.info("✅ LLM 优化建议: {}", suggestion?.getSuggestion())
                return result
                
            } else if ("HIGH".equals(riskLevel)) {
                // 🔴 高风险，调用 LLM 重新生成 SQL
                log.info("🔴 EXPLAIN 评估为高风险，调用 LLM 重新生成 SQL")
                
                String regeneratePrompt = buildRegenerateSQLPrompt(sql, question, explainResult)
                String regeneratedSql = llmService.generateAnswer(regeneratePrompt)
                
                // 提取 SQL（LLM 可能返回 Markdown 或其他格式）
                regeneratedSql = extractSQLFromResponse(regeneratedSql)
                
                log.info("LLM 重新生成的 SQL: {}", regeneratedSql)
                
                // 重新 EXPLAIN 验证优化效果
                log.info("Step 2: 重新 EXPLAIN 验证优化后的 SQL")
                SQLRiskAnalyzer.RiskAnalysisResult optimizedExplain = riskAnalyzer.analyzeRisk(regeneratedSql, datasourceId)
                
                log.info("优化后 EXPLAIN 结果: riskLevel={}, risks={}", optimizedExplain.getRiskLevel(), optimizedExplain.getRisks()?.size() ?: 0)
                
                // 判断优化后的风险等级
                if ("HIGH".equals(optimizedExplain.getRiskLevel())) {
                    // ⚠️ 优化后仍为高风险，只返回 SQL 给前端，标注高风险，需要人工介入，绝对不执行
                    log.warn("⚠️ LLM 优化后仍为高风险，只返回 SQL 给前端，需要人工审核，绝对不执行")
                    log.warn("   原始 SQL: {}", sql)
                    log.warn("   优化后 SQL: {}", regeneratedSql)
                    log.warn("   风险原因: {}", explainResult.getRisks().join("; "))
                    publishEvent(context, sessionId, "risk_high_blocked", "⚠️ 高风险SQL，已阻断执行，请人工审核")
                    
                    RiskAssessmentResult result = new RiskAssessmentResult(
                        "HIGH", 
                        "LLM 优化后仍为高风险，需要人工介入审核。原始风险：" + explainResult.getRisks().join("; ")
                    )
                    result.setOriginalSql(sql)
                    result.setOptimizedSql(regeneratedSql)  // 返回优化后的 SQL 供人工审核
                    result.setOptimizationApplied(true)
                    result.setRequiresManualReview(true)  // 标记需要人工审核
                    
                    return result
                } else {
                    // ✅ 优化成功，风险降低，使用新 SQL
                    log.info("✅ LLM 优化成功，风险从 HIGH 降到 {}", optimizedExplain.getRiskLevel())
                    
                    RiskAssessmentResult result = new RiskAssessmentResult(
                        optimizedExplain.getRiskLevel(), 
                        optimizedExplain.getRisks().join("; ")
                    )
                    result.setOriginalSql(sql)
                    result.setOptimizedSql(regeneratedSql)
                    result.setOptimizationApplied(true)
                    
                    return result
                }
            } else {
                // 未知风险等级，默认低风险
                log.warn("⚠️ 未知风险等级: {}，默认低风险", riskLevel)
                return new RiskAssessmentResult("LOW", "未知风险等级，默认继续执行")
            }
            
        } catch (Exception e) {
            log.error("❌ 风险评估失败: {}", e.message, e)
            
            // ✅ 离线模式降级：使用静态规则校验
            log.info("✅ 降级为静态规则校验（离线模式）")
            return staticRiskAssessment(sql)
        }
    }
    
    /**
     * ✅ 新增：静态 SQL 风险评估（无需连库，适用于离线模式）
     */
    private RiskAssessmentResult staticRiskAssessment(String sql) {
        if (sql == null || sql.isEmpty()) {
            return new RiskAssessmentResult("LOW", "空 SQL")
        }
        
        String upperSql = sql.toUpperCase().trim()
        List<String> risks = []
        String riskLevel = "LOW"
        
        // 规则1: 检测全表扫描风险（无 WHERE 条件）
        if (upperSql.startsWith("SELECT") && !upperSql.contains("WHERE")) {
            // 检查是否有 LIMIT 限制
            if (!upperSql.contains("LIMIT")) {
                risks.add("⚠️ 无 WHERE 条件且无 LIMIT，可能导致全表扫描")
                riskLevel = "MEDIUM"
            }
        }
        
        // 规则2: 检测多表 JOIN 复杂度
        int joinCount = 0
        if (upperSql.contains(" JOIN ")) {
            joinCount = upperSql.split(" JOIN ").length - 1
            if (joinCount >= 3) {
                risks.add("🔴 多表 JOIN（${joinCount}个），性能风险高")
                riskLevel = "HIGH"
            } else if (joinCount >= 2) {
                risks.add("⚠️ 多表 JOIN（${joinCount}个），建议优化")
                if ("MEDIUM".compareTo(riskLevel) > 0) {
                    riskLevel = "MEDIUM"
                }
            }
        }
        
        // 规则3: 检测子查询嵌套
        int selectCount = 0
        for (int i = 0; i < upperSql.length(); i++) {
            if (upperSql.substring(i).startsWith("SELECT")) {
                selectCount++
            }
        }
        if (selectCount >= 3) {
            risks.add("🔴 多层子查询嵌套（${selectCount}层），性能差")
            riskLevel = "HIGH"
        } else if (selectCount == 2) {
            risks.add("⚠️ 包含子查询，建议优化为 JOIN")
            if ("MEDIUM".compareTo(riskLevel) > 0) {
                riskLevel = "MEDIUM"
            }
        }
        
        // 规则4: 检测危险操作
        if (upperSql.contains("DROP ") || upperSql.contains("TRUNCATE ") || 
            upperSql.contains("DELETE FROM") || upperSql.contains("UPDATE ")) {
            risks.add("🔴 包含数据修改/删除操作，禁止执行")
            riskLevel = "HIGH"
        }
        
        // 规则5: 检测大结果集风险（无 LIMIT 的复杂查询）
        if ((joinCount >= 2 || selectCount >= 2) && !upperSql.contains("LIMIT")) {
            risks.add("⚠️ 复杂查询无 LIMIT，可能返回大量数据")
            if ("MEDIUM".compareTo(riskLevel) > 0) {
                riskLevel = "MEDIUM"
            }
        }
        
        String reason = risks.isEmpty() ? "静态校验通过" : risks.join("; ")
        log.info("[静态校验] riskLevel={}, risks={}", riskLevel, risks.size())
        
        return new RiskAssessmentResult(riskLevel, reason)
    }
    
    /**
     * 快速判断是否为简单查询（无需 EXPLAIN）
     * 
     * 规则：
     * 1. 单表查询（无 JOIN）
     * 2. WHERE 条件包含主键等值查询（id = ?）
     * 3. 无子查询
     * 4. 无聚合函数（COUNT/SUM/AVG 等）
     * 5. 无 GROUP BY / ORDER BY / DISTINCT
     * 6. 有 LIMIT 限制（可选，但推荐）
     */
    private boolean isSimpleQuery(String sql) {
        if (sql == null || sql.isEmpty()) {
            return false
        }
        
        String upperSql = sql.toUpperCase().trim()
        
        // 只处理 SELECT 语句
        if (!upperSql.startsWith("SELECT")) {
            return false
        }
        
        // 规则1: 无 JOIN
        if (upperSql.contains(" JOIN ") || upperSql.contains("JOIN\n") || upperSql.contains("JOIN ")) {
            return false
        }
        
        // 规则2: 无子查询（检查是否有嵌套 SELECT）
        int selectCount = 0
        for (int i = 0; i < upperSql.length(); i++) {
            if (upperSql.substring(i).startsWith("SELECT")) {
                selectCount++
            }
        }
        if (selectCount > 1) {
            return false
        }
        
        // 规则3: 无聚合函数
        if (upperSql.contains("COUNT(") || 
            upperSql.contains("SUM(") || 
            upperSql.contains("AVG(") || 
            upperSql.contains("MAX(") || 
            upperSql.contains("MIN(") ||
            upperSql.contains("GROUP BY")) {
            return false
        }
        
        // 规则4: 无 ORDER BY / DISTINCT
        if (upperSql.contains("ORDER BY") || upperSql.contains("DISTINCT")) {
            return false
        }
        
        // 规则5: WHERE 条件包含主键等值查询（id = ? 或 id = 数字）
        // 匹配模式：WHERE xxx_id = 数字 或 WHERE id = 数字
        def primaryKeyPattern = java.util.regex.Pattern.compile("WHERE\\s+\\w*_?id\\s*=\\s*\\d+", java.util.regex.Pattern.CASE_INSENSITIVE)
        def matcher = primaryKeyPattern.matcher(sql)
        if (!matcher.find()) {
            return false
        }
        
        // ✅ 所有规则通过，判定为简单查询
        return true
    }
    
    /**
     * 从用户问题中提取表名偏好
     */
    private String extractTablePreference(String question) {
        if (!question) return null
        
        // 匹配模式："使用XX表"、"用XX表"、"从XX表"
        def patterns = [
            /使用(\w+)表/,
            /用(\w+)表/,
            /从(\w+)表/,
            /基于(\w+)表/
        ]
        
        for (pattern in patterns) {
            def matcher = question =~ pattern
            if (matcher.find()) {
                return matcher.group(1)
            }
        }
        
        return null
    }
    
    /**
     * 构建风险评估Prompt
     */
    private String buildRiskAssessmentPrompt(String sql, String question) {
        return """你是一个数据库专家。请评估以下SQL语句的执行风险。

用户问题：${question}

生成的SQL：
${sql}

⚠️ **核心原则：你无法知道表的实际大小、索引情况、数据分布！**
⚠️ **因此，对于任何涉及JOIN、子查询、聚合的SQL，你必须返回 UNCERTAIN！**

请从以下维度评估（仅用于判断是否需要EXPLAIN）：
1. 是否包含 JOIN 操作？
2. 是否包含子查询？
3. 是否包含 GROUP BY / ORDER BY / DISTINCT？
4. WHERE 条件是否有明确的索引字段？
5. 是否可能返回大量数据（无LIMIT）？

🔴 **强制规则（必须遵守）：**
- 如果 SQL 包含 **任何 JOIN 操作** → 必须返回 UNCERTAIN
- 如果 SQL 包含 **子查询** → 必须返回 UNCERTAIN
- 如果 SQL 包含 **GROUP BY / ORDER BY** → 必须返回 UNCERTAIN
- 如果你**不确定表的大小或索引情况** → 必须返回 UNCERTAIN
- **宁可过度谨慎，也不要误判为低风险**
- **只有当 SQL 是简单的单表查询且有明确WHERE条件时，才能返回 LOW**

返回JSON格式：
{
  "risk_level": "LOW/MEDIUM/HIGH/UNCERTAIN",
  "reason": "简要说明原因，如果是UNCERTAIN请说明需要EXPLAIN验证什么",
  "can_self_fix": true/false,
  "fix_suggestion": "如果可以自修复，给出建议"
}

注意：
- HIGH: 可能导致性能问题或超时，建议阻断
- MEDIUM: 有一定风险但可以接受
- LOW: 无明显风险（仅限简单单表查询）
- UNCERTAIN: **无法确定，必须通过EXPLAIN验证（这是最常见的情况）**"""
    }
    
    /**
     * 构建结合EXPLAIN结果的Prompt
     */
    private String buildRefinedRiskPrompt(String sql, String question, SQLRiskAnalyzer.RiskAnalysisResult explainResult) {
        StringBuilder sb = new StringBuilder()
        sb.append("你是一个数据库专家。之前你无法确定SQL的风险等级，现在提供EXPLAIN分析结果，请重新评估。\n\n")
        sb.append("用户问题：").append(question).append("\n\n")
        sb.append("SQL：\n").append(sql).append("\n\n")
        sb.append("EXPLAIN分析结果：\n")
        sb.append("- 风险等级：").append(explainResult.getRiskLevel()).append("\n")
        if (explainResult.getRisks() != null && !explainResult.getRisks().isEmpty()) {
            sb.append("- 发现的风险点：\n")
            for (String risk : explainResult.getRisks()) {
                sb.append("  - ").append(risk).append("\n")
            }
        }
        if (explainResult.getSuggestions() != null && !explainResult.getSuggestions().isEmpty()) {
            sb.append("- 优化建议：\n")
            for (String suggestion : explainResult.getSuggestions()) {
                sb.append("  - ").append(suggestion).append("\n")
            }
        }
        sb.append("\n请根据以上信息，重新评估风险等级。\n\n")
        sb.append("返回JSON格式：\n")
        sb.append("{\n")
        sb.append("  \"risk_level\": \"LOW/MEDIUM/HIGH\",\n")
        sb.append("  \"reason\": \"简要说明原因\",\n")
        sb.append("  \"can_self_fix\": true/false,\n")
        sb.append("  \"fix_suggestion\": \"如果可以自修复，给出建议\"\n")
        sb.append("}")
        
        return sb.toString()
    }
    
    /**
     * 解析LLM的风险评估结果
     */
    private RiskAssessmentResult parseLLMRiskAssessment(String response) {
        try {
            // 提取JSON部分
            int jsonStart = response.indexOf("{")
            int jsonEnd = response.lastIndexOf("}")
            if (jsonStart >= 0 && jsonEnd > jsonStart) {
                String jsonStr = response.substring(jsonStart, jsonEnd + 1)
                ObjectMapper mapper = new ObjectMapper()
                Map<String, Object> json = mapper.readValue(jsonStr, Map.class)
                
                String riskLevel = json.getOrDefault("risk_level", "LOW") as String
                String reason = json.getOrDefault("reason", "") as String
                Boolean canSelfFix = json.getOrDefault("can_self_fix", false) as Boolean
                String fixSuggestion = json.getOrDefault("fix_suggestion", "") as String
                
                RiskAssessmentResult result = new RiskAssessmentResult(riskLevel, reason)
                result.setCanSelfFix(canSelfFix)
                result.setFixSuggestion(fixSuggestion)
                return result
            }
        } catch (Exception e) {
            log.warn("解析LLM风险评估结果失败: {}", e.message)
        }
        
        // 解析失败，默认低风险
        return new RiskAssessmentResult("LOW", "解析失败，默认继续执行")
    }
    
    /**
     * 构建优化建议 Prompt（中风险）
     */
    private String buildOptimizationSuggestionPrompt(String sql, String question, SQLRiskAnalyzer.RiskAnalysisResult explainResult) {
        StringBuilder sb = new StringBuilder()
        sb.append("你是一个数据库优化专家。以下SQL存在中等风险，请提供优化建议。\n\n")
        sb.append("用户问题：").append(question).append("\n\n")
        sb.append("原始 SQL：\n").append(sql).append("\n\n")
        sb.append("EXPLAIN 分析结果：\n")
        sb.append("- 风险等级：").append(explainResult.getRiskLevel()).append("\n")
        if (explainResult.getRisks() != null && !explainResult.getRisks().isEmpty()) {
            sb.append("- 发现的风险点：\n")
            for (String risk : explainResult.getRisks()) {
                sb.append("  - ").append(risk).append("\n")
            }
        }
        sb.append("\n请提供优化建议（不要重新生成 SQL，只给建议）：\n")
        sb.append("1. 指出主要性能瓶颈\n")
        sb.append("2. 给出具体的优化建议（如添加索引、改写 WHERE 条件等）\n")
        sb.append("3. 说明预期优化效果\n\n")
        sb.append("返回JSON格式：\n")
        sb.append("{\n")
        sb.append("  \"bottleneck\": \"主要性能瓶颈\",\n")
        sb.append("  \"suggestion\": \"具体优化建议\",\n")
        sb.append("  \"expected_improvement\": \"预期优化效果\"\n")
        sb.append("}")
        
        return sb.toString()
    }
    
    /**
     * 解析 LLM 优化建议
     */
    private OptimizationSuggestion parseOptimizationSuggestion(String response) {
        try {
            int jsonStart = response.indexOf("{")
            int jsonEnd = response.lastIndexOf("}")
            if (jsonStart >= 0 && jsonEnd > jsonStart) {
                String jsonStr = response.substring(jsonStart, jsonEnd + 1)
                ObjectMapper mapper = new ObjectMapper()
                Map<String, Object> json = mapper.readValue(jsonStr, Map.class)
                
                OptimizationSuggestion suggestion = new OptimizationSuggestion()
                suggestion.setBottleneck(json.getOrDefault("bottleneck", "") as String)
                suggestion.setSuggestion(json.getOrDefault("suggestion", "") as String)
                suggestion.setExpectedImprovement(json.getOrDefault("expected_improvement", "") as String)
                return suggestion
            }
        } catch (Exception e) {
            log.warn("解析LLM优化建议失败: {}", e.message)
        }
        
        return null
    }
    
    /**
     * 构建重新生成 SQL 的 Prompt（高风险）
     */
    private String buildRegenerateSQLPrompt(String originalSql, String question, SQLRiskAnalyzer.RiskAnalysisResult explainResult) {
        StringBuilder sb = new StringBuilder()
        sb.append("你是一个数据库专家。之前生成的SQL存在高风险，请重新生成一个更优化的SQL。\n\n")
        sb.append("用户问题：").append(question).append("\n\n")
        sb.append("❌ 原始 SQL（有高风险）：\n").append(originalSql).append("\n\n")
        sb.append("⚠️ EXPLAIN 分析发现的风险：\n")
        if (explainResult.getRisks() != null && !explainResult.getRisks().isEmpty()) {
            for (String risk : explainResult.getRisks()) {
                sb.append("  - ").append(risk).append("\n")
            }
        }
        if (explainResult.getSuggestions() != null && !explainResult.getSuggestions().isEmpty()) {
            sb.append("\n💡 优化建议：\n")
            for (String suggestion : explainResult.getSuggestions()) {
                sb.append("  - ").append(suggestion).append("\n")
            }
        }
        sb.append("\n🎯 任务：重新生成一个SQL，要求：\n")
        sb.append("1. 避免上述风险（如全表扫描、缺少索引等）\n")
        sb.append("2. 保持查询语义不变\n")
        sb.append("3. 如果无法优化，请说明原因并返回原 SQL\n")
        sb.append("4. **只输出 SQL 语句，不要包含其他内容**\n\n")
        sb.append("新 SQL：")
        
        return sb.toString()
    }
    
    /**
     * 从 LLM 响应中提取 SQL
     */
    private String extractSQLFromResponse(String response) {
        if (response == null || response.isEmpty()) {
            return null
        }
        
        // 去除 Markdown 代码块标记
        String cleaned = response.trim()
        cleaned = cleaned.replaceAll('```sql\\s*', '')
        cleaned = cleaned.replaceAll('```\\s*$', '')
        cleaned = cleaned.trim()
        
        // 如果包含多行，取第一行完整的 SQL
        if (cleaned.contains("\n")) {
            String[] lines = cleaned.split("\n")
            for (String line : lines) {
                String trimmed = line.trim()
                if (trimmed.toUpperCase().startsWith("SELECT") || 
                    trimmed.toUpperCase().startsWith("WITH") ||
                    trimmed.toUpperCase().startsWith("INSERT") ||
                    trimmed.toUpperCase().startsWith("UPDATE") ||
                    trimmed.toUpperCase().startsWith("DELETE")) {
                    return trimmed
                }
            }
        }
        
        return cleaned
    }
    
    /**
     * 执行SQL并支持自动修正
     */
    private def executeWithAutoFix(String sql, Long datasourceId, Long userId, String username, 
                                    int maxRetries, NL2SQLService nl2sqlService, SQLExecutionTool sqlExecutionTool,
                                    SkillContext context, String sessionId) {
        // ✅ 统一使用 SQLCorrectionService 进行纠错
        com.nl2sql.core.service.SQLCorrectionService correctionService = null
        try {
            correctionService = context.getBean(com.nl2sql.core.service.SQLCorrectionService.class)
        } catch (Exception e) {
            log.warn("⚠️ SQLCorrectionService 未配置，降级为原有纠错逻辑")
        }
        
        String currentSql = sql
        
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                if (attempt > 0) {
                    publishEvent(context, sessionId, "retrying_sql", "🔄 第${attempt}次重试...")
                }
                
                def result = sqlExecutionTool.executeSQL(currentSql, datasourceId, userId, username)
                
                if (result.success) {
                    return result
                }
                
                // 如果还有重试次数，尝试自动修正
                if (attempt < maxRetries) {
                    log.info("执行失败，尝试自动修正 (第{}次): {}", attempt + 1, result.error)
                    publishEvent(context, sessionId, "correcting_sql", "🔧 自动修正SQL...")
                    
                    if (correctionService != null) {
                        // ✅ 使用统一的 SQLCorrectionService（兼容旧版API）
                        com.nl2sql.core.service.SQLCorrectionService.CorrectionResult correctionResult = 
                            correctionService.autoCorrect(currentSql, result.error, 1)
                        
                        if (correctionResult.success) {
                            currentSql = correctionResult.correctedSQL
                            log.info("✅ SQLCorrectionService 修正成功: {}", currentSql)
                        } else {
                            // 降级：使用原有逻辑
                            log.warn("⚠️ SQLCorrectionService 修正失败，降级为 LLM 修正")
                            currentSql = nl2sqlService.autoFixSQL(currentSql, result.error)
                        }
                    } else {
                        // 降级：使用原有逻辑
                        currentSql = nl2sqlService.autoFixSQL(currentSql, result.error)
                    }
                    
                    log.info("修正后的SQL: {}", currentSql)
                }
                
            } catch (Exception e) {
                if (attempt < maxRetries) {
                    log.error("执行异常，尝试自动修正 (第{}次): {}", attempt + 1, e.message)
                    publishEvent(context, sessionId, "correcting_error", "🔧 修正执行错误...")
                    
                    // ✅ 关键修复：检测是否为离线连接异常
                    boolean isOfflineError = e.message != null && (
                        e.message.contains("Communications link failure") ||
                        e.message.contains("Connection refused") ||
                        e.message.contains("Connect timed out") ||
                        e.message.contains("Unknown host") ||
                        e.message.contains("Cannot create PoolableConnectionFactory")
                    )
                    
                    if (isOfflineError) {
                        // ✅ 离线场景：不重试，直接返回SQL
                        log.warn("[离线模式] 无法连接远程数据库，返回生成的SQL")
                        publishEvent(context, sessionId, "offline_mode", "⚠️ 离线模式：无法连接数据库，已生成SQL供手动执行")
                        
                        return [
                            success: true,
                            data: [],
                            rowCount: 0,
                            executionTime: 0.0,
                            sql: currentSql,
                            datasourceId: datasourceId,
                            offlineMode: true,
                            message: "离线模式：无法连接远程数据库，请手动执行以下SQL"
                        ]
                    }
                    
                    if (correctionService != null) {
                        // ✅ 使用统一的 SQLCorrectionService（兼容旧版API）
                        com.nl2sql.core.service.SQLCorrectionService.CorrectionResult correctionResult = 
                            correctionService.autoCorrect(currentSql, e.message, 1)
                        
                        if (correctionResult.success) {
                            currentSql = correctionResult.correctedSQL
                            log.info("✅ SQLCorrectionService 修正成功: {}", currentSql)
                        } else {
                            // 降级：使用原有逻辑
                            log.warn("⚠️ SQLCorrectionService 修正失败，降级为 LLM 修正")
                            currentSql = nl2sqlService.autoFixSQL(currentSql, e.message)
                        }
                    } else {
                        // 降级：使用原有逻辑
                        currentSql = nl2sqlService.autoFixSQL(currentSql, e.message)
                    }
                } else {
                    throw e
                }
            }
        }
        
        throw new RuntimeException("SQL执行失败，已尝试${maxRetries}次修正")
    }
    
    // ==================== 结果工厂方法 ====================
    
    /**
     * 创建包含图表的成功结果
     */
    private Map<String, Object> createSuccessResultWithChart(List<Map<String, Object>> data, int rowCount, 
                                                              double executionTime, String sql, Long datasourceId,
                                                              String chartType, Map<String, Object> echartsConfig,
                                                              String optimizationSuggestion) {
        // ✅ 根据数据特征智能生成追问建议
        List<Map<String, String>> followUpSuggestions = generateFollowUpSuggestions(data, rowCount, sql)
        
        def result = [
            success: true,
            type: "data",  // ✅ 统一响应格式
            data: data,
            rowCount: rowCount,
            executionTime: executionTime,
            sql: sql,
            datasourceId: datasourceId,
            chartType: getChartTypeName(chartType),
            echartsConfig: echartsConfig
        ]
        
        // 只有当有追问建议时才添加
        if (followUpSuggestions && !followUpSuggestions.isEmpty()) {
            result.followUpSuggestions = followUpSuggestions
        }
        
        // ✅ 如果有优化建议，添加到返回结果中
        if (optimizationSuggestion != null && !optimizationSuggestion.trim().isEmpty()) {
            result.optimizationSuggestion = optimizationSuggestion
        }
        
        return result
    }
    
    private Map<String, Object> createSuccessResult(List<Map<String, Object>> data, int rowCount, 
                                                     double executionTime, String sql, Long datasourceId,
                                                     String optimizationSuggestion) {
        // ✅ 根据数据特征智能生成追问建议
        List<Map<String, String>> followUpSuggestions = generateFollowUpSuggestions(data, rowCount, sql)
        
        def result = [
            success: true,
            type: "data",  // ✅ 统一响应格式
            data: data,
            rowCount: rowCount,
            executionTime: executionTime,
            sql: sql,
            datasourceId: datasourceId  // ⚠️ 重要：返回 datasourceId 供前端后续使用
        ]
        
        // 只有当有追问建议时才添加
        if (followUpSuggestions && !followUpSuggestions.isEmpty()) {
            result.followUpSuggestions = followUpSuggestions
        }
        
        // ✅ 如果有优化建议，添加到返回结果中
        if (optimizationSuggestion != null && !optimizationSuggestion.trim().isEmpty()) {
            result.optimizationSuggestion = optimizationSuggestion
        }
        
        return result
    }
    
    /**
     * 根据数据特征智能生成追问建议
     */
    private List<Map<String, String>> generateFollowUpSuggestions(List<Map<String, Object>> data, int rowCount, String sql) {
        List<Map<String, String>> suggestions = []
        
        // ✅ 规则1：只有统计数据才提供 AI 总结（明细数据不提供）
        if (data != null && !data.isEmpty() && isStatisticalData(sql)) {
            suggestions.add([text: "🤖 AI 总结", action: "generate_summary"])
        }
        
        // ✅ 规则2：只有统计数据且有数值字段，才提供图表生成（明细数据禁止推荐）
        if (data != null && rowCount >= 2 && hasNumericColumn(data) && isStatisticalData(sql)) {
            suggestions.add([text: "📊 生成图表", action: "generate_chart"])
        }
        
        // ✅ 规则3：只要有数据就提供下载（包括明细和统计）
        if (data != null && !data.isEmpty()) {
            suggestions.add([text: "💾 下载 Excel", action: "export_excel"])
        }
        
        return suggestions
    }
    
    /**
     * 判断是否为统计数据（而非明细数据）
     * 通过SQL关键字判断：包含 GROUP BY、聚合函数等为统计
     */
    private boolean isStatisticalData(String sql) {
        if (sql == null || sql.isEmpty()) {
            return false
        }
        
        String upperSql = sql.toUpperCase()
        
        // 检查是否包含聚合函数
        boolean hasAggregation = upperSql.contains("GROUP BY") ||
                                 upperSql.contains("COUNT(") ||
                                 upperSql.contains("SUM(") ||
                                 upperSql.contains("AVG(") ||
                                 upperSql.contains("MAX(") ||
                                 upperSql.contains("MIN(")
        
        return hasAggregation
    }
    
    /**
     * 检查数据是否包含数值字段
     */
    private boolean hasNumericColumn(List<Map<String, Object>> data) {
        if (data == null || data.isEmpty()) {
            return false
        }
        
        // 检查第一行数据的值是否有数值类型
        def firstRow = data.get(0)
        for (def value : firstRow.values()) {
            if (value instanceof Number) {
                return true
            }
            // 尝试解析字符串是否为数字
            if (value instanceof String) {
                try {
                    Double.parseDouble(value)
                    return true
                } catch (NumberFormatException e) {
                    // 不是数字，继续检查
                }
            }
        }
        
        return false
    }
    
    /**
     * 从用户问题中提取图表类型
     * 支持：柱状图、折线图、饼图、面积图
     */
    private String extractChartTypeFromQuestion(String question) {
        if (question == null || question.isEmpty()) {
            return null
        }
        
        String lowerQuestion = question.toLowerCase()
        
        // 检测图表关键词
        if (lowerQuestion.contains("柱状图") || lowerQuestion.contains("bar chart") || lowerQuestion.contains("bar")) {
            return "bar"
        }
        if (lowerQuestion.contains("折线图") || lowerQuestion.contains("line chart") || lowerQuestion.contains("line")) {
            return "line"
        }
        if (lowerQuestion.contains("饼图") || lowerQuestion.contains("pie chart") || lowerQuestion.contains("pie")) {
            return "pie"
        }
        if (lowerQuestion.contains("面积图") || lowerQuestion.contains("area chart") || lowerQuestion.contains("area")) {
            return "area"
        }
        
        return null
    }
    
    /**
     * 从问题中移除图表相关描述
     */
    private String removeChartDescription(String question) {
        if (question == null || question.isEmpty()) {
            return question
        }
        
        // 移除常见的图表描述模式
        String cleaned = question
            .replaceAll("并生成[柱状|折线|饼|面积]图", "")
            .replaceAll("并画出[柱状|折线|饼|面积]图", "")
            .replaceAll("并展示[柱状|折线|饼|面积]图", "")
            .replaceAll("生成[柱状|折线|饼|面积]图", "")
            .replaceAll("画出[柱状|折线|饼|面积]图", "")
            .replaceAll("展示[柱状|折线|饼|面积]图", "")
            .trim()
        
        return cleaned
    }
    
    /**
     * 获取图表类型中文名
     */
    private String getChartTypeName(String chartType) {
        switch (chartType) {
            case "bar": return "柱状图"
            case "line": return "折线图"
            case "pie": return "饼图"
            case "area": return "面积图"
            default: return chartType
        }
    }
    
    /**
     * 生成 ECharts 配置（✅ 支持多指标）
     */
    private Map<String, Object> generateEChartsConfig(String chartType, List<Map<String, Object>> data) {
        if (data == null || data.isEmpty()) {
            return [:]
        }
        
        Map<String, Object> config = [:]
        config.put("type", chartType)
        
        // ✅ 提取分类列（第一列）
        List<String> categories = []
        
        // ✅ 提取所有数值列（除第一列外的所有数字列）
        Map<String, List<Object>> seriesMap = new LinkedHashMap<>()
        
        if (!data.isEmpty()) {
            def keys = data.get(0).keySet().toList()
            String categoryKey = keys.get(0)  // 第一列作为分类
            
            // 提取分类
            for (def row : data) {
                categories.add(String.valueOf(row.get(categoryKey)))
            }
            
            // ✅ 提取其他列作为系列
            for (int i = 1; i < keys.size(); i++) {
                String valueKey = keys.get(i)
                List<Object> values = []
                
                for (def row : data) {
                    Object val = row.get(valueKey)
                    // 只添加数字类型的值
                    if (val instanceof Number) {
                        values.add(val)
                    } else if (val != null) {
                        try {
                            values.add(Double.parseDouble(String.valueOf(val)))
                        } catch (Exception e) {
                            values.add(0)  // 非数字默认为0
                        }
                    } else {
                        values.add(0)
                    }
                }
                
                seriesMap.put(valueKey, values)
            }
        }
        
        config.put("categories", categories)
        config.put("series", seriesMap)  // ✅ 改为series映射，支持多指标
        config.put("title", getChartTypeName(chartType))
        
        return config
    }
    
    private Map<String, Object> createClarificationResult(String message) {
        return [
            success: true,
            type: "clarification",  // ✅ 统一响应格式
            needsClarification: true,
            clarificationMessage: message
        ]
    }
    
    private Map<String, Object> createExecutionFailedResult(String error) {
        return [
            success: false,
            type: "error",  // ✅ 统一响应格式
            error: error
        ]
    }
    
    private Map<String, Object> createErrorResult(String error) {
        return [
            success: false,
            type: "error",  // ✅ 统一响应格式
            error: error
        ]
    }
    
    private Map<String, Object> createRiskBlockedResult(String reason, String sql, String optimizationSuggestion) {
        def result = [
            success: false,
            type: "error",  // ✅ 统一响应格式
            error: "⚠️ SQL风险评估为高风险，已阻断执行\n原因: ${reason}",
            sql: sql
        ]
        
        // ✅ 如果有优化建议，添加到返回结果中
        if (optimizationSuggestion != null && !optimizationSuggestion.trim().isEmpty()) {
            result.optimizationSuggestion = optimizationSuggestion
        }
        
        return result
    }
    
    /**
     * ✅ 人机协同：高风险SQL需要用户确认
     * 
     * @param reason 风险原因
     * @param sql 待执行的SQL（可能是LLM优化后的）
     * @param optimizationSuggestion 优化建议
     * @param sessionId 会话ID（用于后续确认请求关联）
     * @return 等待用户确认的响应
     */
    private Map<String, Object> createHumanApprovalRequiredResult(String reason, String sql, String optimizationSuggestion, String sessionId) {
        def result = [
            success: false,
            type: "human_approval_required",  // ✅ 新类型：需要人工确认
            approvalId: sessionId + "_" + System.currentTimeMillis(),  // ✅ 生成唯一确认ID
            error: "⚠️ SQL风险评估为高风险，需要人工确认",
            riskReason: reason,
            sql: sql,
            message: "该SQL存在高风险，请审核后再决定是否执行"
        ]
        
        // ✅ 如果有优化建议，添加到返回结果中
        if (optimizationSuggestion != null && !optimizationSuggestion.trim().isEmpty()) {
            result.optimizationSuggestion = optimizationSuggestion
        }
        
        log.info("✅ 人机协同：生成确认请求 approvalId={}", result.approvalId)
        
        return result
    }
    
    /**
     * 发布流式进度事件
     */
    private void publishEvent(SkillContext context, String sessionId, String step, String message) {
        if (sessionId == null || sessionId.isEmpty()) {
            return
        }
        
        try {
            def eventPublisher = context.getBean(ApplicationEventPublisher.class)
            if (eventPublisher != null) {
                def event = new StreamProgressEvent(this, sessionId, step, message, null)
                eventPublisher.publishEvent(event)
                println "[StreamProgress] 发布事件: step=${step}, message=${message}"
            }
        } catch (Exception e) {
            println "[StreamProgress] 发布事件失败: ${e.message}"
        }
    }
    
    // ==================== 内部类 ====================
    
    static class RiskAssessmentResult {
        String riskLevel
        String reason
        Boolean canSelfFix
        String fixSuggestion
        String originalSql          // 原始 SQL
        String optimizedSql         // 优化后的 SQL（高风险时）
        Boolean optimizationApplied // 是否应用了优化
        OptimizationSuggestion llmSuggestion // LLM 优化建议（中风险时）
        
        RiskAssessmentResult() {}
        
        RiskAssessmentResult(String riskLevel, String reason) {
            this.riskLevel = riskLevel
            this.reason = reason
            this.canSelfFix = false
            this.fixSuggestion = ""
            this.optimizationApplied = false
        }
        
        String getRiskLevel() { return riskLevel }
        String getReason() { return reason }
        Boolean getCanSelfFix() { return canSelfFix }
        void setCanSelfFix(Boolean value) { this.canSelfFix = value }
        String getFixSuggestion() { return fixSuggestion }
        void setFixSuggestion(String value) { this.fixSuggestion = value }
        String getOriginalSql() { return originalSql }
        void setOriginalSql(String value) { this.originalSql = value }
        String getOptimizedSql() { return optimizedSql }
        void setOptimizedSql(String value) { this.optimizedSql = value }
        Boolean getOptimizationApplied() { return optimizationApplied }
        void setOptimizationApplied(Boolean value) { this.optimizationApplied = value }
        OptimizationSuggestion getLlmSuggestion() { return llmSuggestion }
        void setLlmSuggestion(OptimizationSuggestion value) { this.llmSuggestion = value }
    }
    
    static class OptimizationSuggestion {
        String bottleneck           // 性能瓶颈
        String suggestion           // 优化建议
        String expectedImprovement  // 预期优化效果
        
        OptimizationSuggestion() {}
        
        String getBottleneck() { return bottleneck }
        void setBottleneck(String value) { this.bottleneck = value }
        String getSuggestion() { return suggestion }
        void setSuggestion(String value) { this.suggestion = value }
        String getExpectedImprovement() { return expectedImprovement }
        void setExpectedImprovement(String value) { this.expectedImprovement = value }
    }
    
    /**
     * ✅ 新增：从 schema 文本中提取表名列表
     * @param schema schema文本（格式："\n表名: orders\n  - id (INT) [主键]\n  ..."）
     * @return 表名列表
     */
    private List<String> extractTableNamesFromSchema(String schema) {
        if (schema == null || schema.isEmpty()) {
            return []
        }
        
        List<String> tables = []
        def matcher = schema =~ /\n表名:\s*(\w+)/
        while (matcher.find()) {
            String tableName = matcher.group(1).toLowerCase()
            tables.add(tableName)
        }
        
        return tables
    }
}

/*
 * ==================== 新架构示例（待迁移）====================
 * 
 * 以下是如何使用 callTool() 和 callSkill() 的示例：
 * 
 * // ✅ 示例1：调用 execute_sql Tool
 * def toolParams = [
 *     sql: "SELECT * FROM orders LIMIT 10",
 *     datasourceId: context.getParameter("datasourceId")
 * ]
 * String resultJson = context.callTool("execute_sql", toolParams)
 * 
 * // ✅ 示例2：调用 get_table_metadata Tool
 * def metadataParams = [
 *     tableName: "orders",
 *     datasourceId: context.getParameter("datasourceId")
 * ]
 * String metadataJson = context.callTool("get_table_metadata", metadataParams)
 * 
 * // ✅ 示例3：调用其他 Skill
 * def skillParams = [
 *     sql: "SELECT * FROM orders",
 *     datasourceId: context.getParameter("datasourceId")
 * ]
 * def skillResult = context.callSkill("simple_data_query", skillParams)
 * 
 * 迁移计划：
 * 1. 将 retrieveSchema() 改为调用 get_table_metadata Tool
 * 2. 将 generateSQL() 保留（需要 LLM，不适合 Tool）
 * 3. 将 executeWithAutoFix() 中的 SQL 执行改为调用 execute_sql Tool
 * 4. 逐步移除直接 Bean 依赖
 */
