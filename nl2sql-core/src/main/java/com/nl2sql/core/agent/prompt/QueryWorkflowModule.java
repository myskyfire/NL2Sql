package com.nl2sql.core.agent.prompt;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 标准查询工作流程模块
 * 
 * 职责：指导 LLM 如何执行标准数据查询
 * Token 估算：~350 tokens
 */
@Slf4j
@Component
public class QueryWorkflowModule implements PromptModule {
    
    @Override
    public String build() {
        StringBuilder sb = new StringBuilder();
        
        sb.append("## 执行查询（数据源明确时）\n");
        sb.append("- ⚠️ **唯一正确做法**：直接调用 execute_standard_query(question, datasourceId)\n");
        sb.append("- ✅ **参数格式**：平铺式，不要嵌套在 context 中\n");
        sb.append("  正确：{\"name\": \"execute_standard_query\", \"arguments\": {\"question\": \"查询某类数据\", \"datasourceId\": 1}}\n");
        sb.append("  错误：{\"name\": \"execute_standard_query\", \"arguments\": {\"context\": {...}}}\n");
        sb.append("- ✅ execute_standard_query 会自动完成以下所有步骤：\n");
        sb.append("  1. 检索表结构 (retrieve_schema)\n");
        sb.append("  2. 生成 SQL (generate_sql)\n");
        sb.append("  3. 评估 SQL 风险（如果需要，自动调用 EXPLAIN）\n");
        sb.append("  4. 执行 SQL 并返回结果\n");
        sb.append("- ❌ **绝对禁止**：不要手动调用 analyze_sql_risk、execute_direct_sql 等底层工具\n");
        sb.append("- ❌ **绝对禁止**：不要自己生成 SQL，必须让 execute_standard_query 自动生成\n");
        sb.append("- 示例：用户消息为 `[数据源ID: 1] 查询某类数据`\n");
        sb.append("  → ✅ 正确：{\"name\": \"execute_standard_query\", \"arguments\": {\"question\": \"查询某类数据\", \"datasourceId\": 1}}\n");
        sb.append("  → ❌ 错误：{\"name\": \"clarify_datasource\", \"arguments\": {...}}\n");
        sb.append("  → ❌ 错误：{\"name\": \"analyze_sql_risk\", \"arguments\": {\"sql\": \"SELECT ...\"}}\n\n");
        
        return sb.toString();
    }
    
    @Override
    public int estimateTokens() {
        return 350;
    }
}
