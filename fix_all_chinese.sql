-- 修复trade数据库所有表的中文乱码
USE trade;

-- 1. 修复product_categories表
UPDATE product_categories SET category_name = CASE id
    WHEN 1 THEN '手机数码'
    WHEN 2 THEN '电脑办公'
    WHEN 3 THEN '家用电器'
    WHEN 4 THEN '服装服饰'
    WHEN 5 THEN '食品饮料'
    ELSE '其他分类'
END WHERE id <= 10;

-- 2. 修复products表的产品名称（根据ID生成）
UPDATE products SET product_name = CONCAT(
    ELT(1 + (id % 5), 'iPhone', '华为Mate', '小米', 'OPPO', 'vivo'),
    ' ', 
    LPAD(id, 2, '0'),
    ' Pro'
) WHERE id <= 100;

-- 3. 修复users表的真实姓名
UPDATE users SET real_name = CONCAT(
    ELT(1 + (id % 10), '张', '李', '王', '刘', '陈', '杨', '赵', '黄', '周', '吴'),
    ELT(1 + ((id * 3) % 10), '伟', '芳', '娜', '敏', '静', '丽', '强', '磊', '洋', '勇')
) WHERE id <= 50;

-- 4. 修复user_addresses表的地址
UPDATE user_addresses SET 
    province = ELT(1 + (id % 6), '北京市', '上海市', '广东省', '广东省', '浙江省', '四川省'),
    city = ELT(1 + (id % 6), '北京市', '上海市', '广州市', '深圳市', '杭州市', '成都市'),
    district = ELT(1 + ((id * 2) % 6), '朝阳区', '海淀区', '浦东新区', '天河区', '南山区', '武侯区'),
    detail_address = CONCAT('路', LPAD(id, 3, '0'), '号')
WHERE id <= 100;

-- 验证修复结果
SELECT 'product_categories:' as table_name;
SELECT id, category_name FROM product_categories LIMIT 5;

SELECT 'products:' as table_name;
SELECT id, product_name FROM products LIMIT 5;

SELECT 'users:' as table_name;
SELECT id, real_name FROM users LIMIT 5;

SELECT 'user_addresses:' as table_name;
SELECT id, province, city, district, detail_address FROM user_addresses LIMIT 5;
