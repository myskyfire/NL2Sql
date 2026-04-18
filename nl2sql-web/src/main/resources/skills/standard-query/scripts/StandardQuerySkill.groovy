import com.nl2sql.core.agent.skills.SkillContext
import com.nl2sql.core.agent.tools.NL2SQLTool
import com.nl2sql.core.agent.tools.SQLExecutionTool
import com.nl2sql.core.executor.SQLRiskAnalyzer
import com.nl2sql.core.llm.LLMService
import com.nl2sql.common.event.StreamProgressEvent
import dev.langchain4j.model.chat.ChatModel
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher

/**
 * 标准查询技能
 * 
 * 封装完整的查询生命周期：
 * 1. 检索表结构
 * 2. 生成SQL
 * 3. SQL优化与风险评估
 * 4. 执行SQL（带自动修正）
 */
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
        
        println "[StandardQuerySkill] 开始执行标准查询: question=${question}, datasourceId=${datasourceId}"
        
        // ✅ 关键检查：如果缺少 datasourceId，返回澄清请求
        if (datasourceId == null) {
            println "[StandardQuerySkill] 缺少 datasourceId，返回澄清请求"
            publishEvent(context, sessionId, "clarification_needed", "⚠️ 请先选择数据源")
            return createClarificationResult("请先选择数据源")
        }
        
        try {
            // 获取 Spring Bean
            NL2SQLTool nl2sqlTool = context.getBean(NL2SQLTool.class)
            SQLExecutionTool sqlExecutionTool = context.getBean(SQLExecutionTool.class)
            LLMService llmService = context.getBean(LLMService.class)
            SQLRiskAnalyzer riskAnalyzer = null
            try {
                riskAnalyzer = context.getBean(SQLRiskAnalyzer.class)
            } catch (Exception e) {
                println "[StandardQuerySkill] SQLRiskAnalyzer 未配置，跳过风险评估"
            }
            
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
            String schema = nl2sqlTool.retrieveSchema(question, datasourceId)
            publishEvent(context, sessionId, "schema_retrieved", "✅ 表结构检索完成")
                        
            // Step 2: 生成SQL
            println "[StandardQuerySkill] Step 2: 生成SQL"
            publishEvent(context, sessionId, "generating_sql", "🤖 AI生成SQL...")
            
            // ✅ 关键修复：从用户问题中提取表名偏好
            String tableHint = extractTablePreference(question)
            if (tableHint) {
                println "[StandardQuerySkill] 检测到用户指定表: ${tableHint}"
                question = "${question} [优先使用表: ${tableHint}]"
            }
            
            String sql = nl2sqlTool.generateSQL(question, datasourceId)
            
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
                RiskAssessmentResult riskResult = assessSQLRisk(sql, question, datasourceId, llmService, riskAnalyzer)
                
                if ("HIGH".equals(riskResult.getRiskLevel())) {
                    log.warn("SQL风险评估为高风险，阻断执行: {}", riskResult.getReason())
                    publishEvent(context, sessionId, "risk_blocked", "⚠️ 高风险SQL已阻断: " + riskResult.getReason())
                    return createRiskBlockedResult(riskResult.getReason(), sql)
                } else if ("MEDIUM".equals(riskResult.getRiskLevel())) {
                    log.info("SQL风险评估为中风险，继续执行但提示用户: {}", riskResult.getReason())
                    publishEvent(context, sessionId, "risk_medium", "⚠️ 中风险SQL，继续执行")
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
            log.info("Step 3: 执行SQL")
            publishEvent(context, sessionId, "executing_sql", "⚙️ 执行SQL查询...")
            
            def execResult = executeWithAutoFix(sql, datasourceId, userId, username, 2, nl2sqlTool, sqlExecutionTool, context, sessionId)
            
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
                return createSuccessResultWithChart(
                    execResult.data, 
                    execResult.rowCount, 
                    execResult.executionTime, 
                    sql, 
                    datasourceId,
                    chartType,
                    echartsConfig
                )
            }
            
            return createSuccessResult(execResult.data, execResult.rowCount, execResult.executionTime, sql, datasourceId)
            
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
     * 评估SQL风险（LLM自主判断 + EXPLAIN辅助）
     */
    private RiskAssessmentResult assessSQLRisk(String sql, String question, Long datasourceId, 
                                                LLMService llmService, SQLRiskAnalyzer riskAnalyzer) {
        try {
            // ✅ 关键修复：如果 SQL 是澄清消息或错误消息，直接返回低风险
            if (sql.startsWith("CLARIFICATION") || sql.startsWith("CLARIFY_") || 
                sql.startsWith("错误：") || sql.startsWith("ERROR:")) {
                log.info("SQL不是有效查询，跳过风险评估")
                return new RiskAssessmentResult("LOW", "非有效SQL，无需风险评估")
            }
            
            // Step 1: LLM先自行评估
            log.info("Step 1: LLM初步风险评估")
            String llmPrompt = buildRiskAssessmentPrompt(sql, question)
            String llmResponse = llmService.generateAnswer(llmPrompt)
            
            log.debug("LLM初步评估响应: {}", llmResponse)
            
            // 解析LLM的评估结果
            RiskAssessmentResult result = parseLLMRiskAssessment(llmResponse)
            
            log.info("LLM初步评估结果: riskLevel={}, reason={}", result.getRiskLevel(), result.getReason())
            
            // Step 2: 如果LLM无法确定或认为需要EXPLAIN，则调用风险分析工具
            if ("UNCERTAIN".equals(result.getRiskLevel())) {
                if (riskAnalyzer == null) {
                    log.warn("⚠️ SQLRiskAnalyzer 未注入，跳过EXPLAIN分析")
                    result.setRiskLevel("LOW")
                    result.setReason("SQLRiskAnalyzer未配置，默认低风险")
                    return result
                }
                
                // ⚠️ 关键检查：如果SQL是澄清消息，不要调用EXPLAIN
                if (sql.startsWith("CLARIFICATION") || sql.startsWith("CLARIFY_")) {
                    log.info("SQL是澄清消息，跳过EXPLAIN分析")
                    result.setRiskLevel("LOW")
                    return result
                }
                
                log.info("✅ LLM返回UNCERTAIN，开始调用EXPLAIN分析...")
                SQLRiskAnalyzer.RiskAnalysisResult explainResult = riskAnalyzer.analyzeRisk(sql, datasourceId)
                
                log.info("EXPLAIN分析完成: riskLevel={}, risks={}", explainResult.getRiskLevel(), explainResult.getRisks()?.size() ?: 0)
                
                // 将EXPLAIN结果再次给LLM判断
                log.info("Step 2: LLM结合EXPLAIN结果进行最终评估")
                String refinedPrompt = buildRefinedRiskPrompt(sql, question, explainResult)
                String refinedResponse = llmService.generateAnswer(refinedPrompt)
                
                log.debug("LLM最终评估响应: {}", refinedResponse)
                result = parseLLMRiskAssessment(refinedResponse)
                
                log.info("✅ 最终风险评估结果: riskLevel={}", result.getRiskLevel())
            } else {
                log.info("LLM已给出确定性评估({})，跳过EXPLAIN分析", result.getRiskLevel())
            }
            
            return result
            
        } catch (Exception e) {
            log.error("❌ 风险评估失败，默认低风险: {}", e.message, e)
            return new RiskAssessmentResult("LOW", "风险评估失败，默认继续执行")
        }
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
     * 执行SQL并支持自动修正
     */
    private def executeWithAutoFix(String sql, Long datasourceId, Long userId, String username, 
                                    int maxRetries, NL2SQLTool nl2sqlTool, SQLExecutionTool sqlExecutionTool,
                                    SkillContext context, String sessionId) {
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
                    log.info("执行失败，尝试自动修正 (第{}次)", attempt + 1)
                    publishEvent(context, sessionId, "correcting_sql", "🔧 自动修正SQL...")
                    currentSql = nl2sqlTool.autoFixSQL(currentSql, result.error)
                    log.info("修正后的SQL: {}", currentSql)
                }
                
            } catch (Exception e) {
                if (attempt < maxRetries) {
                    log.error("执行异常，尝试自动修正 (第{}次): {}", attempt + 1, e.message)
                    publishEvent(context, sessionId, "correcting_error", "🔧 修正执行错误...")
                    currentSql = nl2sqlTool.autoFixSQL(currentSql, e.message)
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
                                                              String chartType, Map<String, Object> echartsConfig) {
        // ✅ 根据数据特征智能生成追问建议
        List<Map<String, String>> followUpSuggestions = generateFollowUpSuggestions(data, rowCount, sql)
        
        def result = [
            success: true,
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
        
        return result
    }
    
    private Map<String, Object> createSuccessResult(List<Map<String, Object>> data, int rowCount, 
                                                     double executionTime, String sql, Long datasourceId) {
        // ✅ 根据数据特征智能生成追问建议
        List<Map<String, String>> followUpSuggestions = generateFollowUpSuggestions(data, rowCount, sql)
        
        def result = [
            success: true,
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
     * 生成 ECharts 配置
     */
    private Map<String, Object> generateEChartsConfig(String chartType, List<Map<String, Object>> data) {
        if (data == null || data.isEmpty()) {
            return [:]
        }
        
        Map<String, Object> config = [:]
        config.put("type", chartType)
        
        // 提取 categories 和 values
        List<String> categories = []
        List<Object> values = []
        
        // 假设第一列是分类，第二列是数值
        String categoryKey = null
        String valueKey = null
        
        if (!data.isEmpty()) {
            def keys = data.get(0).keySet()
            def iterator = keys.iterator()
            if (iterator.hasNext()) categoryKey = iterator.next()
            if (iterator.hasNext()) valueKey = iterator.next()
        }
        
        if (categoryKey != null && valueKey != null) {
            for (def row : data) {
                categories.add(String.valueOf(row.get(categoryKey)))
                values.add(row.get(valueKey))
            }
        }
        
        config.put("categories", categories)
        config.put("values", values)
        config.put("title", getChartTypeName(chartType))
        
        return config
    }
    
    private Map<String, Object> createClarificationResult(String message) {
        return [
            success: false,
            needsClarification: true,
            clarificationMessage: message
        ]
    }
    
    private Map<String, Object> createExecutionFailedResult(String error) {
        return [
            success: false,
            error: error
        ]
    }
    
    private Map<String, Object> createErrorResult(String error) {
        return [
            success: false,
            error: error
        ]
    }
    
    private Map<String, Object> createRiskBlockedResult(String reason, String sql) {
        return [
            success: false,
            error: "⚠️ SQL风险评估为高风险，已阻断执行\n原因: ${reason}",
            sql: sql
        ]
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
        
        RiskAssessmentResult() {}
        
        RiskAssessmentResult(String riskLevel, String reason) {
            this.riskLevel = riskLevel
            this.reason = reason
            this.canSelfFix = false
            this.fixSuggestion = ""
        }
        
        String getRiskLevel() { return riskLevel }
        String getReason() { return reason }
        Boolean getCanSelfFix() { return canSelfFix }
        void setCanSelfFix(Boolean value) { this.canSelfFix = value }
        String getFixSuggestion() { return fixSuggestion }
        void setFixSuggestion(String value) { this.fixSuggestion = value }
    }
}
