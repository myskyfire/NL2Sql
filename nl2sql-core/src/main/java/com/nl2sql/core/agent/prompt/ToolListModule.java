package com.nl2sql.core.agent.prompt;

import com.nl2sql.core.agent.ReActAgent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 工具列表模块 - 动态注入可用工具
 * 
 * 职责：根据当前注册的 tools 生成工具说明
 * Token 估算：~50 tokens/工具
 */
@Slf4j
@Component
public class ToolListModule implements PromptModule {
    
    private final ReActAgent agent;
    
    public ToolListModule(ReActAgent agent) {
        this.agent = agent;
    }
    
    @Override
    public String build() {
        StringBuilder sb = new StringBuilder();
        sb.append("## 可用工具\n");
        
        // 从 Agent 获取所有注册的工具
        Map<String, ReActAgent.ToolExecutor> tools = agent.getTools();
        
        for (Map.Entry<String, ReActAgent.ToolExecutor> entry : tools.entrySet()) {
            sb.append("- ").append(entry.getKey()).append(": ").append(entry.getValue().getDescription()).append("\n");
        }
        
        sb.append("\n");
        return sb.toString();
    }
    
    @Override
    public int estimateTokens() {
        if (agent == null || agent.getTools() == null) {
            return 50;
        }
        return 50 * agent.getTools().size();
    }
}
