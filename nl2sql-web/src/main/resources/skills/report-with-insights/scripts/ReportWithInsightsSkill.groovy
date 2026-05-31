import com.nl2sql.core.agent.skills.SkillContext
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory

/**
 * @Deprecated Groovy skill scripts are deprecated. Use YAML workflow instead.
 * 
 * 报表与洞察技能
 * 
 * 在标准查询基础上，增加：
 * 1. 智能数据总结
 * 2. 图表推荐
 * 
 * 适用于分析型场景
 */
@Deprecated
class ReportWithInsightsSkill {
    
    private static final def log = LoggerFactory.getLogger(ReportWithInsightsSkill.class)
    
    /**
     * 执行报表生成流程
     * 
     * @param context 执行上下文
     * @return 报表结果
     */
    def execute(SkillContext context) {
        String question = context.getParameter("question")
        Long datasourceId = context.getParameter("datasourceId")
        Long userId = context.getParameter("userId")
        String username = context.getParameter("username")
        
        log.info("开始生成报表: question={}", question)
        
        try {
            // ✅ 新架构：通过 callSkill() 调用 StandardQuerySkill
            log.info("Step 1: 调用 StandardQuerySkill")
            
            def queryResult = context.callSkill("execute_standard_query", [
                question: question,
                datasourceId: datasourceId
            ])
            
            // ✅ 关键修复：检查返回结果的 status 字段
            if (queryResult.status != "success") {
                log.error("标准查询失败: {}", queryResult.error ?: queryResult.message)
                return createFailureResult(queryResult.error ?: queryResult.message)
            }
            
            List<Map<String, Object>> data = queryResult.data
            
            // Step 2: 生成智能总结（如果数据量 > 5行）
            String summary = null
            if (data != null && data.size() > 5) {
                log.info("Step 2: 生成智能总结")
                try {
                    // ✅ TODO: 未来可以改为调用 Tool
                    // def summaryResult = context.callTool("ai_summary", [
                    //     question: question,
                    //     sql: queryResult.sql,
                    //     dataJson: convertToJson(data.subList(0, Math.min(50, data.size())))
                    // ])
                    
                    // 取前50行作为样本
                    List<Map<String, Object>> sample = data.subList(0, Math.min(50, data.size()))
                    String dataJson = convertToJson(sample)
                    
                    def summaryTool = context.getBean("aiSummaryTool")
                    summary = summaryTool.summarize(question, queryResult.sql, dataJson)
                } catch (Exception e) {
                    log.warn("生成总结失败: {}", e.message)
                }
            }
            
            // Step 3: 推荐图表
            String chartRecommendation = null
            if (data != null && !data.isEmpty()) {
                log.info("Step 3: 推荐图表")
                try {
                    String dataJson = convertToJson(data)
                    
                    def chartTool = context.getBean("chartRecommendationTool")
                    chartRecommendation = chartTool.recommendCharts(question, dataJson)
                } catch (Exception e) {
                    log.warn("推荐图表失败: {}", e.message)
                }
            }
            
            log.info("报表生成完成")
            return createSuccessResult(
                data,
                queryResult.rowCount,
                queryResult.executionTime,
                queryResult.sql,
                summary,
                chartRecommendation
            )
            
        } catch (Exception e) {
            log.error("执行异常: {}", e.message, e)
            return createErrorResult(e.message)
        }
    }
    
    /**
     * 将数据转换为 JSON 字符串
     */
    private String convertToJson(List<Map<String, Object>> data) {
        try {
            ObjectMapper mapper = new ObjectMapper()
            return mapper.writeValueAsString(data)
        } catch (Exception e) {
            log.warn("转换JSON失败: {}", e.message)
            return "[]"
        }
    }
    
    // ==================== 结果工厂方法 ====================
    
    private Map<String, Object> createSuccessResult(List<Map<String, Object>> data, int rowCount,
                                                     double executionTime, String sql,
                                                     String aiSummary, String chartRecommendation) {
        return [
            success: true,
            data: data,
            rowCount: rowCount,
            executionTime: executionTime,
            sql: sql,
            aiSummary: aiSummary,
            chartRecommendation: chartRecommendation
        ]
    }
    
    private Map<String, Object> createFailureResult(String error) {
        return [
            success: false,
            error: error != null ? error : "查询失败"
        ]
    }
    
    private Map<String, Object> createErrorResult(String error) {
        return [
            success: false,
            error: error
        ]
    }
}
