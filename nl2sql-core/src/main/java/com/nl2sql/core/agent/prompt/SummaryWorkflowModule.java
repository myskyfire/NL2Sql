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
        StringBuilder sb = new StringBuilder();
        
        sb.append("## 处理 AI 总结意图\n");
        sb.append("- 如果消息包含 [INTENT:AI_SUMMARY] → 从消息中提取用户问题和 SQL，然后调用 summarize_result 工具\n");
        sb.append("  格式：{\"name\": \"summarize_result\", \"arguments\": {\"context\": {\"lastQuery\": \"提取的用户问题\", \"generatedSQL\": \"提取的SQL\"}}}\n");
        sb.append("- ⚠️ **重要**：不要询问数据源，不要调用其他工具\n\n");
        
        return sb.toString();
    }
    
    @Override
    public int estimateTokens() {
        return 100;
    }
}
