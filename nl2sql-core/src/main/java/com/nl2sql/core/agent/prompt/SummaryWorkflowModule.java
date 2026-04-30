package com.nl2sql.core.agent.prompt;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * AI 总结工作流程模块
 * 
 * 职责：指导 LLM 如何处理 [INTENT:AI_SUMMARY] 意图
 * Token 估算：~100 tokens
 */
@Slf4j
@Component
public class SummaryWorkflowModule implements PromptModule {
    
    @Override
    public String build() {
        return "## AI 总结\n" +
               "- [INTENT:AI_SUMMARY]→调用 summarize_result，从消息提取问题和 SQL\n" +
               "- 不要询问数据源或调用其他工具\n\n";
    }
    
    @Override
    public int estimateTokens() {
        return 100;
    }
}
