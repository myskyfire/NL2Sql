-- 创建对话历史表
CREATE TABLE IF NOT EXISTS conversation_history (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    session_id VARCHAR(64) NOT NULL COMMENT '会话ID',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    role VARCHAR(20) NOT NULL COMMENT '角色: user/assistant/tool/system',
    content TEXT COMMENT '消息内容',
    name VARCHAR(100) COMMENT '工具名称(仅tool角色)',
    tool_call_id VARCHAR(100) COMMENT '工具调用ID',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    
    INDEX idx_session_created (session_id, created_at),
    INDEX idx_user_session (user_id, session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='对话历史表';

-- 添加索引优化查询性能
ALTER TABLE conversation_history ADD INDEX idx_session_time (session_id, created_at DESC);
