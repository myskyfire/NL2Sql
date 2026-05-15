-- ============================================
-- NLP2SQL 浼佷笟鐗?- 瀹屾暣鏁版嵁搴揇DL
-- ============================================
-- 鐗堟湰: v1.2.0
-- 鐢熸垚鏃堕棿: 2026-04-05
-- 璇存槑: 鍖呭惈涓氬姟搴撳拰璁よ瘉搴撶殑鎵€鏈夎〃缁撴瀯
-- ============================================

-- ============================================
-- 1. 涓氬姟鏁版嵁搴?(nl2sql_db)
-- ============================================

CREATE DATABASE IF NOT EXISTS nl2sql_db 
DEFAULT CHARACTER SET utf8mb4 
DEFAULT COLLATE utf8mb4_unicode_ci;

USE nl2sql_db;

-- 1.1 瀹¤鏃ュ織琛?CREATE TABLE IF NOT EXISTS audit_logs (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '涓婚敭ID',
    username VARCHAR(50) NOT NULL COMMENT '鐢ㄦ埛鍚?,
    query TEXT NOT NULL COMMENT '鐢ㄦ埛鏌ヨ',
    sql TEXT COMMENT '鐢熸垚鐨凷QL',
    status VARCHAR(20) DEFAULT 'success' COMMENT '鐘舵€? success/failed/blocked',
    row_count INT DEFAULT 0 COMMENT '杩斿洖琛屾暟',
    execution_time DOUBLE COMMENT '鎵ц鏃堕棿(绉?',
    error_message TEXT COMMENT '閿欒淇℃伅',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '鍒涘缓鏃堕棿',
    INDEX idx_username (username),
    INDEX idx_status (status),
    INDEX idx_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='瀹¤鏃ュ織琛?;

-- 1.2 瀵硅瘽鍘嗗彶琛?CREATE TABLE IF NOT EXISTS conversation_history (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '涓婚敭ID',
    session_id VARCHAR(100) NOT NULL COMMENT '浼氳瘽ID',
    role VARCHAR(20) NOT NULL COMMENT '瑙掕壊: user/assistant',
    content TEXT NOT NULL COMMENT '娑堟伅鍐呭',
    context JSON COMMENT '涓婁笅鏂囦俊鎭?,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '鍒涘缓鏃堕棿',
    INDEX idx_session (session_id),
    INDEX idx_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='瀵硅瘽鍘嗗彶琛?;

-- 1.3 SQL鎵ц鏃ュ織琛?CREATE TABLE IF NOT EXISTS sql_execution_logs (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '涓婚敭ID',
    user_id BIGINT COMMENT '鐢ㄦ埛ID',
    username VARCHAR(50) COMMENT '鐢ㄦ埛鍚?,
    sql_text TEXT NOT NULL COMMENT '鎵ц鐨凷QL',
    execution_time_ms BIGINT COMMENT '鎵ц鏃堕棿(姣)',
    row_count INT COMMENT '杩斿洖琛屾暟',
    is_slow_query TINYINT(1) DEFAULT 0 COMMENT '鏄惁鎱㈡煡璇?,
    status VARCHAR(20) DEFAULT 'SUCCESS' COMMENT '鐘舵€? SUCCESS/FAILED/TIMEOUT',
    error_message TEXT COMMENT '閿欒淇℃伅',
    ip_address VARCHAR(50) COMMENT 'IP鍦板潃',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '鎵ц鏃堕棿',
    INDEX idx_user (user_id),
    INDEX idx_created (created_at),
    INDEX idx_slow (is_slow_query),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='SQL鎵ц鏃ュ織琛?;

-- 1.4 鏌ヨ妯℃澘琛?CREATE TABLE IF NOT EXISTS query_templates (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '涓婚敭ID',
    user_id BIGINT NOT NULL COMMENT '鐢ㄦ埛ID',
    name VARCHAR(100) NOT NULL COMMENT '妯℃澘鍚嶇О',
    description VARCHAR(500) COMMENT '妯℃澘鎻忚堪',
    template_sql TEXT NOT NULL COMMENT '妯℃澘SQL',
    parameters JSON COMMENT '鍙傛暟瀹氫箟',
    category VARCHAR(50) COMMENT '鍒嗙被',
    is_public TINYINT(1) DEFAULT 0 COMMENT '鏄惁鍏紑',
    usage_count INT DEFAULT 0 COMMENT '浣跨敤娆℃暟',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '鍒涘缓鏃堕棿',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '鏇存柊鏃堕棿',
    INDEX idx_user (user_id),
    INDEX idx_category (category),
    INDEX idx_public (is_public)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='鏌ヨ妯℃澘琛?;

-- 1.5 RAG鐭ヨ瘑搴撹〃
CREATE TABLE IF NOT EXISTS rag_knowledge_base (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '涓婚敭ID',
    question TEXT NOT NULL COMMENT '闂',
    answer TEXT COMMENT '绛旀',
    sql_example TEXT COMMENT 'SQL绀轰緥',
    embedding VECTOR(384) COMMENT '鍚戦噺宓屽叆',
    category VARCHAR(50) COMMENT '鍒嗙被',
    quality_score FLOAT DEFAULT 0.0 COMMENT '璐ㄩ噺璇勫垎',
    usage_count INT DEFAULT 0 COMMENT '浣跨敤娆℃暟',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '鍒涘缓鏃堕棿',
    INDEX idx_category (category),
    INDEX idx_quality (quality_score),
    FULLTEXT INDEX idx_question (question)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='RAG鐭ヨ瘑搴撹〃';

-- 1.6 琛ㄦ煡璇㈢粺璁¤〃
CREATE TABLE IF NOT EXISTS table_query_stats (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '涓婚敭ID',
    datasource_id BIGINT NOT NULL COMMENT '鏁版嵁婧怚D',
    table_name VARCHAR(100) NOT NULL COMMENT '琛ㄥ悕',
    query_count INT DEFAULT 0 COMMENT '鏌ヨ鏁伴噺',
    avg_rating DOUBLE DEFAULT 0 COMMENT '骞冲潎璇勫垎(1-5)',
    low_rating_count INT DEFAULT 0 COMMENT '浣庤瘎鍒嗘鏁(璇勫垎<=2)',
    last_query_at DATETIME COMMENT '鏈€鍚庢煡璇㈡椂闂?,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '鍒涘缓鏃堕棿',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '鏇存柊鏃堕棿',
    UNIQUE KEY uk_datasource_table (datasource_id, table_name),
    INDEX idx_datasource_id (datasource_id),
    INDEX idx_query_count (query_count),
    INDEX idx_avg_rating (avg_rating),
    INDEX idx_last_query (last_query_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='琛ㄦ煡璇㈢粺璁¤〃';


-- ============================================
-- 2. 璁よ瘉鏁版嵁搴?(nl2sql_auth_db)
-- ============================================

CREATE DATABASE IF NOT EXISTS nl2sql_auth_db 
DEFAULT CHARACTER SET utf8mb4 
DEFAULT COLLATE utf8mb4_unicode_ci;

USE nl2sql_auth_db;

-- 2.1 鐢ㄦ埛琛?CREATE TABLE IF NOT EXISTS users (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '鐢ㄦ埛ID',
    username VARCHAR(50) NOT NULL UNIQUE COMMENT '鐢ㄦ埛鍚?,
    password VARCHAR(100) NOT NULL COMMENT '瀵嗙爜(BCrypt鍔犲瘑)',
    real_name VARCHAR(50) COMMENT '鐪熷疄濮撳悕',
    email VARCHAR(100) COMMENT '閭',
    phone VARCHAR(20) COMMENT '鎵嬫満鍙?,
    role VARCHAR(20) DEFAULT 'user' COMMENT '瑙掕壊: admin/user',
    status TINYINT(1) DEFAULT 1 COMMENT '鐘舵€? 1鍚敤 0绂佺敤',
    last_login_at DATETIME COMMENT '鏈€鍚庣櫥褰曟椂闂?,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '鍒涘缓鏃堕棿',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '鏇存柊鏃堕棿',
    INDEX idx_username (username),
    INDEX idx_role (role),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='鐢ㄦ埛琛?;

-- 鎻掑叆榛樿绠＄悊鍛樿处鎴?(瀵嗙爜: admin123)
INSERT INTO users (username, password, real_name, role) VALUES 
('admin', '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', '绯荤粺绠＄悊鍛?, 'admin')
ON DUPLICATE KEY UPDATE username=username;

-- 2.2 鐧藉悕鍗曡〃
CREATE TABLE IF NOT EXISTS whitelist (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '涓婚敭ID',
    user_id BIGINT NOT NULL COMMENT '鐢ㄦ埛ID',
    added_by BIGINT COMMENT '娣诲姞浜篒D',
    reason VARCHAR(500) COMMENT '娣诲姞鍘熷洜',
    expires_at DATETIME COMMENT '杩囨湡鏃堕棿(NULL琛ㄧず姘镐箙)',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '鍒涘缓鏃堕棿',
    UNIQUE KEY uk_user (user_id),
    INDEX idx_expires (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='鐧藉悕鍗曡〃';

-- 2.3 鎿嶄綔鏃ュ織琛?CREATE TABLE IF NOT EXISTS operation_logs (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '涓婚敭ID',
    user_id BIGINT NOT NULL COMMENT '鐢ㄦ埛ID',
    username VARCHAR(50) NOT NULL COMMENT '鐢ㄦ埛鍚?,
    operation VARCHAR(50) NOT NULL COMMENT '鎿嶄綔绫诲瀷',
    target_type VARCHAR(50) COMMENT '鐩爣绫诲瀷',
    target_id BIGINT COMMENT '鐩爣ID',
    details JSON COMMENT '鎿嶄綔璇︽儏',
    ip_address VARCHAR(50) COMMENT 'IP鍦板潃',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '鎿嶄綔鏃堕棿',
    INDEX idx_user (user_id),
    INDEX idx_operation (operation),
    INDEX idx_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='鎿嶄綔鏃ュ織琛?;

-- 2.4 琛ㄦ潈闄愯〃
CREATE TABLE IF NOT EXISTS table_permissions (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '涓婚敭ID',
    user_id BIGINT NOT NULL COMMENT '鐢ㄦ埛ID',
    table_name VARCHAR(100) NOT NULL COMMENT '琛ㄥ悕',
    granted_by BIGINT COMMENT '鎺堟潈浜篒D',
    granted_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '鎺堟潈鏃堕棿',
    expires_at DATETIME COMMENT '杩囨湡鏃堕棿(NULL琛ㄧず姘镐箙)',
    UNIQUE KEY uk_user_table (user_id, table_name),
    INDEX idx_table (table_name),
    INDEX idx_expires (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='琛ㄦ潈闄愯〃';

-- 2.5 鍒楁潈闄愯〃
CREATE TABLE IF NOT EXISTS column_permissions (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '涓婚敭ID',
    user_id BIGINT NOT NULL COMMENT '鐢ㄦ埛ID',
    table_name VARCHAR(100) NOT NULL COMMENT '琛ㄥ悕',
    column_name VARCHAR(100) NOT NULL COMMENT '鍒楀悕',
    can_view TINYINT(1) DEFAULT 1 COMMENT '鏄惁鍙煡鐪?,
    need_desensitize TINYINT(1) DEFAULT 0 COMMENT '鏄惁闇€瑕佽劚鏁?,
    granted_by BIGINT COMMENT '鎺堟潈浜篒D',
    granted_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '鎺堟潈鏃堕棿',
    UNIQUE KEY uk_user_table_column (user_id, table_name, column_name),
    INDEX idx_table_column (table_name, column_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='鍒楁潈闄愯〃';

-- 2.6 Token浼氳瘽琛?CREATE TABLE IF NOT EXISTS user_sessions (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '涓婚敭ID',
    user_id BIGINT NOT NULL COMMENT '鐢ㄦ埛ID',
    token VARCHAR(100) NOT NULL UNIQUE COMMENT 'Token',
    ip_address VARCHAR(50) COMMENT 'IP鍦板潃',
    user_agent VARCHAR(500) COMMENT 'User-Agent',
    expires_at DATETIME NOT NULL COMMENT '杩囨湡鏃堕棿',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '鍒涘缓鏃堕棿',
    INDEX idx_token (token),
    INDEX idx_user (user_id),
    INDEX idx_expires (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='鐢ㄦ埛浼氳瘽琛?;


-- ============================================
-- 3. 鍒濆鍖栨暟鎹?-- ============================================

USE nl2sql_auth_db;

-- 鎻掑叆绠＄悊鍛樺埌鐧藉悕鍗?INSERT INTO whitelist (user_id, added_by, reason) 
SELECT id, id, '绯荤粺绠＄悊鍛樿嚜鍔ㄥ姞鍏? FROM users WHERE username = 'admin'
ON DUPLICATE KEY UPDATE user_id=user_id;

-- 缁欑鐞嗗憳鎺堜簣鎵€鏈夎〃鐨勬潈闄愶紙绀轰緥锛?-- 娉ㄦ剰锛氬疄闄呬娇鐢ㄦ椂闇€瑕佹牴鎹敓浜у簱鐨勮〃鍚嶈繘琛岃皟鏁?-- INSERT INTO table_permissions (user_id, table_name, granted_by)
-- SELECT u.id, 'orders', u.id FROM users u WHERE u.username = 'admin';


-- ============================================
-- 4. 瑙嗗浘鍜屽瓨鍌ㄨ繃绋嬶紙鍙€夛級
-- ============================================

USE nl2sql_db;

-- 4.1 瀹¤缁熻瑙嗗浘
CREATE OR REPLACE VIEW v_audit_stats AS
SELECT 
    DATE(created_at) as stat_date,
    COUNT(*) as total_queries,
    SUM(CASE WHEN status = 'success' THEN 1 ELSE 0 END) as success_count,
    SUM(CASE WHEN status = 'failed' THEN 1 ELSE 0 END) as failed_count,
    SUM(CASE WHEN status = 'blocked' THEN 1 ELSE 0 END) as blocked_count,
    AVG(execution_time) as avg_execution_time,
    MAX(execution_time) as max_execution_time
FROM audit_logs
GROUP BY DATE(created_at);

-- 4.2 鎱㈡煡璇㈢粺璁¤鍥?CREATE OR REPLACE VIEW v_slow_query_stats AS
SELECT 
    DATE(created_at) as stat_date,
    COUNT(*) as slow_query_count,
    AVG(execution_time_ms) as avg_execution_time,
    MAX(execution_time_ms) as max_execution_time,
    SUM(row_count) as total_rows
FROM sql_execution_logs
WHERE is_slow_query = 1
GROUP BY DATE(created_at);


-- ============================================
-- 5. 绱㈠紩浼樺寲寤鸿
-- ============================================

-- 涓哄父鐢ㄦ煡璇㈡坊鍔犲鍚堢储寮?USE nl2sql_db;
CREATE INDEX idx_audit_user_date ON audit_logs(username, created_at);
CREATE INDEX idx_conversation_session_created ON conversation_history(session_id, created_at);

USE nl2sql_auth_db;
CREATE INDEX idx_operation_user_date ON operation_logs(user_id, created_at);
CREATE INDEX idx_table_perm_user_table ON table_permissions(user_id, table_name);


-- ============================================
-- 瀹屾垚
-- ============================================

SELECT '鏁版嵁搴撳垵濮嬪寲瀹屾垚锛? as message;
SELECT '涓氬姟搴? nl2sql_db' as database_info;
SELECT '璁よ瘉搴? nl2sql_auth_db' as database_info;
SELECT '榛樿绠＄悊鍛? admin / admin123' as default_admin;
