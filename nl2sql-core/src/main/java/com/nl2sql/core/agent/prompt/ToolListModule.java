package com.nl2sql.core.agent.prompt;

import com.nl2sql.core.agent.SupervisorAgent;
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

    private final SupervisorAgent agent;

    public ToolListModule(SupervisorAgent agent) {
        this.agent = agent;
    }

    @Override
    public String build() {
        StringBuilder sb = new StringBuilder();
        sb.append("## 可用工具\n");

        // ✅ 新架构：Workers 在 WorkflowEngine 中注册，此处不再动态生成
        sb.append("系统使用 Plan-and-Execute 架构，Workers 由 WorkflowEngine 编排执行。\n\n");

        return sb.toString();
    }

    @Override
    public int estimateTokens() {
        return 50;
    }
}
