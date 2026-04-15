import com.nl2sql.core.agent.skills.SkillContext
import com.nl2sql.core.agent.tools.NL2SQLTool
import com.nl2sql.core.agent.tools.SQLExecutionTool
import com.nl2sql.core.executor.SQLRiskAnalyzer
import com.nl2sql.core.llm.LLMService
import dev.langchain4j.model.chat.ChatModel
import com.fasterxml.jackson.databind.ObjectMapper

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
        
        println "[StandardQuerySkill] 开始执行标准查询: question=${question}, datasourceId=${datasourceId}"
        
        // ✅ 关键检查：如果缺少 datasourceId，返回澄清请求
        if (datasourceId == null) {
            println "[StandardQuerySkill] 缺少 datasourceId，返回澄清请求"
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
            
            // Step 1: 检索表结构
            println "[StandardQuerySkill] Step 1: 检索表结构"
            String schema = nl2sqlTool.retrieveSchema(question, datasourceId)
            
            // Step 2: 生成SQL
            println "[StandardQuerySkill] Step 2: 生成SQL"
            String sql = nl2sqlTool.generateSQL(question, datasourceId)
            
            // SQL后处理：检测并修复IN子查询关联
            sql = optimizeSQL(sql)
            
            // Step 2.5: LLM自主评估SQL风险
            println "[StandardQuerySkill] Step 2.5: 评估SQL风险"
            RiskAssessmentResult riskResult = assessSQLRisk(sql, question, datasourceId, llmService, riskAnalyzer)
            
            if ("HIGH".equals(riskResult.getRiskLevel())) {
                println "[StandardQuerySkill] SQL风险评估为高风险，阻断执行: ${riskResult.getReason()}"
                return createRiskBlockedResult(riskResult.getReason(), sql)
            } else if ("MEDIUM".equals(riskResult.getRiskLevel())) {
                println "[StandardQuerySkill] SQL风险评估为中风险，继续执行但提示用户: ${riskResult.getReason()}"
            } else {
                println "[StandardQuerySkill] SQL风险评估为低风险，直接执行"
            }
            
            // 检查是否需要澄清
            if (sql.startsWith("CLARIFY_") || sql.startsWith("CLARIFICATION")) {
                println "[StandardQuerySkill] 需要澄清: ${sql}"
                return createClarificationResult(sql)
            }
            
            // Step 3: 执行SQL（带自动修正，最多2次）
            println "[StandardQuerySkill] Step 3: 执行SQL"
            
            def execResult = executeWithAutoFix(sql, datasourceId, userId, username, 2, nl2sqlTool, sqlExecutionTool)
            
            if (!execResult.success) {
                println "[StandardQuerySkill] 执行失败: ${execResult.error}"
                return createExecutionFailedResult(execResult.error)
            }
            
            println "[StandardQuerySkill] 查询成功: rowCount=${execResult.rowCount}"
            return createSuccessResult(execResult.data, execResult.rowCount, execResult.executionTime, sql)
            
        } catch (Exception e) {
            println "[StandardQuerySkill] 执行异常: ${e.message}"
            e.printStackTrace()
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
            println "[StandardQuerySkill] 检测到ON条件中使用IN子查询，建议改为直接JOIN"
        }
        
        return sql
    }
    
    /**
     * 评估SQL风险（LLM自主判断 + EXPLAIN辅助）
     */
    private RiskAssessmentResult assessSQLRisk(String sql, String question, Long datasourceId, 
                                                LLMService llmService, SQLRiskAnalyzer riskAnalyzer) {
        try {
            ChatModel model = llmService.getChatModel()
            
            // Step 1: LLM先自行评估
            String llmPrompt = buildRiskAssessmentPrompt(sql, question)
            String llmResponse = model.chat(llmPrompt)
            
            println "[StandardQuerySkill] LLM初步评估: ${llmResponse}"
            
            // 解析LLM的评估结果
            RiskAssessmentResult result = parseLLMRiskAssessment(llmResponse)
            
            // Step 2: 如果LLM无法确定或认为需要EXPLAIN，则调用风险分析工具
            if ("UNCERTAIN".equals(result.getRiskLevel()) && riskAnalyzer != null) {
                println "[StandardQuerySkill] LLM不确定，调用EXPLAIN分析"
                SQLRiskAnalyzer.RiskAnalysisResult explainResult = riskAnalyzer.analyzeRisk(sql)
                
                // 将EXPLAIN结果再次给LLM判断
                String refinedPrompt = buildRefinedRiskPrompt(sql, question, explainResult)
                String refinedResponse = model.chat(refinedPrompt)
                
                println "[StandardQuerySkill] LLM结合EXPLAIN后的评估: ${refinedResponse}"
                result = parseLLMRiskAssessment(refinedResponse)
            }
            
            return result
            
        } catch (Exception e) {
            println "[StandardQuerySkill] 风险评估失败，默认低风险: ${e.message}"
            return new RiskAssessmentResult("LOW", "风险评估失败，默认继续执行")
        }
    }
    
    /**
     * 构建风险评估Prompt
     */
    private String buildRiskAssessmentPrompt(String sql, String question) {
        return """你是一个数据库专家。请评估以下SQL语句的执行风险。

用户问题：${question}

生成的SQL：
${sql}

请从以下维度评估：
1. 是否有全表扫描风险？
2. JOIN的表数量是否过多（≥3张）？
3. 是否有子查询或复杂嵌套？
4. WHERE条件是否缺少索引支持？
5. 是否可能返回大量数据（无LIMIT）？

返回JSON格式：
{
  "risk_level": "LOW/MEDIUM/HIGH/UNCERTAIN",
  "reason": "简要说明原因",
  "can_self_fix": true/false,
  "fix_suggestion": "如果可以自修复，给出建议"
}

注意：
- HIGH: 可能导致性能问题或超时，建议阻断
- MEDIUM: 有一定风险但可以接受
- LOW: 无明显风险
- UNCERTAIN: 无法判断，需要EXPLAIN辅助"""
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
            println "[StandardQuerySkill] 解析LLM风险评估结果失败: ${e.message}"
        }
        
        // 解析失败，默认低风险
        return new RiskAssessmentResult("LOW", "解析失败，默认继续执行")
    }
    
    /**
     * 执行SQL并支持自动修正
     */
    private def executeWithAutoFix(String sql, Long datasourceId, Long userId, String username, 
                                    int maxRetries, NL2SQLTool nl2sqlTool, SQLExecutionTool sqlExecutionTool) {
        String currentSql = sql
        
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                def result = sqlExecutionTool.executeSQL(currentSql, datasourceId, userId, username)
                
                if (result.success) {
                    return result
                }
                
                // 如果还有重试次数，尝试自动修正
                if (attempt < maxRetries) {
                    println "[StandardQuerySkill] 执行失败，尝试自动修正 (第${attempt + 1}次)"
                    currentSql = nl2sqlTool.autoFixSQL(currentSql, result.error)
                    println "[StandardQuerySkill] 修正后的SQL: ${currentSql}"
                }
                
            } catch (Exception e) {
                if (attempt < maxRetries) {
                    println "[StandardQuerySkill] 执行异常，尝试自动修正 (第${attempt + 1}次): ${e.message}"
                    currentSql = nl2sqlTool.autoFixSQL(currentSql, e.message)
                } else {
                    throw e
                }
            }
        }
        
        throw new RuntimeException("SQL执行失败，已尝试${maxRetries}次修正")
    }
    
    // ==================== 结果工厂方法 ====================
    
    private Map<String, Object> createSuccessResult(List<Map<String, Object>> data, int rowCount, 
                                                     double executionTime, String sql) {
        // ✅ 根据数据特征智能生成追问建议
        List<Map<String, String>> followUpSuggestions = generateFollowUpSuggestions(data, rowCount, sql)
        
        // ⚠️ 重要：获取 datasourceId 并添加到返回结果
        Long datasourceId = context.getParameter("datasourceId")
        
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
