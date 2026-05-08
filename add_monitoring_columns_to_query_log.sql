-- ============================================
-- Add table_name and query_type columns to nl2sql_query_log
-- For monitoring feature
-- ============================================

USE nl2sql_meta_db;

-- Add table_name column (JSON array format to store multiple tables)
ALTER TABLE nl2sql_query_log 
ADD COLUMN table_name VARCHAR(500) DEFAULT NULL COMMENT 'Tables involved in query (JSON array format, e.g. ["orders", "users"])' 
AFTER datasource_id;

-- Add query_type column
ALTER TABLE nl2sql_query_log 
ADD COLUMN query_type VARCHAR(50) DEFAULT NULL COMMENT 'Query type: SIMPLE, JOIN, AGGREGATION, SUBQUERY, etc.' 
AFTER table_name;

-- Add index for query_type to optimize GROUP BY performance
CREATE INDEX idx_query_type ON nl2sql_query_log (query_type);
