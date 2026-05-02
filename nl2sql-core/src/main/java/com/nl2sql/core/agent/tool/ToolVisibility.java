package com.nl2sql.core.agent.tool;

/**
 * 工具可见性枚举
 * 
 * 控制工具是否对 LLM 可见：
 * - PUBLIC: 对 LLM 可见，LLM 可以直接调用（高层 Skills）
 * - INTERNAL: 对 LLM 不可见，仅 Skills 内部调用（原子 Tools）
 */
public enum ToolVisibility {
    /**
     * 公开：对 LLM 可见，LLM 可以直接调用
     * 适用：高层 Skills（如 execute_standard_query, summarize_result）
     * 优势：LLM 决策空间小，准确率高
     */
    PUBLIC,
    
    /**
     * 内部：对 LLM 不可见，仅 Skills 内部调用
     * 适用：原子 Tools（如 get_table_metadata, generate_sql, execute_sql）
     * 优势：避免 LLM 选择错误的底层工具，简化决策逻辑
     */
    INTERNAL
}
