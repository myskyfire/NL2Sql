import com.nl2sql.core.agent.skills.SkillContext
import groovy.json.JsonSlurper
import groovy.json.JsonOutput

/**
 * @Deprecated Groovy skill scripts are deprecated. Use YAML workflow instead.
 * 
 * 简单数据查询 Skill - 演示正确的 Tool 调用方式
 * 
 * ✅ 这是真正的 Skill：只负责流程编排，不直接操作数据库
 * ✅ P0优化：使用 SkillResult 统一响应格式
 */
@Deprecated
def execute(SkillContext context) {
    println "[SimpleDataQuerySkill] 开始执行"
    
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
    
    println "[SimpleDataQuerySkill] SQL: ${sql}"
    println "[SimpleDataQuerySkill] 数据源ID: ${datasourceId}"
    
    try {
        // 2. ✅ 调用原子 Tool 执行 SQL（而不是直接查库）
        def toolParams = [
            sql: sql,
            datasourceId: datasourceId
        ]
        
        String resultJson = context.callTool("execute_sql", toolParams)
        
        // 3. 解析结果
        def slurper = new JsonSlurper()
        def result = slurper.parseText(resultJson)
        
        if (!result.success) {
            return JsonOutput.toJson([
                success: false,
                type: "error",
                error: [
                    errorCode: "SQL_EXECUTION_FAILED",
                    errorMessage: "SQL执行失败: ${result.error}",
                    context: [datasourceId: datasourceId]
                ]
            ])
        }
        
        println "[SimpleDataQuerySkill] 查询成功，返回 ${result.rowCount} 行数据"
        
        // 4. 返回结果（SkillResult 统一格式）
        return JsonOutput.toJson([
            success: true,
            type: "query_result",
            data: [
                rowCount: result.rowCount,
                columns: result.columns,
                data: result.data
            ],
            metadata: [
                datasourceId: datasourceId
            ]
        ])
        
    } catch (Exception e) {
        println "[SimpleDataQuerySkill] 执行失败: ${e.message}"
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
