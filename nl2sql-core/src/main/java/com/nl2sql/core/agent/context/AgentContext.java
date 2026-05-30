package com.nl2sql.core.agent.context;

import lombok.Builder;
import lombok.Data;

import java.util.HashMap;
import java.util.Map;

/**
 * 统一的 Agent 上下文对象
 * 
 * 功能：
 * - 贯穿整个执行流程（ReActAgent → SkillRouter → Skills）
 * - 替代现有的多处状态存储（SessionContext、SkillContext等）
 * - 提供统一的元数据扩展机制
 */
@Data
@Builder
public class AgentContext {
    
    /**
     * 用户 ID
     */
    private Long userId;
    
    /**
     * 用户名
     */
    private String username;
    
    /**
     * 会话 ID
     */
    private String sessionId;
    
    /**
     * 数据源 ID
     */
    private Long datasourceId;
    
    /**
     * 最近一次生成的 SQL
     */
    private String lastSQL;
    
    /**
     * 最近一次的用户问题
     */
    private String lastQuery;
    
    /**
     * 扩展字段（用于传递自定义数据）
     */
    @Builder.Default
    private Map<String, Object> metadata = new HashMap<>();
    
    /**
     * 设置扩展字段
     */
    public void setMetadata(String key, Object value) {
        this.metadata.put(key, value);
    }
    
    /**
     * 获取扩展字段
     */
    @SuppressWarnings("unchecked")
    public <T> T getMetadata(String key, Class<T> type) {
        Object value = this.metadata.get(key);
        return value != null ? type.cast(value) : null;
    }
}
