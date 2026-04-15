-- 修复trade库users表中文乱码数据
-- 执行方式：mysql -h localhost -P 33061 -u root -p123456 trade --default-character-set=utf8mb4 < fix_users_data.sql

SET NAMES utf8mb4;
SET CHARACTER SET utf8mb4;

-- 禁用外键检查
SET FOREIGN_KEY_CHECKS = 0;

-- 清空现有乱码数据
TRUNCATE TABLE users;

-- 重新插入正确的中文数据（20个用户）
INSERT INTO users (username, password, real_name, gender, phone, email, province, city, district, created_at, updated_at) VALUES
('zhangsan', '$2b$12$LQv3c1yqBWVHxkd0LHAkCOYz6TtxMQJqhN8/LewY5GyYILp92S.0i', '张三', '男', '13800138001', 'zhangsan@example.com', '广东省', '深圳市', '南山区', NOW(), NOW()),
('lisi', '$2b$12$LQv3c1yqBWVHxkd0LHAkCOYz6TtxMQJqhN8/LewY5GyYILp92S.0i', '李四', '女', '13800138002', 'lisi@example.com', '北京市', '北京市', '朝阳区', NOW(), NOW()),
('wangwu', '$2b$12$LQv3c1yqBWVHxkd0LHAkCOYz6TtxMQJqhN8/LewY5GyYILp92S.0i', '王五', '男', '13800138003', 'wangwu@example.com', '上海市', '上海市', '浦东新区', NOW(), NOW()),
('zhaoliu', '$2b$12$LQv3c1yqBWVHxkd0LHAkCOYz6TtxMQJqhN8/LewY5GyYILp92S.0i', '赵六', '女', '13800138004', 'zhaoliu@example.com', '浙江省', '杭州市', '西湖区', NOW(), NOW()),
('sunqi', '$2b$12$LQv3c1yqBWVHxkd0LHAkCOYz6TtxMQJqhN8/LewY5GyYILp92S.0i', '孙七', '男', '13800138005', 'sunqi@example.com', '江苏省', '南京市', '鼓楼区', NOW(), NOW()),
('zhouba', '$2b$12$LQv3c1yqBWVHxkd0LHAkCOYz6TtxMQJqhN8/LewY5GyYILp92S.0i', '周八', '女', '13800138006', 'zhouba@example.com', '四川省', '成都市', '武侯区', NOW(), NOW()),
('wujiu', '$2b$12$LQv3c1yqBWVHxkd0LHAkCOYz6TtxMQJqhN8/LewY5GyYILp92S.0i', '吴九', '男', '13800138007', 'wujiu@example.com', '湖北省', '武汉市', '武昌区', NOW(), NOW()),
('zhengshi', '$2b$12$LQv3c1yqBWVHxkd0LHAkCOYz6TtxMQJqhN8/LewY5GyYILp92S.0i', '郑十', '女', '13800138008', 'zhengshi@example.com', '广东省', '广州市', '天河区', NOW(), NOW()),
('chenyi', '$2b$12$LQv3c1yqBWVHxkd0LHAkCOYz6TtxMQJqhN8/LewY5GyYILp92S.0i', '陈一', '男', '13800138009', 'chenyi@example.com', '山东省', '青岛市', '市南区', NOW(), NOW()),
('liner', '$2b$12$LQv3c1yqBWVHxkd0LHAkCOYz6TtxMQJqhN8/LewY5GyYILp92S.0i', '林二', '女', '13800138010', 'liner@example.com', '福建省', '厦门市', '思明区', NOW(), NOW()),
('huangsan', '$2b$12$LQv3c1yqBWVHxkd0LHAkCOYz6TtxMQJqhN8/LewY5GyYILp92S.0i', '黄三', '男', '13800138011', 'huangsan@example.com', '河南省', '郑州市', '金水区', NOW(), NOW()),
('hesi', '$2b$12$LQv3c1yqBWVHxkd0LHAkCOYz6TtxMQJqhN8/LewY5GyYILp92S.0i', '何四', '女', '13800138012', 'hesi@example.com', '湖南省', '长沙市', '岳麓区', NOW(), NOW()),
('gaowu', '$2b$12$LQv3c1yqBWVHxkd0LHAkCOYz6TtxMQJqhN8/LewY5GyYILp92S.0i', '高五', '男', '13800138013', 'gaowu@example.com', '陕西省', '西安市', '雁塔区', NOW(), NOW()),
('luoliu', '$2b$12$LQv3c1yqBWVHxkd0LHAkCOYz6TtxMQJqhN8/LewY5GyYILp92S.0i', '罗六', '女', '13800138014', 'luoliu@example.com', '重庆市', '重庆市', '渝中区', NOW(), NOW()),
('liangqi', '$2b$12$LQv3c1yqBWVHxkd0LHAkCOYz6TtxMQJqhN8/LewY5GyYILp92S.0i', '梁七', '男', '13800138015', 'liangqi@example.com', '天津市', '天津市', '和平区', NOW(), NOW()),
('songba', '$2b$12$LQv3c1yqBWVHxkd0LHAkCOYz6TtxMQJqhN8/LewY5GyYILp92S.0i', '宋八', '女', '13800138016', 'songba@example.com', '安徽省', '合肥市', '蜀山区', NOW(), NOW()),
('tangjiu', '$2b$12$LQv3c1yqBWVHxkd0LHAkCOYz6TtxMQJqhN8/LewY5GyYILp92S.0i', '唐九', '男', '13800138017', 'tangjiu@example.com', '江西省', '南昌市', '东湖区', NOW(), NOW()),
('fengshi', '$2b$12$LQv3c1yqBWVHxkd0LHAkCOYz6TtxMQJqhN8/LewY5GyYILp92S.0i', '冯十', '女', '13800138018', 'fengshi@example.com', '河北省', '石家庄市', '长安区', NOW(), NOW()),
('caoyi', '$2b$12$LQv3c1yqBWVHxkd0LHAkCOYz6TtxMQJqhN8/LewY5GyYILp92S.0i', '曹一', '男', '13800138019', 'caoyi@example.com', '辽宁省', '沈阳市', '和平区', NOW(), NOW()),
('xuer', '$2b$12$LQv3c1yqBWVHxkd0LHAkCOYz6TtxMQJqhN8/LewY5GyYILp92S.0i', '薛二', '女', '13800138020', 'xuer@example.com', '云南省', '昆明市', '五华区', NOW(), NOW());

-- 恢复外键检查
SET FOREIGN_KEY_CHECKS = 1;

-- 验证数据
SELECT COUNT(*) as total_users FROM users;
SELECT username, real_name, gender, province, city FROM users LIMIT 5;
