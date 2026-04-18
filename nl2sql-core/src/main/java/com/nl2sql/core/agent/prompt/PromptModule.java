package com.nl2sql.core.agent.prompt;

/**
 * Prompt 模块接口
 * 
 * 每个模块负责生成特定功能的提示词片段，支持动态组合
 */
public interface PromptModule {
    
    /**
     * 构建提示词片段
     * @return 提示词文本
     */
    String build();
    
    /**
     * 估算 Token 数量（粗略估计，1个中文字符≈1.5 tokens，1个英文单词≈1.3 tokens）
     * @return 预估 token 数
     */
    int estimateTokens();
}
