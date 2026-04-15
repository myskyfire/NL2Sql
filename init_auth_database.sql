-- 鍒涘缓鏉冮檺绠＄悊鏁版嵁搴?CREATE DATABASE IF NOT EXISTS nl2sql_auth_db DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE nl2sql_auth_db;

-- 鐢ㄦ埛琛?CREATE TABLE IF NOT EXISTS users (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '鐢ㄦ埛ID',
    username VARCHAR(50) NOT NULL UNIQUE COMMENT '鐢ㄦ埛鍚?,
    password VARCHAR(255) NOT NULL COMMENT '瀵嗙爜(鍔犲瘑)',
    real_name VARCHAR(50) COMMENT '鐪熷疄濮撳悕',
    email VARCHAR(100) COMMENT '閭',
    role ENUM('admin', 'user') NOT NULL DEFAULT 'user' COMMENT '瑙掕壊: admin绠＄悊鍛? user鏅€氱敤鎴?,
    is_active TINYINT(1) NOT NULL DEFAULT 1 COMMENT '鏄惁婵€娲?,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '鍒涘缓鏃堕棿',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '鏇存柊鏃堕棿',
    last_login_at DATETIME COMMENT '鏈€鍚庣櫥褰曟椂闂?,
    INDEX idx_username (username),
    INDEX idx_role (role)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='鐢ㄦ埛琛?;

-- 鐧藉悕鍗曡〃
CREATE TABLE IF NOT EXISTS whitelist (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'ID',
    user_id BIGINT NOT NULL COMMENT '鐢ㄦ埛ID',
    added_by BIGINT NOT NULL COMMENT '娣诲姞浜篒D',
    reason VARCHAR(500) COMMENT '娣诲姞鍘熷洜',
    expires_at DATETIME COMMENT '杩囨湡鏃堕棿(NULL琛ㄧず姘镐箙)',
    is_active TINYINT(1) NOT NULL DEFAULT 1 COMMENT '鏄惁鏈夋晥',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '娣诲姞鏃堕棿',
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY (added_by) REFERENCES users(id) ON DELETE CASCADE,
    UNIQUE KEY uk_user_id (user_id),
    INDEX idx_expires (expires_at),
    INDEX idx_active (is_active)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='鐧藉悕鍗曡〃';

-- 鎿嶄綔鏃ュ織琛?CREATE TABLE IF NOT EXISTS operation_logs (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '鏃ュ織ID',
    operator_id BIGINT NOT NULL COMMENT '鎿嶄綔浜篒D',
    operation_type VARCHAR(50) NOT NULL COMMENT '鎿嶄綔绫诲瀷: LOGIN, ADD_WHITELIST, REMOVE_WHITELIST, QUERY, GRANT_TABLE, REVOKE_TABLE',
    target_user_id BIGINT COMMENT '鐩爣鐢ㄦ埛ID',
    description VARCHAR(1000) COMMENT '鎿嶄綔鎻忚堪',
    ip_address VARCHAR(50) COMMENT 'IP鍦板潃',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '鎿嶄綔鏃堕棿',
    INDEX idx_operator (operator_id),
    INDEX idx_type (operation_type),
    INDEX idx_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='鎿嶄綔鏃ュ織琛?;

-- 琛ㄦ巿鏉冭〃
CREATE TABLE IF NOT EXISTS table_permissions (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'ID',
    user_id BIGINT NOT NULL COMMENT '鐢ㄦ埛ID',
    table_name VARCHAR(100) NOT NULL COMMENT '琛ㄥ悕',
    granted_by BIGINT NOT NULL COMMENT '鎺堟潈浜篒D',
    granted_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '鎺堟潈鏃堕棿',
    expires_at DATETIME COMMENT '杩囨湡鏃堕棿(NULL琛ㄧず姘镐箙)',
    is_active TINYINT(1) NOT NULL DEFAULT 1 COMMENT '鏄惁鏈夋晥',
    UNIQUE KEY uk_user_table (user_id, table_name),
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY (granted_by) REFERENCES users(id) ON DELETE CASCADE,
    INDEX idx_table_name (table_name),
    INDEX idx_active (is_active)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='琛ㄦ巿鏉冭〃';

-- 鎻掑叆榛樿绠＄悊鍛樿处鍙?(瀵嗙爜: admin123, BCrypt鍔犲瘑)
INSERT INTO users (username, password, real_name, role, is_active) 
VALUES ('admin', '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', '绯荤粺绠＄悊鍛?, 'admin', 1);

-- 鎻掑叆娴嬭瘯鏅€氱敤鎴?(瀵嗙爜: user123)
INSERT INTO users (username, password, real_name, role, is_active) 
VALUES 
('zhangsan', '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', '寮犱笁', 'user', 1),
('lisi', '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', '鏉庡洓', 'user', 1);

-- 灏嗗紶涓夊姞鍏ョ櫧鍚嶅崟
INSERT INTO whitelist (user_id, added_by, reason, is_active) 
VALUES (2, 1, '娴嬭瘯鐢ㄦ埛', 1);
