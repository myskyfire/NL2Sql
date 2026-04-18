package com.nl2sql.core.agent.prompt;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 工作流程模块 - 定义 Agent 的执行流程
 * 
 * 职责：指导 LLM 如何分步骤处理用户请求
 * Token 估算：~400-600 tokens
 */
@Slf4j
@Component
public class WorkflowModule implements PromptModule {
    
    @Override
    public String build() {
        StringBuilder sb = new StringBuilder();
        
        sb.append("## 工作流程\n");
        sb.append("对于每个问题，你需要：\n");
        sb.append("1. **检查消息是否包含 [INTENT:XXX] 标记**\n");
        sb.append("   - 如果包含 [INTENT:AI_SUMMARY] → 从消息中提取用户问题和 SQL，然后调用 summarize_result 工具\n");
        sb.append("     格式：{\"name\": \"summarize_result\", \"arguments\": {\"context\": {\"lastQuery\": \"提取的用户问题\", \"generatedSQL\": \"提取的SQL\"}}}\n");
        sb.append("   - 如果包含 [INTENT:GENERATE_CHART] → 从消息中提取图表类型和 SQL，然后调用 generate_chart 工具\n");
        sb.append("     格式：{\"name\": \"generate_chart\", \"arguments\": {\"context\": {\"chartType\": \"bar/line/pie\", \"generatedSQL\": \"提取的SQL\"}}}\n");
        sb.append("   - 不要询问数据源，不要调用其他工具\n");
        sb.append("2. **⚠️ 关键第一步：检查用户消息开头的 [数据源ID: XXX] 标记**\n");
        sb.append("   - ⚠️ **重要**：每条用户消息都会以 `[数据源ID: XXX]` 开头\n");
        sb.append("   - 如果 `[数据源ID: null]` → 必须调用 clarify_datasource 获取推荐的数据源\n");
        sb.append("   - 如果 `[数据源ID: 数字]`（如 `[数据源ID: 1]`）→ **直接使用这个数字作为 datasourceId**\n");
        sb.append("   - ⚠️ **绝对禁止**：如果已有数据源ID，绝对不能再次调用 clarify_datasource！\n");
        sb.append("3. **执行查询**（数据源明确时）：\n");
        sb.append("   - ⚠️ **唯一正确做法**：直接调用 execute_standard_query(question, datasourceId)\n");
        sb.append("   - ✅ **参数格式**：平铺式，不要嵌套在 context 中\n");
        sb.append("     正确：{\"name\": \"execute_standard_query\", \"arguments\": {\"question\": \"查询某类数据\", \"datasourceId\": 1}}\n");
        sb.append("     错误：{\"name\": \"execute_standard_query\", \"arguments\": {\"context\": {...}}}\n");
        sb.append("   - ✅ execute_standard_query 会自动完成以下所有步骤：\n");
        sb.append("     1. 检索表结构 (retrieve_schema)\n");
        sb.append("     2. 生成 SQL (generate_sql)\n");
        sb.append("     3. 评估 SQL 风险（如果需要，自动调用 EXPLAIN）\n");
        sb.append("     4. 执行 SQL 并返回结果\n");
        sb.append("   - ❌ **绝对禁止**：不要手动调用 analyze_sql_risk、execute_direct_sql 等底层工具\n");
        sb.append("   - ❌ **绝对禁止**：不要自己生成 SQL，必须让 execute_standard_query 自动生成\n");
        sb.append("   - 示例：用户消息为 `[数据源ID: 1] 查询某类数据`\n");
        sb.append("     → ✅ 正确：{\"name\": \"execute_standard_query\", \"arguments\": {\"question\": \"查询某类数据\", \"datasourceId\": 1}}\n");
        sb.append("     → ❌ 错误：{\"name\": \"clarify_datasource\", \"arguments\": {...}}\n");
        sb.append("     → ❌ 错误：{\"name\": \"analyze_sql_risk\", \"arguments\": {\"sql\": \"SELECT ...\"}}\n\n");
        
        return sb.toString();
    }
    
    @Override
    public int estimateTokens() {
        return 500;
    }
}
