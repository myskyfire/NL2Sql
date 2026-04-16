-- ============================================
-- Create nl2sql_query_log table
-- For recording all NL2SQL query history, supporting default rating feature
-- ============================================

CREATE TABLE IF NOT EXISTS `nl2sql_query_log` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Primary key ID',
    `session_id` VARCHAR(128) NOT NULL COMMENT 'Session ID',
    `user_id` BIGINT DEFAULT NULL COMMENT 'User ID',
    `question` TEXT NOT NULL COMMENT 'User question (natural language)',
    `generated_sql` TEXT NOT NULL COMMENT 'Generated SQL statement',
    `executed_sql` TEXT DEFAULT NULL COMMENT 'Actually executed SQL (may be corrected)',
    `execution_success` BOOLEAN DEFAULT FALSE COMMENT 'Execution success or not',
    `row_count` INT DEFAULT 0 COMMENT 'Returned row count',
    `execution_time_ms` DOUBLE DEFAULT 0 COMMENT 'Execution time (milliseconds)',
    `error_message` TEXT DEFAULT NULL COMMENT 'Error message (if failed)',
    `datasource_id` BIGINT DEFAULT NULL COMMENT 'Datasource ID',
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT 'Creation time',
    `updated_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Update time',
    
    PRIMARY KEY (`id`),
    INDEX `idx_session_id` (`session_id`),
    INDEX `idx_user_id` (`user_id`),
    INDEX `idx_created_at` (`created_at`),
    INDEX `idx_datasource_id` (`datasource_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='NL2SQL Query Log Table';

-- Add index to optimize query performance
CREATE INDEX `idx_session_created` ON `nl2sql_query_log` (`session_id`, `created_at` DESC);
