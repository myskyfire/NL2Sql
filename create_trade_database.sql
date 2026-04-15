-- ============================================
-- 电商模拟数据库 - trade
-- ============================================

CREATE DATABASE IF NOT EXISTS trade DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE trade;

-- 1. 用户表
CREATE TABLE users (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '用户ID',
    username VARCHAR(50) NOT NULL UNIQUE COMMENT '用户名',
    password VARCHAR(255) NOT NULL COMMENT '密码',
    real_name VARCHAR(50) COMMENT '真实姓名',
    gender VARCHAR(10) COMMENT '性别',
    phone VARCHAR(20) COMMENT '手机号',
    email VARCHAR(100) COMMENT '邮箱',
    province VARCHAR(50) COMMENT '省份',
    city VARCHAR(50) COMMENT '城市',
    district VARCHAR(50) COMMENT '区县',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '注册时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_province (province),
    INDEX idx_gender (gender)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

-- 2. 收货地址表
CREATE TABLE user_addresses (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '地址ID',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    receiver_name VARCHAR(50) NOT NULL COMMENT '收货人',
    receiver_phone VARCHAR(20) NOT NULL COMMENT '收货电话',
    province VARCHAR(50) NOT NULL COMMENT '省份',
    city VARCHAR(50) NOT NULL COMMENT '城市',
    district VARCHAR(50) NOT NULL COMMENT '区县',
    detail_address VARCHAR(200) NOT NULL COMMENT '详细地址',
    is_default TINYINT DEFAULT 0 COMMENT '是否默认地址',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    FOREIGN KEY (user_id) REFERENCES users(id),
    INDEX idx_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='收货地址表';

-- 3. 商品分类表
CREATE TABLE product_categories (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '分类ID',
    category_name VARCHAR(50) NOT NULL COMMENT '分类名称',
    parent_id BIGINT DEFAULT 0 COMMENT '父分类ID',
    level INT DEFAULT 1 COMMENT '层级',
    description VARCHAR(200) COMMENT '描述',
    INDEX idx_parent (parent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品分类表';

-- 4. 商品表
CREATE TABLE products (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '商品ID',
    product_name VARCHAR(200) NOT NULL COMMENT '商品名称',
    category_id BIGINT COMMENT '分类ID',
    brand VARCHAR(100) COMMENT '品牌',
    model VARCHAR(100) COMMENT '型号',
    specification VARCHAR(200) COMMENT '规格',
    description TEXT COMMENT '商品描述',
    detail_info TEXT COMMENT '详细介绍',
    price DECIMAL(10,2) NOT NULL COMMENT '售价',
    cost_price DECIMAL(10,2) COMMENT '成本价',
    status VARCHAR(20) DEFAULT '上架' COMMENT '状态',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    FOREIGN KEY (category_id) REFERENCES product_categories(id),
    INDEX idx_category (category_id),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品表';

-- 5. 库存表
CREATE TABLE inventory (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '库存ID',
    product_id BIGINT NOT NULL COMMENT '商品ID',
    warehouse_name VARCHAR(100) DEFAULT '主仓库' COMMENT '仓库名称',
    stock_quantity INT NOT NULL DEFAULT 0 COMMENT '库存数量',
    locked_quantity INT NOT NULL DEFAULT 0 COMMENT '锁定数量',
    available_quantity INT NOT NULL DEFAULT 0 COMMENT '可用库存',
    last_updated DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新',
    FOREIGN KEY (product_id) REFERENCES products(id),
    UNIQUE KEY uk_product_warehouse (product_id, warehouse_name),
    INDEX idx_product (product_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='库存表';

-- 6. 订单主表
CREATE TABLE orders (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '订单ID',
    order_no VARCHAR(50) NOT NULL UNIQUE COMMENT '订单号',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    total_amount DECIMAL(10,2) NOT NULL COMMENT '订单总额',
    discount_amount DECIMAL(10,2) DEFAULT 0 COMMENT '优惠金额',
    actual_amount DECIMAL(10,2) NOT NULL COMMENT '实付金额(GMV)',
    status VARCHAR(20) DEFAULT '待支付' COMMENT '订单状态',
    payment_method VARCHAR(50) COMMENT '支付方式',
    payment_time DATETIME COMMENT '支付时间',
    shipping_address VARCHAR(500) COMMENT '收货地址',
    receiver_name VARCHAR(50) COMMENT '收货人',
    receiver_phone VARCHAR(20) COMMENT '收货电话',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '下单时间',
    paid_at DATETIME COMMENT '支付时间',
    shipped_at DATETIME COMMENT '发货时间',
    completed_at DATETIME COMMENT '完成时间',
    FOREIGN KEY (user_id) REFERENCES users(id),
    INDEX idx_user (user_id),
    INDEX idx_status (status),
    INDEX idx_created (created_at),
    INDEX idx_order_no (order_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单主表';

-- 7. 订单明细表
CREATE TABLE order_items (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '明细ID',
    order_id BIGINT NOT NULL COMMENT '订单ID',
    product_id BIGINT NOT NULL COMMENT '商品ID',
    product_name VARCHAR(200) NOT NULL COMMENT '商品名称',
    product_spec VARCHAR(200) COMMENT '商品规格',
    quantity INT NOT NULL COMMENT '购买数量',
    unit_price DECIMAL(10,2) NOT NULL COMMENT '单价',
    subtotal DECIMAL(10,2) NOT NULL COMMENT '小计',
    return_status VARCHAR(20) DEFAULT '无退货' COMMENT '退货状态',
    return_quantity INT DEFAULT 0 COMMENT '退货数量',
    FOREIGN KEY (order_id) REFERENCES orders(id),
    FOREIGN KEY (product_id) REFERENCES products(id),
    INDEX idx_order (order_id),
    INDEX idx_product (product_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单明细表';

-- 8. 退货表
CREATE TABLE returns (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '退货ID',
    return_no VARCHAR(50) NOT NULL UNIQUE COMMENT '退货单号',
    order_id BIGINT NOT NULL COMMENT '订单ID',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    reason VARCHAR(500) COMMENT '退货原因',
    status VARCHAR(20) DEFAULT '申请中' COMMENT '退货状态',
    return_quantity INT NOT NULL COMMENT '退货数量',
    refund_amount DECIMAL(10,2) NOT NULL COMMENT '退款金额',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '申请时间',
    processed_at DATETIME COMMENT '处理时间',
    FOREIGN KEY (order_id) REFERENCES orders(id),
    FOREIGN KEY (user_id) REFERENCES users(id),
    INDEX idx_order (order_id),
    INDEX idx_user (user_id),
    INDEX idx_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='退货表';

-- ============================================
-- 插入测试数据
-- ============================================

-- 插入商品分类
INSERT INTO product_categories (category_name, parent_id, level, description) VALUES
('电子产品', 0, 1, '各类电子设备'),
('服装', 0, 1, '服装服饰'),
('食品', 0, 1, '食品饮料'),
('手机', 1, 2, '智能手机'),
('电脑', 1, 2, '笔记本电脑'),
('男装', 2, 2, '男士服装'),
('女装', 2, 2, '女士服装'),
('零食', 3, 2, '休闲零食'),
('饮料', 3, 2, '饮品');

-- 插入20个用户
INSERT INTO users (username, password, real_name, gender, phone, email, province, city, district) VALUES
('zhangsan', '$2b$12$LQv3c1yqBWVHxkd0LHAkCOYz6TtxMQJqhN8/LewY5GyYILp92S.0i', '张三', '男', '13800138001', 'zhangsan@example.com', '广东省', '深圳市', '南山区'),
('lisi', '$2b$12$LQv3c1yqBWVHxkd0LHAkCOYz6TtxMQJqhN8/LewY5GyYILp92S.0i', '李四', '女', '13800138002', 'lisi@example.com', '北京市', '北京市', '朝阳区'),
('wangwu', '$2b$12$LQv3c1yqBWVHxkd0LHAkCOYz6TtxMQJqhN8/LewY5GyYILp92S.0i', '王五', '男', '13800138003', 'wangwu@example.com', '上海市', '上海市', '浦东新区'),
('zhaoliu', '$2b$12$LQv3c1yqBWVHxkd0LHAkCOYz6TtxMQJqhN8/LewY5GyYILp92S.0i', '赵六', '女', '13800138004', 'zhaoliu@example.com', '浙江省', '杭州市', '西湖区'),
('sunqi', '$2b$12$LQv3c1yqBWVHxkd0LHAkCOYz6TtxMQJqhN8/LewY5GyYILp92S.0i', '孙七', '男', '13800138005', 'sunqi@example.com', '江苏省', '南京市', '鼓楼区'),
('zhouba', '$2b$12$LQv3c1yqBWVHxkd0LHAkCOYz6TtxMQJqhN8/LewY5GyYILp92S.0i', '周八', '女', '13800138006', 'zhouba@example.com', '四川省', '成都市', '武侯区'),
('wujiu', '$2b$12$LQv3c1yqBWVHxkd0LHAkCOYz6TtxMQJqhN8/LewY5GyYILp92S.0i', '吴九', '男', '13800138007', 'wujiu@example.com', '湖北省', '武汉市', '武昌区'),
('zhengshi', '$2b$12$LQv3c1yqBWVHxkd0LHAkCOYz6TtxMQJqhN8/LewY5GyYILp92S.0i', '郑十', '女', '13800138008', 'zhengshi@example.com', '广东省', '广州市', '天河区'),
('chenyi', '$2b$12$LQv3c1yqBWVHxkd0LHAkCOYz6TtxMQJqhN8/LewY5GyYILp92S.0i', '陈一', '男', '13800138009', 'chenyi@example.com', '山东省', '青岛市', '市南区'),
('liner', '$2b$12$LQv3c1yqBWVHxkd0LHAkCOYz6TtxMQJqhN8/LewY5GyYILp92S.0i', '林二', '女', '13800138010', 'liner@example.com', '福建省', '厦门市', '思明区'),
('huangsan', '$2b$12$LQv3c1yqBWVHxkd0LHAkCOYz6TtxMQJqhN8/LewY5GyYILp92S.0i', '黄三', '男', '13800138011', 'huangsan@example.com', '河南省', '郑州市', '金水区'),
('hesi', '$2b$12$LQv3c1yqBWVHxkd0LHAkCOYz6TtxMQJqhN8/LewY5GyYILp92S.0i', '何四', '女', '13800138012', 'hesi@example.com', '湖南省', '长沙市', '岳麓区'),
('gaowu', '$2b$12$LQv3c1yqBWVHxkd0LHAkCOYz6TtxMQJqhN8/LewY5GyYILp92S.0i', '高五', '男', '13800138013', 'gaowu@example.com', '陕西省', '西安市', '雁塔区'),
('luoliu', '$2b$12$LQv3c1yqBWVHxkd0LHAkCOYz6TtxMQJqhN8/LewY5GyYILp92S.0i', '罗六', '女', '13800138014', 'luoliu@example.com', '重庆市', '重庆市', '渝中区'),
('liangqi', '$2b$12$LQv3c1yqBWVHxkd0LHAkCOYz6TtxMQJqhN8/LewY5GyYILp92S.0i', '梁七', '男', '13800138015', 'liangqi@example.com', '天津市', '天津市', '和平区'),
('songba', '$2b$12$LQv3c1yqBWVHxkd0LHAkCOYz6TtxMQJqhN8/LewY5GyYILp92S.0i', '宋八', '女', '13800138016', 'songba@example.com', '安徽省', '合肥市', '蜀山区'),
('tangjiu', '$2b$12$LQv3c1yqBWVHxkd0LHAkCOYz6TtxMQJqhN8/LewY5GyYILp92S.0i', '唐九', '男', '13800138017', 'tangjiu@example.com', '江西省', '南昌市', '东湖区'),
('fengshi', '$2b$12$LQv3c1yqBWVHxkd0LHAkCOYz6TtxMQJqhN8/LewY5GyYILp92S.0i', '冯十', '女', '13800138018', 'fengshi@example.com', '河北省', '石家庄市', '长安区'),
('caoyi', '$2b$12$LQv3c1yqBWVHxkd0LHAkCOYz6TtxMQJqhN8/LewY5GyYILp92S.0i', '曹一', '男', '13800138019', 'caoyi@example.com', '辽宁省', '沈阳市', '和平区'),
('xuer', '$2b$12$LQv3c1yqBWVHxkd0LHAkCOYz6TtxMQJqhN8/LewY5GyYILp92S.0i', '薛二', '女', '13800138020', 'xuer@example.com', '云南省', '昆明市', '五华区');

-- 插入收货地址（每个用户1-3个地址）
INSERT INTO user_addresses (user_id, receiver_name, receiver_phone, province, city, district, detail_address, is_default) VALUES
(1, '张三', '13800138001', '广东省', '深圳市', '南山区', '科技南路1001号', 1),
(1, '张三', '13800138001', '广东省', '深圳市', '福田区', '深南大道2002号', 0),
(2, '李四', '13800138002', '北京市', '北京市', '朝阳区', '建国路3003号', 1),
(3, '王五', '13800138003', '上海市', '上海市', '浦东新区', '世纪大道4004号', 1),
(3, '王五', '13800138003', '上海市', '上海市', '黄浦区', '南京东路5005号', 0),
(4, '赵六', '13800138004', '浙江省', '杭州市', '西湖区', '文三路6006号', 1),
(5, '孙七', '13800138005', '江苏省', '南京市', '鼓楼区', '中山北路7007号', 1),
(6, '周八', '13800138006', '四川省', '成都市', '武侯区', '人民南路8008号', 1),
(7, '吴九', '13800138007', '湖北省', '武汉市', '武昌区', '解放路9009号', 1),
(8, '郑十', '13800138008', '广东省', '广州市', '天河区', '天河路1010号', 1),
(9, '陈一', '13800138009', '山东省', '青岛市', '市南区', '香港中路1111号', 1),
(10, '林二', '13800138010', '福建省', '厦门市', '思明区', '厦禾路1212号', 1);

-- 插入30个商品
INSERT INTO products (product_name, category_id, brand, model, specification, description, detail_info, price, cost_price, status) VALUES
('iPhone 15 Pro', 4, 'Apple', 'A3108', '256GB 钛金属', '最新款iPhone，搭载A17 Pro芯片', '6.1英寸超视网膜XDR显示屏，钛金属设计，动作按钮，4800万像素主摄', 8999.00, 7200.00, '上架'),
('华为Mate 60 Pro', 4, '华为', 'ALN-AL00', '512GB 雅川青', '卫星通话功能，超强续航', '6.82英寸OLED屏幕，麒麟9000S芯片，5000mAh电池，支持卫星通信', 6999.00, 5600.00, '上架'),
('小米14', 4, '小米', '23127PN0CC', '16GB+512GB', '徕卡光学镜头，骁龙8 Gen3', '6.36英寸1.5K屏幕，徕卡Summilux镜头，澎湃OS系统', 4599.00, 3700.00, '上架'),
('MacBook Pro 14', 5, 'Apple', 'MRX33CH/A', 'M3 Pro 18GB+512GB', '专业级笔记本，性能强劲', '14.2英寸Liquid视网膜XDR显示屏，M3 Pro芯片，最长17小时续航', 14999.00, 12000.00, '上架'),
('联想ThinkPad X1', 5, '联想', '21JNA00F00', 'i7-1355U 16GB+512GB', '商务轻薄本，经典小红点', '14英寸2.8K屏幕，第13代英特尔酷睿，1.18kg轻薄机身', 9999.00, 8000.00, '上架'),
('戴尔XPS 13', 5, '戴尔', 'XPS9315', 'i5-1230U 16GB+512GB', '超薄边框，便携办公', '13.4英寸FHD+屏幕，无边框设计，仅1.17kg', 7999.00, 6400.00, '上架'),
('男士商务衬衫', 6, '雅戈尔', 'DP免烫', '白色 40码', 'DP免烫技术，易打理', '100%棉面料，DP免烫工艺，修身版型，适合商务场合', 299.00, 150.00, '上架'),
('男士休闲裤', 6, '优衣库', '455373', '黑色 32码', '弹力舒适，日常百搭', '弹力面料，修身剪裁，多色可选，适合日常穿着', 199.00, 100.00, '上架'),
('女士连衣裙', 7, 'ZARA', '2731/244', '蓝色 M码', '时尚设计，优雅大方', '聚酯纤维面料，印花设计，收腰版型，适合多种场合', 399.00, 200.00, '上架'),
('女士牛仔裤', 7, 'Levi''s', '721', '深蓝色 27码', '经典高腰，修身显瘦', '高腰设计，弹力牛仔布，修身剪裁，经典五袋款', 599.00, 300.00, '上架'),
('三只松鼠坚果礼盒', 8, '三只松鼠', 'NL2023', '1500g 混合装', '精选坚果，营养健康', '包含核桃、腰果、杏仁等8种坚果，独立小包装', 128.00, 80.00, '上架'),
('良品铺子零食大礼包', 8, '良品铺子', 'LP2023', '2000g 组合装', '多种口味，满足味蕾', '包含肉脯、果干、饼干等15种零食，精美包装', 158.00, 100.00, '上架'),
('可口可乐', 9, '可口可乐', 'CC330', '330ml*24罐', '经典口味，清凉解渴', '碳酸饮料，经典配方，330ml罐装，整箱24罐', 59.90, 40.00, '上架'),
('农夫山泉矿泉水', 9, '农夫山泉', 'NS550', '550ml*24瓶', '天然水源，健康饮水', '天然矿泉水，弱碱性，550ml瓶装，整箱24瓶', 36.00, 20.00, '上架'),
('三星Galaxy S24', 4, '三星', 'SM-S9210', '12GB+256GB', 'AI手机，影像旗舰', '6.2英寸Dynamic AMOLED屏幕，骁龙8 Gen3，AI图像处理', 5999.00, 4800.00, '上架'),
('OPPO Find X7', 4, 'OPPO', 'PHZ110', '16GB+512GB', '哈苏影像，超长续航', '6.78英寸2K屏幕，天玑9300芯片，5000mAh电池', 4299.00, 3400.00, '上架'),
('华硕ROG游戏本', 5, '华硕', 'G614JV', 'i9-13980HX RTX4060', '电竞笔记本，性能怪兽', '16英寸2.5K 240Hz屏幕，RTX4060显卡，16GB DDR5', 11999.00, 9600.00, '上架'),
('惠普战66', 5, '惠普', 'G6-14', 'i5-1340P 16GB+512GB', '商务本，稳定可靠', '14英寸FHD屏幕，第13代酷睿，通过军规测试', 5499.00, 4400.00, '上架'),
('男士运动鞋', 6, '耐克', 'DV1236', '黑色 42码', '透气舒适，运动必备', '网眼鞋面，气垫缓震，橡胶大底，适合跑步健身', 699.00, 350.00, '上架'),
('男士夹克', 6, '杰克琼斯', '223108503', '军绿色 L码', '时尚休闲，春秋必备', '棉质面料，拉链开衫，多口袋设计，休闲百搭', 499.00, 250.00, '上架'),
('女士羽绒服', 7, '波司登', 'B30142322', '红色 M码', '轻盈保暖，时尚美观', '90%白鸭绒填充，防风面料，可拆卸帽子', 1299.00, 650.00, '上架'),
('女士高跟鞋', 7, '百丽', '6RD1DCQ1', '黑色 37码', '优雅气质，职场首选', '羊皮材质，细高跟设计，舒适内里，适合职场穿着', 599.00, 300.00, '上架'),
('百草味坚果', 8, '百草味', 'BW2023', '1000g 每日坚果', '科学配比，营养均衡', '7种坚果+3种果干，30小包独立包装，每日一包', 99.00, 60.00, '上架'),
('恰恰瓜子', 8, '恰恰', 'QG500', '500g 原味', '经典零食，休闲必备', '精选葵花籽，传统工艺炒制，香脆可口', 19.90, 12.00, '上架'),
('百事可乐', 9, '百事', 'PC330', '330ml*24罐', '清爽口感，冰镇更佳', '碳酸饮料，独特配方，330ml罐装，整箱24罐', 55.00, 38.00, '上架'),
('康师傅红茶', 9, '康师傅', 'KSF500', '500ml*15瓶', '茶香浓郁，甘甜爽口', '优质茶叶萃取，低糖配方，500ml瓶装，整箱15瓶', 45.00, 30.00, '上架'),
('vivo X100 Pro', 4, 'vivo', 'V2318A', '16GB+512GB', '蔡司影像，天玑9300', '6.78英寸曲面屏，蔡司APO镜头，5400mAh电池', 4999.00, 4000.00, '上架'),
('荣耀Magic6', 4, '荣耀', 'BVL-AN16', '12GB+256GB', '鹰眼相机，超长续航', '6.78英寸LTPO屏幕，骁龙8 Gen3，5600mAh电池', 4699.00, 3800.00, '上架'),
('微软Surface Laptop', 5, '微软', 'RBI-00025', 'i7-1255U 16GB+512GB', '触控屏幕，轻薄便携', '13.5英寸PixelSense触控屏，阿尔坎塔拉键盘', 10999.00, 8800.00, '上架'),
('苹果iPad Air', 5, 'Apple', 'MM9F3CH/A', 'M1 64GB WiFi', '平板电脑，生产力工具', '10.9英寸Liquid视网膜屏，M1芯片，支持Apple Pencil', 4799.00, 3800.00, '上架');

-- 插入库存数据
INSERT INTO inventory (product_id, warehouse_name, stock_quantity, locked_quantity, available_quantity) VALUES
(1, '主仓库', 150, 20, 130),
(2, '主仓库', 200, 30, 170),
(3, '主仓库', 300, 50, 250),
(4, '主仓库', 80, 10, 70),
(5, '主仓库', 120, 15, 105),
(6, '主仓库', 100, 12, 88),
(7, '主仓库', 500, 100, 400),
(8, '主仓库', 600, 80, 520),
(9, '主仓库', 400, 60, 340),
(10, '主仓库', 350, 45, 305),
(11, '主仓库', 800, 150, 650),
(12, '主仓库', 700, 120, 580),
(13, '主仓库', 1000, 200, 800),
(14, '主仓库', 1200, 250, 950),
(15, '主仓库', 180, 25, 155),
(16, '主仓库', 220, 35, 185),
(17, '主仓库', 60, 8, 52),
(18, '主仓库', 90, 12, 78),
(19, '主仓库', 450, 70, 380),
(20, '主仓库', 380, 55, 325),
(21, '主仓库', 280, 40, 240),
(22, '主仓库', 320, 48, 272),
(23, '主仓库', 900, 180, 720),
(24, '主仓库', 1500, 300, 1200),
(25, '主仓库', 1100, 220, 880),
(26, '主仓库', 1300, 260, 1040),
(27, '主仓库', 160, 22, 138),
(28, '主仓库', 190, 28, 162),
(29, '主仓库', 70, 9, 61),
(30, '主仓库', 85, 11, 74);

-- 生成10天的订单数据（从2026-03-27到2026-04-05）
-- 使用存储过程生成随机订单
DELIMITER $$

CREATE PROCEDURE generate_orders()
BEGIN
    DECLARE i INT DEFAULT 0;
    DECLARE order_count INT DEFAULT 0;
    DECLARE v_user_id BIGINT;
    DECLARE v_order_no VARCHAR(50);
    DECLARE v_total_amount DECIMAL(10,2);
    DECLARE v_actual_amount DECIMAL(10,2);
    DECLARE v_order_id BIGINT;
    DECLARE v_days_ago INT;
    DECLARE v_order_date DATETIME;
    DECLARE v_item_count INT;
    DECLARE j INT;
    DECLARE v_product_id BIGINT;
    DECLARE v_quantity INT;
    DECLARE v_price DECIMAL(10,2);
    DECLARE v_subtotal DECIMAL(10,2);
    
    WHILE i < 150 DO
        -- 随机选择用户
        SET v_user_id = FLOOR(1 + RAND() * 20);
        
        -- 随机生成过去10天内的日期
        SET v_days_ago = FLOOR(RAND() * 10);
        SET v_order_date = DATE_SUB(NOW(), INTERVAL v_days_ago DAY);
        SET v_order_date = DATE_ADD(v_order_date, INTERVAL FLOOR(RAND() * 24) HOUR);
        SET v_order_date = DATE_ADD(v_order_date, INTERVAL FLOOR(RAND() * 60) MINUTE);
        
        -- 生成订单号
        SET v_order_no = CONCAT('ORD', DATE_FORMAT(v_order_date, '%Y%m%d%H%i%s'), LPAD(i, 4, '0'));
        
        -- 随机生成1-5个商品
        SET v_item_count = FLOOR(1 + RAND() * 5);
        SET v_total_amount = 0;
        SET j = 0;
        
        -- 先计算总金额
        WHILE j < v_item_count DO
            SET v_product_id = FLOOR(1 + RAND() * 30);
            SET v_quantity = FLOOR(1 + RAND() * 3);
            SELECT price INTO v_price FROM products WHERE id = v_product_id;
            SET v_subtotal = v_price * v_quantity;
            SET v_total_amount = v_total_amount + v_subtotal;
            SET j = j + 1;
        END WHILE;
        
        -- 随机优惠
        SET v_actual_amount = v_total_amount * (0.9 + RAND() * 0.1);
        
        -- 插入订单
        INSERT INTO orders (order_no, user_id, total_amount, discount_amount, actual_amount, status, payment_method, payment_time, shipping_address, receiver_name, receiver_phone, created_at, paid_at)
        VALUES (
            v_order_no,
            v_user_id,
            v_total_amount,
            v_total_amount - v_actual_amount,
            v_actual_amount,
            CASE 
                WHEN RAND() < 0.7 THEN '已完成'
                WHEN RAND() < 0.85 THEN '已发货'
                WHEN RAND() < 0.95 THEN '已支付'
                ELSE '待支付'
            END,
            CASE FLOOR(1 + RAND() * 3)
                WHEN 1 THEN '微信支付'
                WHEN 2 THEN '支付宝'
                ELSE '银行卡'
            END,
            DATE_ADD(v_order_date, INTERVAL FLOOR(RAND() * 60) MINUTE),
            CONCAT('测试地址', i),
            CONCAT('收货人', i),
            CONCAT('138', LPAD(FLOOR(RAND() * 100000000), 8, '0')),
            v_order_date,
            DATE_ADD(v_order_date, INTERVAL FLOOR(RAND() * 60) MINUTE)
        );
        
        SET v_order_id = LAST_INSERT_ID();
        
        -- 插入订单明细
        SET j = 0;
        WHILE j < v_item_count DO
            SET v_product_id = FLOOR(1 + RAND() * 30);
            SET v_quantity = FLOOR(1 + RAND() * 3);
            SELECT price, product_name, specification INTO v_price, @prod_name, @prod_spec 
            FROM products WHERE id = v_product_id;
            SET v_subtotal = v_price * v_quantity;
            
            INSERT INTO order_items (order_id, product_id, product_name, product_spec, quantity, unit_price, subtotal)
            VALUES (v_order_id, v_product_id, @prod_name, @prod_spec, v_quantity, v_price, v_subtotal);
            
            SET j = j + 1;
        END WHILE;
        
        SET i = i + 1;
    END WHILE;
END$$

DELIMITER ;

-- 执行存储过程生成订单
CALL generate_orders();

-- 删除存储过程
DROP PROCEDURE IF EXISTS generate_orders;

-- 生成一些退货记录（约10%的订单有退货）
INSERT INTO returns (return_no, order_id, user_id, reason, status, return_quantity, refund_amount, created_at, processed_at)
SELECT 
    CONCAT('RET', DATE_FORMAT(o.created_at, '%Y%m%d%H%i%s'), LPAD(@rownum := @rownum + 1, 4, '0')),
    o.id,
    o.user_id,
    CASE FLOOR(1 + RAND() * 4)
        WHEN 1 THEN '商品质量问题'
        WHEN 2 THEN '尺寸不合适'
        WHEN 3 THEN '不喜欢/效果不好'
        ELSE '发错货'
    END,
    CASE FLOOR(1 + RAND() * 3)
        WHEN 1 THEN '已完成'
        WHEN 2 THEN '已同意'
        ELSE '审核中'
    END,
    1,
    oi.subtotal,
    DATE_ADD(o.created_at, INTERVAL FLOOR(1 + RAND() * 3) DAY),
    DATE_ADD(o.created_at, INTERVAL FLOOR(4 + RAND() * 3) DAY)
FROM (
    SELECT id, user_id, created_at
    FROM orders
    WHERE RAND() < 0.1
    LIMIT 15
) o
JOIN order_items oi ON o.id = oi.order_id
JOIN (SELECT @rownum := 0) r
LIMIT 15;

-- 验证数据
SELECT '=== Data Statistics ===' AS info;
SELECT COUNT(*) AS user_count FROM users;
SELECT COUNT(*) AS address_count FROM user_addresses;
SELECT COUNT(*) AS product_count FROM products;
SELECT COUNT(*) AS inventory_count FROM inventory;
SELECT COUNT(*) AS order_count FROM orders;
SELECT COUNT(*) AS order_item_count FROM order_items;
SELECT COUNT(*) AS return_count FROM returns;
SELECT DATE(MIN(created_at)) AS earliest_order, DATE(MAX(created_at)) AS latest_order FROM orders;
SELECT SUM(actual_amount) AS total_gmv FROM orders WHERE status != '已取消';
