package com.nl2sql.core.agent.prompt;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 基础指令模块 - 所有请求共用
 * 
 * 职责：定义 Agent 的基本角色和输出格式规范
 * Token 估算：~150 tokens
 */
@Slf4j
@Component
public class BaseInstructionModule implements PromptModule {
    
    @Override
    public String build() {
        return "你是数据分析助手。\n" +
               "## 输出规则\n" +
               "- 直接输出纯 JSON，不要```json 标记\n" +
               "- 工具返回 JSON 后，直接返回，不添加额外内容\n" +
               "- 工具返回后立即停止\n\n";
    }
    
    @Override
    public int estimateTokens() {
        return 150;
    }
}
