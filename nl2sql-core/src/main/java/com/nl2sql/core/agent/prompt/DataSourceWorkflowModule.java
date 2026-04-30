package com.nl2sql.core.agent.prompt;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 数据源澄清工作流程模块
 * 
 * 职责：指导 LLM 如何处理数据源缺失的情况
 * Token 估算：~150 tokens
 */
@Slf4j
@Component
public class DataSourceWorkflowModule implements PromptModule {
    
    @Override
    public String build() {
        return "## 数据源处理\n" +
               "- 检查消息开头`[数据源 ID: XXX]`\n" +
               "- null → 调用 clarify_datasource\n" +
               "- 有数字 → 直接用，禁止再次澄清\n\n";
    }
    
    @Override
    public int estimateTokens() {
        return 150;
    }
}
