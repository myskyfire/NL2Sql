package com.nl2sql.core.agent.context;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 增强的上下文构建器
 * 
 * 整合对话历史、表引用信息、用户偏好等，为LLM提供更丰富的上下文
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EnhancedContext {
    
    /**
     * 用户原始问题
     */
    private String userQuery;
    
    /**
     * 会话ID
     */
    private String sessionId;
    
    /**
     * 数据源ID
     */
    private Long datasourceId;
    
    /**
     * 对话历史（最近N轮）
     */
    @Builder.Default
    private List<DialogueTurn> dialogueHistory = new ArrayList<>();
    
    /**
     * 已引用的表列表（从历史对话中提取）
     */
    @Builder.Default
    private List<String> referencedTables = new ArrayList<>();
    
    /**
     * 用户偏好设置
     */
    @Builder.Default
    private Map<String, Object> userPreferences = new HashMap<>();
    
    /**
     * 当前时间上下文（用于时间相关查询）
     */
    private TimeContext timeContext;
    
    /**
     * 行业概念映射
     */
    @Builder.Default
    private Map<String, String> industryConcepts = new HashMap<>();
    
    /**
     * 对话轮次
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DialogueTurn {
        /**
         * 角色（user/assistant）
         */
        private String role;
        
        /**
         * 消息内容
         */
        private String content;
        
        /**
         * 生成的SQL（如果是assistant）
         */
        private String sql;
        
        /**
         * 时间戳
         */
        private Long timestamp;
    }
    
    /**
     * 时间上下文
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TimeContext {
        /**
         * 当前日期
         */
        private String currentDate;
        
        /**
         * 当前星期
         */
        private String currentWeekday;
        
        /**
         * 相对时间基准（如："今天"对应的日期）
         */
        private String relativeTimeBase;
    }
    
    /**
     * 构建上下文摘要（用于Prompt）
     */
    public String buildContextSummary() {
        StringBuilder sb = new StringBuilder();
        
        // 1. 对话历史摘要
        if (!dialogueHistory.isEmpty()) {
            sb.append("【对话历史】\n");
            int startIdx = Math.max(0, dialogueHistory.size() - 5); // 最近5轮
            for (int i = startIdx; i < dialogueHistory.size(); i++) {
                DialogueTurn turn = dialogueHistory.get(i);
                String roleLabel = "user".equals(turn.getRole()) ? "用户" : "助手";
                sb.append(String.format("%s: %s\n", roleLabel, turn.getContent()));
                if (turn.getSql() != null && !turn.getSql().isEmpty()) {
                    sb.append(String.format("  SQL: %s\n", turn.getSql()));
                }
            }
            sb.append("\n");
        }
        
        // 2. 已引用的表
        if (!referencedTables.isEmpty()) {
            sb.append("【已引用的表】\n");
            sb.append(String.join(", ", referencedTables));
            sb.append("\n\n");
        }
        
        // 3. 时间上下文
        if (timeContext != null) {
            sb.append("【当前时间】\n");
            sb.append(String.format("日期: %s (%s)\n", timeContext.getCurrentDate(), timeContext.getCurrentWeekday()));
            if (timeContext.getRelativeTimeBase() != null) {
                sb.append(String.format("相对时间基准: %s\n", timeContext.getRelativeTimeBase()));
            }
            sb.append("\n");
        }
        
        // 4. 行业概念
        if (!industryConcepts.isEmpty()) {
            sb.append("【行业概念映射】\n");
            industryConcepts.forEach((k, v) -> sb.append(String.format("- %s → %s\n", k, v)));
            sb.append("\n");
        }
        
        return sb.toString();
    }
}
