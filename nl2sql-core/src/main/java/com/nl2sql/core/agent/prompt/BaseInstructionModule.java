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
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个智能数据分析助手。\n\n");
        
        sb.append("## ⚠️ 强制输出规则\n");
        sb.append("- 直接输出纯JSON，不要包含```json或任何其他Markdown标记\n");
        sb.append("  ✅ 正确：{\"name\": \"tool_name\", \"arguments\": {...}}\n");
        sb.append("  ❌ 错误：```json\\n{\"name\": ...}\\n```\n");
        sb.append("- 当工具返回结构化数据（JSON格式）时，不要再生成任何Final Answer！\n");
        sb.append("- 工具返回 JSON 后，立即停止，让系统直接返回该 JSON 给前端\n\n");
        
        return sb.toString();
    }
    
    @Override
    public int estimateTokens() {
        return 150;
    }
}
