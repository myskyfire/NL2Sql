-- ============================================
-- 创建 nl2sql_query_log 表
-- 用于记录所有NL2SQL查询历史，支持默认评分功能
-- ============================================

CREATE TABLE IF NOT EXISTS `nl2sql_query_log` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `session_id` VARCHAR(128) NOT NULL COMMENT '会话ID',
    `user_id` BIGINT DEFAULT NULL COMMENT '用户ID',
    `question` TEXT NOT NULL COMMENT '用户问题（自然语言）',
    `generated_sql` TEXT NOT NULL COMMENT '生成的SQL语句',
    `executed_sql` TEXT DEFAULT NULL COMMENT '实际执行的SQL（可能经过修正）',
    `execution_success` BOOLEAN DEFAULT FALSE COMMENT '执行是否成功',
    `row_count` INT DEFAULT 0 COMMENT '返回行数',
    `execution_time_ms` DOUBLE DEFAULT 0 COMMENT '执行耗时（毫秒）',
    `error_message` TEXT DEFAULT NULL COMMENT '错误信息（如果失败）',
    `datasource_id` BIGINT DEFAULT NULL COMMENT '数据源ID',
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    
    PRIMARY KEY (`id`),
    INDEX `idx_session_id` (`session_id`),
    INDEX `idx_user_id` (`user_id`),
    INDEX `idx_created_at` (`created_at`),
    INDEX `idx_datasource_id` (`datasource_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='NL2SQL查询日志表';

-- 添加索引优化查询性能
CREATE INDEX `idx_session_created` ON `nl2sql_query_log` (`session_id`, `created_at` DESC);
