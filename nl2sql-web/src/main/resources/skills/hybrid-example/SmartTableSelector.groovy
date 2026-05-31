import com.nl2sql.core.agent.skills.SkillContext
import com.fasterxml.jackson.databind.ObjectMapper

/**
 * @Deprecated Groovy skill scripts are deprecated. Use YAML workflow instead.
 * 
 * 智能选表脚本（混合模式示例）
 * 
 * 职责：根据用户问题和检索到的 schema，智能选择最相关的表
 */
@Deprecated
class SmartTableSelector {
    
    def execute(SkillContext context) {
        def mapper = new ObjectMapper()
        
        try {
            String question = context.getParameter("question")
            def schemaResult = context.getParameter("schema")
            Long datasourceId = context.getParameter("datasourceId")
            
            log.info("[SmartTableSelector] 开始智能选表: question={}", question)
            
            // 简化版：实际应调用 LLM 进行智能选表
            // 这里演示如何从 schema 中提取表信息
            def tables = schemaResult?.tables ?: []
            def selectedTables = []
            
            // 简单关键词匹配（实际应使用 LLM）
            for (table in tables) {
                def tableName = table.tableName?.toLowerCase() ?: ""
                def tableComment = table.comment?.toLowerCase() ?: ""
                
                // 检查表名或注释是否包含问题中的关键词
                if (containsKeyword(tableName, question) || containsKeyword(tableComment, question)) {
                    selectedTables.add(table)
                }
            }
            
            // 如果没有匹配到，返回所有表（降级策略）
            if (selectedTables.isEmpty()) {
                log.warn("[SmartTableSelector] 未匹配到相关表，返回所有表")
                selectedTables = tables
            }
            
            log.info("[SmartTableSelector] 选中 {} 个表", selectedTables.size())
            
            // 构建返回结果
            def result = [
                success: true,
                selectedTables: selectedTables,
                schema: [
                    tables: selectedTables
                ]
            ]
            
            return mapper.writeValueAsString(result)
            
        } catch (Exception e) {
            log.error("[SmartTableSelector] 执行失败", e)
            def error = [
                success: false,
                error: e.message
            ]
            return mapper.writeValueAsString(error)
        }
    }
    
    /**
     * 简单的关键词匹配（实际应使用 LLM）
     */
    private boolean containsKeyword(String text, String question) {
        if (!text || !question) return false
        
        def keywords = question.split(/[\\s,，]+/).findAll { it.length() > 1 }
        return keywords.any { keyword ->
            text.contains(keyword.toLowerCase())
        }
    }
}
