-- ============================================
-- Add selected_tables column to nl2sql_query_log
-- For caching the final table list used in 5-star feedback
-- ============================================

USE nl2sql_meta_db;

ALTER TABLE `nl2sql_query_log` 
ADD COLUMN `selected_tables` TEXT DEFAULT NULL COMMENT 'Selected tables (JSON array, e.g. ["orders","users"])' AFTER `datasource_id`;

-- Verify
DESCRIBE `nl2sql_query_log`;
