package com.nl2sql.core.agent.prompt;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 图表生成工作流程模块
 * 
 * 职责：指导 LLM 如何处理 [INTENT:GENERATE_CHART] 意图
 * Token 估算：~100 tokens
 */
@Slf4j
@Component
public class ChartWorkflowModule implements PromptModule {
    
    @Override
    public String build() {
        return "## 图表生成\n" +
               "- [INTENT:GENERATE_CHART]→调用 generate_chart，提取图表类型和 SQL\n" +
               "- 不要询问数据源或调用其他工具\n\n";
    }
    
    @Override
    public int estimateTokens() {
        return 100;
    }
}
