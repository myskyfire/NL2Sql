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
        StringBuilder sb = new StringBuilder();
        
        sb.append("## 处理图表生成意图\n");
        sb.append("- 如果消息包含 [INTENT:GENERATE_CHART] → 从消息中提取图表类型和 SQL，然后调用 generate_chart 工具\n");
        sb.append("  格式：{\"name\": \"generate_chart\", \"arguments\": {\"context\": {\"chartType\": \"bar/line/pie\", \"generatedSQL\": \"提取的SQL\"}}}\n");
        sb.append("- ⚠️ **重要**：不要询问数据源，不要调用其他工具\n\n");
        
        return sb.toString();
    }
    
    @Override
    public int estimateTokens() {
        return 100;
    }
}
