-- 鍒涘缓鏁版嵁搴?CREATE DATABASE IF NOT EXISTS nl2sql_db DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE nl2sql_db;

-- 鍒涘缓鐢ㄦ埛琛?CREATE TABLE IF NOT EXISTS users (
    id INT PRIMARY KEY AUTO_INCREMENT,
    username VARCHAR(50) NOT NULL COMMENT '鐢ㄦ埛鍚?,
    email VARCHAR(100) COMMENT '閭',
    phone VARCHAR(20) COMMENT '鎵嬫満鍙?,
    age INT COMMENT '骞撮緞',
    city VARCHAR(50) COMMENT '鍩庡競',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '鍒涘缓鏃堕棿'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='鐢ㄦ埛淇℃伅琛?;

-- 鍒涘缓璁㈠崟琛?CREATE TABLE IF NOT EXISTS orders (
    id INT PRIMARY KEY AUTO_INCREMENT,
    user_id INT NOT NULL COMMENT '鐢ㄦ埛ID',
    product_name VARCHAR(100) NOT NULL COMMENT '浜у搧鍚嶇О',
    amount DECIMAL(10, 2) NOT NULL COMMENT '閲戦',
    status VARCHAR(20) DEFAULT 'pending' COMMENT '鐘舵€? pending, completed, cancelled',
    order_date DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '璁㈠崟鏃ユ湡',
    FOREIGN KEY (user_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='璁㈠崟琛?;

-- 鎻掑叆娴嬭瘯鏁版嵁
INSERT INTO users (username, email, phone, age, city) VALUES
('寮犱笁', 'zhangsan@example.com', '13800138000', 25, '鍖椾含'),
('鏉庡洓', 'lisi@example.com', '13800138001', 30, '涓婃捣'),
('鐜嬩簲', 'wangwu@example.com', '13800138002', 28, '骞垮窞'),
('璧靛叚', 'zhaoliu@example.com', '13800138003', 35, '娣卞湷'),
('閽变竷', 'qianqi@example.com', '13800138004', 32, '鏉窞');

INSERT INTO orders (user_id, product_name, amount, status, order_date) VALUES
(1, 'iPhone 15', 7999.00, 'completed', '2024-01-15 10:30:00'),
(1, 'MacBook Pro', 15999.00, 'completed', '2024-01-20 14:20:00'),
(2, 'iPad Air', 4999.00, 'pending', '2024-02-01 09:15:00'),
(3, 'AirPods Pro', 1999.00, 'completed', '2024-02-10 16:45:00'),
(4, 'Apple Watch', 3199.00, 'cancelled', '2024-02-15 11:30:00'),
(2, 'iPhone 15 Pro', 9999.00, 'completed', '2024-03-01 13:20:00'),
(5, 'MacBook Air', 9499.00, 'pending', '2024-03-05 15:10:00');
