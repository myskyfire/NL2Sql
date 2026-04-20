import com.nl2sql.core.agent.skills.SkillContext
import groovy.json.JsonSlurper

/**
 * SQL验证与执行 Skill - 演示完整的 Tool 调用链
 * 
 * ✅ 这是真正的 Skill：只负责流程编排，不直接操作数据库
 * 
 * 流程：
 * 1. 调用 validate_sql Tool 验证SQL语法和安全性
 * 2. 如果验证通过，调用 execute_sql Tool 执行查询
 * 3. 返回结果或错误信息
 */
def execute(SkillContext context) {
    println "[SQLValidateAndExecuteSkill] 开始执行"
    
    // 1. 获取参数
    String sql = context.getParameter("sql")
    Long datasourceId = context.getParameter("datasourceId")
    
    if (!sql || !datasourceId) {
        return [
            status: "error",
            message: "缺少必需参数: sql 和 datasourceId"
        ]
    }
    
    println "[SQLValidateAndExecuteSkill] SQL: ${sql}"
    println "[SQLValidateAndExecuteSkill] 数据源ID: ${datasourceId}"
    
    try {
        // 2. ✅ 第一步：调用 validate_sql Tool 验证SQL
        log.info("Step 1: 验证SQL语法和安全性")
        
        def validateParams = [
            sql: sql,
            datasourceId: datasourceId
        ]
        
        String validateResultJson = context.callTool("validate_sql", validateParams)
        
        // 3. 解析验证结果
        def slurper = new JsonSlurper()
        def validateResult = slurper.parseText(validateResultJson)
        
        if (!validateResult.valid) {
            log.warn("SQL验证失败: {}", validateResult.error)
            return [
                status: "validation_failed",
                message: "SQL验证失败",
                error: validateResult.error,
                riskLevel: validateResult.riskLevel,
                suggestions: validateResult.suggestions,
                datasourceId: datasourceId
            ]
        }
        
        log.info("✅ SQL验证通过，风险等级: {}", validateResult.riskLevel)
        
        // 4. ✅ 第二步：调用 execute_sql Tool 执行查询
        log.info("Step 2: 执行SQL查询")
        
        def executeParams = [
            sql: sql,
            datasourceId: datasourceId
        ]
        
        String executeResultJson = context.callTool("execute_sql", executeParams)
        def executeResult = slurper.parseText(executeResultJson)
        
        if (!executeResult.success) {
            log.error("SQL执行失败: {}", executeResult.error)
            return [
                status: "execution_failed",
                message: "SQL执行失败",
                error: executeResult.error,
                sql: sql,
                datasourceId: datasourceId
            ]
        }
        
        log.info("✅ 查询成功，返回 {} 行数据", executeResult.rowCount)
        
        // 5. 返回完整结果
        return [
            status: "success",
            validation: [
                valid: validateResult.valid,
                riskLevel: validateResult.riskLevel,
                warnings: validateResult.warnings
            ],
            execution: [
                rowCount: executeResult.rowCount,
                columns: executeResult.columns,
                data: executeResult.data,
                executionTime: executeResult.executionTime
            ],
            sql: sql,
            datasourceId: datasourceId
        ]
        
    } catch (Exception e) {
        log.error("[SQLValidateAndExecuteSkill] 执行失败: {}", e.message)
        e.printStackTrace()
        
        return [
            status: "error",
            message: "执行失败: ${e.message}",
            datasourceId: datasourceId
        ]
    }
}
