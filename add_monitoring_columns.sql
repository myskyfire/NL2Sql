-- ============================================
-- Add monitoring columns to nl2sql_query_log
-- For tracking normalization and cache effectiveness
-- ============================================

USE nl2sql_meta_db;

-- 1. 添加归一化相关字段
ALTER TABLE `nl2sql_query_log` 
ADD COLUMN `normalized_query` TEXT DEFAULT NULL COMMENT '归一化后的查询文本' AFTER `question`,
ADD COLUMN `has_person_entity` TINYINT(1) DEFAULT 0 COMMENT '是否包含人名实体(HanLP识别)' AFTER `normalized_query`,
ADD COLUMN `has_location_entity` TINYINT(1) DEFAULT 0 COMMENT '是否包含地名实体(HanLP识别)' AFTER `has_person_entity`,
ADD COLUMN `normalization_method` VARCHAR(50) DEFAULT NULL COMMENT '归一化方式: regex/hanlp/mixed' AFTER `has_location_entity`;

-- 2. 添加缓存相关字段
ALTER TABLE `nl2sql_query_log` 
ADD COLUMN `cache_level` VARCHAR(20) DEFAULT NULL COMMENT '缓存层级: L1/L2/L3/MISS' AFTER `normalization_method`,
ADD COLUMN `cache_hit` TINYINT(1) DEFAULT 0 COMMENT '是否命中缓存' AFTER `cache_level`;

-- 3. 添加RAG相关字段
ALTER TABLE `nl2sql_query_log` 
ADD COLUMN `rag_examples_count` INT DEFAULT 0 COMMENT 'RAG检索到的示例数量' AFTER `cache_hit`,
ADD COLUMN `industry_terms_matched` TEXT DEFAULT NULL COMMENT '匹配到的行业术语(JSON数组)' AFTER `rag_examples_count`;

-- 验证
DESCRIBE `nl2sql_query_log`;
