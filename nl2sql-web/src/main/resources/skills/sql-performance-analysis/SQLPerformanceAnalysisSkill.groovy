import com.nl2sql.core.agent.skills.SkillContext
import groovy.json.JsonSlurper
import groovy.json.JsonOutput

/**
 * SQL性能分析 Skill - 演示多Tool协同工作
 * 
 * ✅ 这是真正的 Skill：只负责流程编排，不直接操作数据库
 * ✅ P0优化：使用 SkillResult 统一响应格式
 * 
 * 流程：
 * 1. 调用 validate_sql Tool 验证SQL语法
 * 2. 调用 analyze_query_plan Tool 分析执行计划
 * 3. 调用 check_index Tool 检查索引使用情况
 * 4. 调用 estimate_cost Tool 估算查询成本
 * 5. 综合所有分析结果，给出优化建议
 */
def execute(SkillContext context) {
    println "[SQLPerformanceAnalysisSkill] 开始执行"
    
    // 1. 获取参数
    String sql = context.getParameter("sql")
    Long datasourceId = context.getParameter("datasourceId")
    
    if (!sql || !datasourceId) {
        return JsonOutput.toJson([
            success: false,
            type: "error",
            error: [
                errorCode: "MISSING_PARAMS",
                errorMessage: "缺少必需参数: sql 和 datasourceId"
            ]
        ])
    }
    
    println "[SQLPerformanceAnalysisSkill] SQL: ${sql}"
    println "[SQLPerformanceAnalysisSkill] 数据源ID: ${datasourceId}"
    
    try {
        def slurper = new JsonSlurper()
        def analysisResults = [:]
        
        // 2. ✅ 第一步：验证SQL
        log.info("Step 1: 验证SQL语法和安全性")
        def validateResult = callToolAndParse(context, "validate_sql", [
            sql: sql,
            datasourceId: datasourceId
        ])
        
        if (!validateResult.valid) {
            log.warn("SQL验证失败: {}", validateResult.error)
            return JsonOutput.toJson([
                success: false,
                type: "error",
                error: [
                    errorCode: "SQL_VALIDATION_FAILED",
                    errorMessage: "SQL验证失败",
                    context: [
                        riskLevel: validateResult.riskLevel,
                        error: validateResult.error,
                        datasourceId: datasourceId
                    ]
                ]
            ])
        }
        
        analysisResults.validation = validateResult
        log.info("✅ SQL验证通过")
        
        // 3. ✅ 第二步：分析执行计划
        log.info("Step 2: 分析执行计划（EXPLAIN）")
        def explainResult = callToolAndParse(context, "analyze_query_plan", [
            sql: sql,
            datasourceId: datasourceId
        ])
        
        analysisResults.explainPlan = explainResult
        log.info("✅ 执行计划分析完成，风险等级: {}", explainResult.riskLevel)
        
        // 4. ✅ 第三步：检查索引
        log.info("Step 3: 检查索引使用情况")
        def indexResult = callToolAndParse(context, "check_index", [
            sql: sql,
            datasourceId: datasourceId
        ])
        
        analysisResults.indexCheck = indexResult
        log.info("✅ 索引检查完成")
        
        // 5. ✅ 第四步：估算成本
        log.info("Step 4: 估算查询成本")
        def costResult = callToolAndParse(context, "estimate_cost", [
            sql: sql,
            datasourceId: datasourceId
        ])
        
        analysisResults.costEstimate = costResult
        log.info("✅ 成本估算完成")
        
        // 6. ✅ 综合分析，生成优化建议
        log.info("Step 5: 综合分析并生成优化建议")
        def optimizationSuggestions = generateOptimizationSuggestions(
            explainResult, 
            indexResult, 
            costResult
        )
        
        analysisResults.suggestions = optimizationSuggestions
        
        // 7. 返回完整分析报告（SkillResult 统一格式）
        return JsonOutput.toJson([
            success: true,
            type: "analysis_result",
            data: [
                sql: sql,
                analysis: analysisResults,
                summary: [
                    overallRisk: calculateOverallRisk(explainResult, indexResult, costResult),
                    suggestionCount: optimizationSuggestions.size(),
                    recommendations: optimizationSuggestions
                ]
            ],
            metadata: [
                datasourceId: datasourceId
            ]
        ])
        
    } catch (Exception e) {
        log.error("[SQLPerformanceAnalysisSkill] 执行失败: {}", e.message)
        e.printStackTrace()
        
        return JsonOutput.toJson([
            success: false,
            type: "error",
            error: [
                errorCode: "SKILL_EXECUTION_ERROR",
                errorMessage: "执行失败: ${e.message}",
                context: [datasourceId: datasourceId]
            ]
        ])
    }
}

/**
 * 调用Tool并解析JSON结果
 */
private def callToolAndParse(SkillContext context, String toolName, Map params) {
    def slurper = new JsonSlurper()
    String resultJson = context.callTool(toolName, params)
    return slurper.parseText(resultJson)
}

/**
 * 生成优化建议
 */
private List<String> generateOptimizationSuggestions(def explainResult, def indexResult, def costResult) {
    List<String> suggestions = []
    
    // 基于执行计划的建议
    if (explainResult.risks) {
        explainResult.risks.each { risk ->
            if (risk.contains("全表扫描")) {
                suggestions.add("⚠️ 检测到全表扫描，建议添加合适的索引")
            }
            if (risk.contains("临时表")) {
                suggestions.add("⚠️ 使用临时表，考虑优化查询结构或添加索引")
            }
            if (risk.contains("文件排序")) {
                suggestions.add("⚠️ 使用文件排序，建议在ORDER BY字段上添加索引")
            }
        }
    }
    
    // 基于索引检查的建议
    if (indexResult.missingIndexes) {
        indexResult.missingIndexes.each { idx ->
            suggestions.add("💡 建议添加索引: ${idx.table}.${idx.column}")
        }
    }
    
    // 基于成本估算的建议
    if (costResult.estimatedCost > 1000) {
        suggestions.add("⚠️ 查询成本较高 (${costResult.estimatedCost})，考虑优化查询逻辑")
    }
    
    if (costResult.estimatedRows > 100000) {
        suggestions.add("⚠️ 预计扫描行数过多 (${costResult.estimatedRows})，建议添加WHERE条件限制")
    }
    
    if (suggestions.isEmpty()) {
        suggestions.add("✅ 查询性能良好，无明显优化空间")
    }
    
    return suggestions
}

/**
 * 计算整体风险等级
 */
private String calculateOverallRisk(def explainResult, def indexResult, def costResult) {
    // 如果任何一个环节是高风险，整体就是高风险
    if ("HIGH".equals(explainResult.riskLevel)) {
        return "HIGH"
    }
    
    // 如果成本非常高，也是高风险
    if (costResult.estimatedCost > 5000) {
        return "HIGH"
    }
    
    // 如果有中风险或中等成本
    if ("MEDIUM".equals(explainResult.riskLevel) || costResult.estimatedCost > 1000) {
        return "MEDIUM"
    }
    
    return "LOW"
}
