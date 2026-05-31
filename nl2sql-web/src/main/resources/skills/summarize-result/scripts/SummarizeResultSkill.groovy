import com.nl2sql.core.agent.skills.SkillContext
import groovy.json.JsonSlurper
import groovy.json.JsonOutput

/**
 * @Deprecated Groovy skill scripts are deprecated. Use YAML workflow instead.
 * 
 * Summarize Result Skill - 对SQL查询结果进行智能总结
 */
@Deprecated
def execute(SkillContext context) {
    println "[SummarizeResultSkill] 开始执行"
    
    String question = context.getParameter("question")
    String sql = context.getParameter("sql")
    String dataJson = context.getParameter("dataJson")
    
    if (!question || !sql || !dataJson) {
        return JsonOutput.toJson([
            success: false,
            type: "error",
            error: [
                errorCode: "MISSING_PARAMS",
                errorMessage: "缺少必需参数: question, sql, dataJson"
            ]
        ])
    }
    
    try {
        // 调用 summarize_result Tool
        def result = context.callTool("summarize_result", [
            userQuery: question,
            sql: sql,
            dataJson: dataJson
        ])
        
        println "[SummarizeResultSkill] Tool调用成功"
        return result
        
    } catch (Exception e) {
        log.error("[SummarizeResultSkill] 执行失败: {}", e.message)
        e.printStackTrace()
        
        return JsonOutput.toJson([
            success: false,
            type: "error",
            error: [
                errorCode: "SKILL_EXECUTION_ERROR",
                errorMessage: "执行失败: ${e.message}"
            ]
        ])
    }
}
