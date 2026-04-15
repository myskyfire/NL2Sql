-- ============================================
-- NLP2SQL 浼佷笟锟?- 缁熶竴鍏冩暟鎹暟鎹簱DDL
-- ============================================
-- 鐗堟湰: v2.0.0
-- 鐢熸垚鏃堕棿: 2026-04-05
-- 璇存槑: 鎵€鏈夋暟鎹瓨鍌ㄥ湪鍗曚竴鏁版嵁锟?nl2sql_meta_db
--       鍖呮嫭锛氬厓鏁版嵁銆佽璇併€佸璁°€丷AG銆佹棩蹇楃瓑
-- ============================================

CREATE DATABASE IF NOT EXISTS nl2sql_meta_db 
DEFAULT CHARACTER SET utf8mb4 
DEFAULT COLLATE utf8mb4_unicode_ci;

USE nl2sql_meta_db;

-- ============================================
-- 1. 鍏冩暟鎹鐞嗘ā锟?-- ============================================

-- 1.1 鏁版嵁婧愰厤缃〃
CREATE TABLE IF NOT EXISTS datasource_config (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '涓婚敭ID',
    name VARCHAR(100) NOT NULL COMMENT '鏁版嵁婧愬悕锟?,
    db_type VARCHAR(20) NOT NULL COMMENT '鏁版嵁搴撶被锟? MYSQL/ORACLE/DAMENG/POSTGRESQL',
    host VARCHAR(200) NOT NULL COMMENT '涓绘満鍦板潃',
    port INT NOT NULL COMMENT '绔彛',
    database_name VARCHAR(100) NOT NULL COMMENT '鏁版嵁搴撳悕',
    username VARCHAR(100) NOT NULL COMMENT '鐢ㄦ埛锟?,
    password_encrypted VARCHAR(500) NOT NULL COMMENT '鍔犲瘑鍚庣殑瀵嗙爜',
    encryption_algorithm VARCHAR(50) DEFAULT 'AES-256' COMMENT '鍔犲瘑绠楁硶',
    is_active TINYINT DEFAULT 1 COMMENT '鏄惁婵€锟? 0-绂佺敤, 1-鍚敤',
    description VARCHAR(500) COMMENT '鎻忚堪',
    created_by BIGINT COMMENT '鍒涘缓浜篒D',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '鍒涘缓鏃堕棿',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '鏇存柊鏃堕棿',
    UNIQUE KEY uk_name (name),
    INDEX idx_db_type (db_type),
    INDEX idx_is_active (is_active)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='鏁版嵁婧愰厤缃〃';

-- 1.2 琛ㄥ厓鏁版嵁锟?CREATE TABLE IF NOT EXISTS table_metadata (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '涓婚敭ID',
    datasource_id BIGINT NOT NULL COMMENT '鏁版嵁婧怚D',
    table_name VARCHAR(100) NOT NULL COMMENT '琛ㄥ悕',
    table_comment VARCHAR(500) COMMENT '琛ㄦ敞锟?,
    table_type VARCHAR(20) DEFAULT 'TABLE' COMMENT '琛ㄧ被锟? TABLE/VIEW',
    schema_name VARCHAR(100) COMMENT 'Schema鍚嶇О',
    row_count_estimate BIGINT COMMENT '棰勪及琛屾暟',
    data_size_kb BIGINT COMMENT '鏁版嵁澶у皬(KB)',
    index_size_kb BIGINT COMMENT '绱㈠紩澶у皬(KB)',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '閲囬泦鏃堕棿',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '鏇存柊鏃堕棿',
    UNIQUE KEY uk_datasource_table (datasource_id, table_name),
    INDEX idx_table_name (table_name),
    INDEX idx_datasource_id (datasource_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='琛ㄥ厓鏁版嵁锟?;

-- 1.3 瀛楁鍏冩暟鎹〃
CREATE TABLE IF NOT EXISTS column_metadata (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '涓婚敭ID',
    datasource_id BIGINT NOT NULL COMMENT '鏁版嵁婧怚D',
    table_name VARCHAR(100) NOT NULL COMMENT '琛ㄥ悕',
    column_name VARCHAR(100) NOT NULL COMMENT '瀛楁锟?,
    data_type VARCHAR(50) NOT NULL COMMENT '鏁版嵁绫诲瀷',
    column_size INT COMMENT '瀛楁闀垮害',
    decimal_digits INT COMMENT '灏忔暟浣嶆暟',
    is_nullable TINYINT DEFAULT 1 COMMENT '鏄惁鍙┖: 0-锟? 1-锟?,
    column_default VARCHAR(500) COMMENT '榛樿锟?,
    column_comment VARCHAR(500) COMMENT '瀛楁娉ㄩ噴',
    is_primary_key TINYINT DEFAULT 0 COMMENT '鏄惁涓婚敭: 0-锟? 1-锟?,
    is_unique TINYINT DEFAULT 0 COMMENT '鏄惁鍞竴: 0-锟? 1-锟?,
    ordinal_position INT COMMENT '瀛楁椤哄簭',
    character_set_name VARCHAR(50) COMMENT '瀛楃锟?,
    collation_name VARCHAR(50) COMMENT '鎺掑簭瑙勫垯',
    extra_info VARCHAR(200) COMMENT '棰濆淇℃伅(auto_increment锟?',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '閲囬泦鏃堕棿',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '鏇存柊鏃堕棿',
    INDEX idx_datasource_table (datasource_id, table_name),
    INDEX idx_column_name (column_name),
    INDEX idx_datasource_id (datasource_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='瀛楁鍏冩暟鎹〃';

-- 1.4 澶栭敭鍏崇郴锟?CREATE TABLE IF NOT EXISTS foreign_key_metadata (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '涓婚敭ID',
    datasource_id BIGINT NOT NULL COMMENT '鏁版嵁婧怚D',
    fk_name VARCHAR(100) COMMENT '澶栭敭绾︽潫锟?,
    table_name VARCHAR(100) NOT NULL COMMENT '涓昏〃锟?,
    column_name VARCHAR(100) NOT NULL COMMENT '涓昏〃瀛楁',
    ref_table_name VARCHAR(100) NOT NULL COMMENT '寮曠敤琛ㄥ悕',
    ref_column_name VARCHAR(100) NOT NULL COMMENT '寮曠敤瀛楁',
    update_rule VARCHAR(20) COMMENT '鏇存柊瑙勫垯',
    delete_rule VARCHAR(20) COMMENT '鍒犻櫎瑙勫垯',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '閲囬泦鏃堕棿',
    UNIQUE KEY uk_fk (datasource_id, fk_name),
    INDEX idx_datasource_id (datasource_id),
    INDEX idx_table_name (table_name),
    INDEX idx_ref_table (ref_table_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='澶栭敭鍏崇郴锟?;

-- 1.5 鍏冩暟鎹噰闆嗘棩蹇楄〃
CREATE TABLE IF NOT EXISTS metadata_sync_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '涓婚敭ID',
    datasource_id BIGINT NOT NULL COMMENT '鏁版嵁婧怚D',
    sync_status VARCHAR(20) NOT NULL COMMENT '鍚屾鐘讹拷? SUCCESS/FAILED/PARTIAL',
    table_count INT DEFAULT 0 COMMENT '鍚屾琛ㄦ暟锟?,
    column_count INT DEFAULT 0 COMMENT '鍚屾瀛楁鏁伴噺',
    foreign_key_count INT DEFAULT 0 COMMENT '鍚屾澶栭敭鏁伴噺',
    error_message TEXT COMMENT '閿欒淇℃伅',
    started_at DATETIME COMMENT '寮€濮嬫椂锟?,
    completed_at DATETIME COMMENT '瀹屾垚鏃堕棿',
    duration_seconds INT COMMENT '鑰楁椂(锟?',
    created_by BIGINT COMMENT '鎿嶄綔浜篒D',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '璁板綍鏃堕棿',
    INDEX idx_datasource (datasource_id),
    INDEX idx_status (sync_status),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='鍏冩暟鎹噰闆嗘棩蹇楄〃';


-- ============================================
-- 2. 鐢ㄦ埛璁よ瘉涓庢潈闄愭ā锟?-- ============================================

-- 2.1 鐢ㄦ埛锟?CREATE TABLE IF NOT EXISTS users (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '鐢ㄦ埛ID',
    username VARCHAR(50) NOT NULL UNIQUE COMMENT '鐢ㄦ埛锟?,
    password VARCHAR(100) NOT NULL COMMENT '瀵嗙爜(BCrypt鍔犲瘑)',
    real_name VARCHAR(50) COMMENT '鐪熷疄濮撳悕',
    email VARCHAR(100) COMMENT '閭',
    phone VARCHAR(20) COMMENT '鎵嬫満锟?,
    `role` VARCHAR(20) DEFAULT 'user' COMMENT '瑙掕壊: admin/user',
    status TINYINT(1) DEFAULT 1 COMMENT '鐘讹拷? 1鍚敤 0绂佺敤',
    last_login_at DATETIME COMMENT '鏈€鍚庣櫥褰曟椂锟?,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '鍒涘缓鏃堕棿',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '鏇存柊鏃堕棿',
    INDEX idx_username (username),
    INDEX idx_role (role),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='鐢ㄦ埛锟?;

-- 鎻掑叆榛樿绠＄悊鍛樿处锟?(瀵嗙爜: admin123)
INSERT INTO users (username, password, real_name, role) VALUES 
('admin', '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', 'Admin', 'admin')
ON DUPLICATE KEY UPDATE username=username;

-- 2.2 鐧藉悕鍗曡〃
CREATE TABLE IF NOT EXISTS whitelist (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '涓婚敭ID',
    user_id BIGINT NOT NULL COMMENT '鐢ㄦ埛ID',
    added_by BIGINT COMMENT '娣诲姞浜篒D',
    reason VARCHAR(500) COMMENT '娣诲姞鍘熷洜',
    expires_at DATETIME COMMENT '杩囨湡鏃堕棿(NULL琛ㄧず姘镐箙)',
    is_active TINYINT(1) DEFAULT 1 COMMENT '鏄惁婵€锟?,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '鍒涘缓鏃堕棿',
    UNIQUE KEY uk_user (user_id),
    INDEX idx_expires (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='鐧藉悕鍗曡〃';

-- 2.3 鎿嶄綔鏃ュ織锟?CREATE TABLE IF NOT EXISTS operation_logs (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '涓婚敭ID',
    user_id BIGINT NOT NULL COMMENT '鐢ㄦ埛ID',
    username VARCHAR(50) NOT NULL COMMENT '鐢ㄦ埛锟?,
    operation VARCHAR(50) NOT NULL COMMENT '鎿嶄綔绫诲瀷',
    target_type VARCHAR(50) COMMENT '鐩爣绫诲瀷',
    target_id BIGINT COMMENT '鐩爣ID',
    details JSON COMMENT '鎿嶄綔璇︽儏',
    ip_address VARCHAR(50) COMMENT 'IP鍦板潃',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '鎿嶄綔鏃堕棿',
    INDEX idx_user (user_id),
    INDEX idx_operation (operation),
    INDEX idx_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='鎿嶄綔鏃ュ織锟?;

-- 2.4 琛ㄦ潈闄愯〃
CREATE TABLE IF NOT EXISTS table_permissions (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '涓婚敭ID',
    user_id BIGINT NOT NULL COMMENT '鐢ㄦ埛ID',
    table_name VARCHAR(100) NOT NULL COMMENT '琛ㄥ悕',
    granted_by BIGINT COMMENT '鎺堟潈浜篒D',
    granted_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '鎺堟潈鏃堕棿',
    expires_at DATETIME COMMENT '杩囨湡鏃堕棿(NULL琛ㄧず姘镐箙)',
    is_active TINYINT(1) DEFAULT 1 COMMENT '鏄惁婵€锟?,
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
    can_view TINYINT(1) DEFAULT 1 COMMENT '鏄惁鍙煡锟?,
    need_desensitize TINYINT(1) DEFAULT 0 COMMENT '鏄惁闇€瑕佽劚锟?,
    granted_by BIGINT COMMENT '鎺堟潈浜篒D',
    granted_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '鎺堟潈鏃堕棿',
    UNIQUE KEY uk_user_table_column (user_id, table_name, column_name),
    INDEX idx_table_column (table_name, column_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='鍒楁潈闄愯〃';

-- 2.6 Token浼氳瘽琛紙鍙€夛紝涓昏浣跨敤Redis锟?CREATE TABLE IF NOT EXISTS user_sessions (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='鐢ㄦ埛浼氳瘽锟?;


-- ============================================
-- 3. 涓氬姟鍔熻兘妯″潡
-- ============================================

-- 3.1 瀹¤鏃ュ織锟?CREATE TABLE IF NOT EXISTS audit_logs (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '涓婚敭ID',
    username VARCHAR(50) NOT NULL COMMENT '鐢ㄦ埛锟?,
    query TEXT NOT NULL COMMENT '鐢ㄦ埛鏌ヨ',
    `sql` TEXT COMMENT '鐢熸垚鐨凷QL',
    status VARCHAR(20) DEFAULT 'success' COMMENT '鐘讹拷? success/failed/blocked',
    row_count INT DEFAULT 0 COMMENT '杩斿洖琛屾暟',
    execution_time DOUBLE COMMENT '鎵ц鏃堕棿(锟?',
    error_message TEXT COMMENT '閿欒淇℃伅',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '鍒涘缓鏃堕棿',
    INDEX idx_username (username),
    INDEX idx_status (status),
    INDEX idx_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='瀹¤鏃ュ織锟?;

-- 3.2 瀵硅瘽鍘嗗彶锟?CREATE TABLE IF NOT EXISTS conversation_history (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '涓婚敭ID',
    session_id VARCHAR(100) NOT NULL COMMENT '浼氳瘽ID',
    `role` VARCHAR(20) NOT NULL COMMENT '瑙掕壊: user/assistant',
    content TEXT NOT NULL COMMENT '娑堟伅鍐呭',
    context JSON COMMENT '涓婁笅鏂囦俊锟?,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '鍒涘缓鏃堕棿',
    INDEX idx_session (session_id),
    INDEX idx_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='瀵硅瘽鍘嗗彶锟?;

-- 3.3 SQL鎵ц鏃ュ織锟?CREATE TABLE IF NOT EXISTS sql_execution_logs (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '涓婚敭ID',
    user_id BIGINT COMMENT '鐢ㄦ埛ID',
    username VARCHAR(50) COMMENT '鐢ㄦ埛锟?,
    sql_text TEXT NOT NULL COMMENT '鎵ц鐨凷QL',
    execution_time_ms BIGINT COMMENT '鎵ц鏃堕棿(姣)',
    row_count INT COMMENT '杩斿洖琛屾暟',
    is_slow_query TINYINT(1) DEFAULT 0 COMMENT '鏄惁鎱㈡煡锟?,
    status VARCHAR(20) DEFAULT 'SUCCESS' COMMENT '鐘讹拷? SUCCESS/FAILED/TIMEOUT',
    error_message TEXT COMMENT '閿欒淇℃伅',
    ip_address VARCHAR(50) COMMENT 'IP鍦板潃',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '鎵ц鏃堕棿',
    INDEX idx_user (user_id),
    INDEX idx_created (created_at),
    INDEX idx_slow (is_slow_query),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='SQL鎵ц鏃ュ織锟?;

-- 3.4 鏌ヨ妯℃澘锟?CREATE TABLE IF NOT EXISTS query_templates (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='鏌ヨ妯℃澘锟?;

-- 3.5 RAG鐭ヨ瘑搴撹〃
CREATE TABLE IF NOT EXISTS rag_knowledge_base (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '涓婚敭ID',
    question TEXT NOT NULL COMMENT '闂',
    answer TEXT COMMENT '绛旀',
    sql_example TEXT COMMENT 'SQL绀轰緥',
    category VARCHAR(50) COMMENT '鍒嗙被',
    quality_score FLOAT DEFAULT 0.0 COMMENT '璐ㄩ噺璇勫垎',
    usage_count INT DEFAULT 0 COMMENT '浣跨敤娆℃暟',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '鍒涘缓鏃堕棿',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '鏇存柊鏃堕棿',
    INDEX idx_category (category),
    INDEX idx_quality (quality_score),
    FULLTEXT INDEX idx_question (question)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='RAG鐭ヨ瘑搴撹〃';


-- ============================================
-- 4. 鍒濆鍖栨暟锟?-- ============================================

-- 鎻掑叆绠＄悊鍛樺埌鐧藉悕锟?INSERT INTO whitelist (user_id, added_by, reason, is_active) 
SELECT id, id, '绯荤粺绠＄悊鍛樿嚜鍔ㄥ姞锟?, 1 FROM users WHERE username = 'admin'
ON DUPLICATE KEY UPDATE user_id=user_id;


-- ============================================
-- 5. 瑙嗗浘锛堝彲閫夛級
-- ============================================

-- 5.1 瀹¤缁熻瑙嗗浘
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

-- 5.2 鎱㈡煡璇㈢粺璁¤锟?CREATE OR REPLACE VIEW v_slow_query_stats AS
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
-- 6. 绱㈠紩浼樺寲
-- ============================================

-- 涓哄父鐢ㄦ煡璇㈡坊鍔犲鍚堢储锟?CREATE INDEX idx_audit_user_date ON audit_logs(username, created_at);
CREATE INDEX idx_conversation_session_created ON conversation_history(session_id, created_at);
CREATE INDEX idx_operation_user_date ON operation_logs(user_id, created_at);
CREATE INDEX idx_table_perm_user_table ON table_permissions(user_id, table_name);


-- ============================================
-- 瀹屾垚
-- ============================================

SELECT '========================================' as message;
SELECT '鏁版嵁搴撳垵濮嬪寲瀹屾垚锟? as message;
SELECT '========================================' as message;
SELECT '鏁版嵁锟? nl2sql_meta_db' as database_info;
SELECT '鍖呭惈妯″潡:' as info;
SELECT '  1. 鍏冩暟鎹鐞嗭紙鏁版嵁婧愰厤缃€佽〃缁撴瀯銆佸瓧娈点€佸閿級' as module;
SELECT '  2. 鐢ㄦ埛璁よ瘉涓庢潈闄愶紙鐢ㄦ埛銆佺櫧鍚嶅崟銆佹潈闄愩€佷細璇濓級' as module;
SELECT '  3. 涓氬姟鍔熻兘锛堝璁°€佸璇濄€丼QL鏃ュ織銆佹ā鏉裤€丷AG锟? as module;
SELECT '========================================' as message;
SELECT '榛樿绠＄悊锟? admin / admin123' as default_admin;
SELECT '========================================' as message;
