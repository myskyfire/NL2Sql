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
        return "## 执行查询\n" +
               "- 调用 execute_standard_query(question, datasourceId)\n" +
               "- 参数平铺，不嵌套 context\n" +
               "- 该工具自动完成：检索表结构→生成 SQL→风险评估→执行\n" +
               "- 禁止手动调用底层工具或自生成 SQL\n" +
               "- 示例：`[数据源 ID: 1] 查询订单`→{\"name\":\"execute_standard_query\",\"arguments\":{\"question\":\"查询订单\",\"datasourceId\":1}}\n\n";
    }
    
    @Override
    public int estimateTokens() {
        return 350;
    }
}
