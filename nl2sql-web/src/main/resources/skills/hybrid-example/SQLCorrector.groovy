import com.nl2sql.core.agent.skills.SkillContext
import com.fasterxml.jackson.databind.ObjectMapper

/**
 * SQL验证与修正脚本（混合模式示例）
 * 
 * 职责：验证 SQL 语法，失败时自动修正（最多 N 次）
 */
class SQLCorrector {
    
    def execute(SkillContext context) {
        def mapper = new ObjectMapper()
        
        try {
            String sql = context.getParameter("sql")
            String question = context.getParameter("question")
            Long datasourceId = context.getParameter("datasourceId")
            int maxRetries = context.getParameter("max_retries") ?: 2
            
            log.info("[SQLCorrector] 开始验证与修正: maxRetries={}", maxRetries)
            
            def validationResult = validateSQL(sql, datasourceId, context)
            
            // 如果验证通过，直接返回
            if (validationResult.valid) {
                log.info("[SQLCorrector] SQL 验证通过")
                def result = [
                    success: true,
                    sql: sql,
                    wasModified: false,
                    originalSql: sql
                ]
                return mapper.writeValueAsString(result)
            }
            
            // 验证失败，尝试修正
            log.warn("[SQLCorrector] SQL 验证失败: {}", validationResult.error)
            String correctedSql = sql
            boolean wasModified = false
            
            for (int i = 0; i < maxRetries; i++) {
                log.info("[SQLCorrector] 第 {} 次修正尝试", i + 1)
                
                // 调用 LLM 修正 SQL（简化版：实际应调用 LLMService）
                correctedSql = correctSQLWithLLM(correctedSql, validationResult.error, question, context)
                wasModified = true
                
                // 重新验证
                validationResult = validateSQL(correctedSql, datasourceId, context)
                if (validationResult.valid) {
                    log.info("[SQLCorrector] 第 {} 次修正成功", i + 1)
                    break
                }
                
                log.warn("[SQLCorrector] 第 {} 次修正仍失败", i + 1)
            }
            
            def result = [
                success: validationResult.valid,
                sql: correctedSql,
                wasModified: wasModified,
                originalSql: sql,
                error: validationResult.valid ? null : validationResult.error
            ]
            
            return mapper.writeValueAsString(result)
            
        } catch (Exception e) {
            log.error("[SQLCorrector] 执行失败", e)
            def error = [
                success: false,
                error: e.message
            ]
            return mapper.writeValueAsString(error)
        }
    }
    
    /**
     * 验证 SQL（调用 validate_sql Tool）
     */
    private def validateSQL(String sql, Long datasourceId, SkillContext context) {
        try {
            String resultJson = context.callTool("validate_sql", [
                sql: sql,
                datasourceId: datasourceId
            ])
            return new ObjectMapper().readValue(resultJson, Map.class)
        } catch (Exception e) {
            log.error("[SQLCorrector] 验证失败", e)
            return [valid: false, error: e.message]
        }
    }
    
    /**
     * 使用 LLM 修正 SQL（简化版）
     * 
     * 注意：实际实现应调用 LLMService.generateWithPrompt()
     * 这里仅演示如何集成 LLM 调用
     */
    private String correctSQLWithLLM(String sql, String error, String question, SkillContext context) {
        log.info("[SQLCorrector] 调用 LLM 修正 SQL")
        
        // TODO: 实际应调用 LLMService
        // def llmService = context.getApplicationContext().getBean(LLMService.class)
        // def prompt = "修正以下 SQL，错误信息：${error}\n原始SQL：${sql}"
        // return llmService.generate(prompt)
        
        // 简化版：返回原 SQL（仅演示流程）
        return sql
    }
}
