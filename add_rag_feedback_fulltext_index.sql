-- 为rag_feedback表添加FULLTEXT索引以支持全文搜索
-- 执行前请确认表存在且使用InnoDB引擎

USE nl2sql_meta_db;

-- 检查表是否存在
SELECT TABLE_NAME, ENGINE 
FROM INFORMATION_SCHEMA.TABLES 
WHERE TABLE_SCHEMA = 'nl2sql_meta_db' 
  AND TABLE_NAME = 'rag_feedback';

-- 添加FULLTEXT索引（如果不存在）
ALTER TABLE rag_feedback 
ADD FULLTEXT INDEX idx_question_fulltext (question);

-- 验证索引创建成功
SHOW INDEX FROM rag_feedback WHERE Index_type = 'FULLTEXT';
