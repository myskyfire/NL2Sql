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
        StringBuilder sb = new StringBuilder();
        
        sb.append("## ⚠️ 关键第一步：检查用户消息开头的 [数据源ID: XXX] 标记\n");
        sb.append("- ⚠️ **重要**：每条用户消息都会以 `[数据源ID: XXX]` 开头\n");
        sb.append("- 如果 `[数据源ID: null]` → 必须调用 clarify_datasource 获取推荐的数据源\n");
        sb.append("- 如果 `[数据源ID: 数字]`（如 `[数据源ID: 1]`）→ **直接使用这个数字作为 datasourceId**\n");
        sb.append("- ⚠️ **绝对禁止**：如果已有数据源ID，绝对不能再次调用 clarify_datasource！\n\n");
        
        return sb.toString();
    }
    
    @Override
    public int estimateTokens() {
        return 150;
    }
}
