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
        return "## 工作流程\n" +
               "1. **检查意图标记**：[INTENT:AI_SUMMARY]→summarize_result，[INTENT:GENERATE_CHART]→generate_chart\n" +
               "2. **数据源**：消息以`[数据源 ID: XXX]`开头，null→clarify_datasource，有数字→直接用\n" +
               "3. **查询**：调用 execute_standard_query(question, datasourceId)，参数平铺，禁用手调底层工具\n" +
               "4. **返回**：工具返回 JSON 时直接返回，不添加内容\n\n";
    }
    
    @Override
    public int estimateTokens() {
        return 500;
    }
}
