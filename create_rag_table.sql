-- ============================================
-- RAG知识库表创建脚本（兼容MySQL 5.7+）
-- ============================================

USE nl2sql_db;

-- 如果表已存在且包含VECTOR类型，先删除
DROP TABLE IF EXISTS rag_knowledge_base;

-- 创建RAG知识库表（使用TEXT存储向量，兼容所有MySQL版本）
CREATE TABLE IF NOT EXISTS rag_knowledge_base (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    question TEXT NOT NULL COMMENT '问题',
    answer TEXT COMMENT '答案',
    sql_example TEXT COMMENT 'SQL示例',
    embedding TEXT COMMENT '向量嵌入(JSON数组格式)',
    category VARCHAR(50) COMMENT '分类',
    quality_score FLOAT DEFAULT 0.0 COMMENT '质量评分',
    usage_count INT DEFAULT 0 COMMENT '使用次数',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_category (category),
    INDEX idx_quality (quality_score),
    FULLTEXT INDEX idx_question (question)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='RAG知识库表';

-- 插入示例数据（可选）
INSERT INTO rag_knowledge_base (question, answer, sql_example, category, quality_score) VALUES
('查询用户总数', '统计系统中所有用户的数量', 'SELECT COUNT(*) as total FROM users', '统计查询', 1.0),
('查询最近订单', '获取最近的订单列表', 'SELECT * FROM orders ORDER BY created_at DESC LIMIT 10', '订单查询', 1.0),
('查询销售额', '统计总销售额', 'SELECT SUM(amount) as total_sales FROM orders', '销售统计', 1.0)
ON DUPLICATE KEY UPDATE question=question;

SELECT 'RAG知识库表创建完成' as message;
SELECT CONCAT('当前记录数: ', COUNT(*)) as info FROM rag_knowledge_base;
