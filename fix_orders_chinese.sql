-- 修复orders表中文乱码数据
USE trade;

-- 更新status字段
UPDATE orders SET status = CASE id % 5
    WHEN 0 THEN 'pending'
    WHEN 1 THEN 'paid'
    WHEN 2 THEN 'shipped'
    WHEN 3 THEN 'completed'
    WHEN 4 THEN 'cancelled'
END;

-- 更新payment_method字段
UPDATE orders SET payment_method = CASE id % 4
    WHEN 0 THEN 'alipay'
    WHEN 1 THEN 'wechat'
    WHEN 2 THEN 'card'
    WHEN 3 THEN 'cod'
END;

-- 更新shipping_address字段（生成模拟地址）
UPDATE orders SET 
    shipping_address = CONCAT('北京市', 
        ELT(1 + (id % 6), '朝阳区', '海淀区', '西城区', '东城区', '丰台区', '昌平区'),
        '路', LPAD(id, 3, '0'), '号');

-- 更新receiver_name字段（生成模拟姓名）
UPDATE orders SET 
    receiver_name = CONCAT(
        ELT(1 + (id % 10), '张', '李', '王', '刘', '陈', '杨', '赵', '黄', '周', '吴'),
        ELT(1 + ((id * 7) % 10), '伟', '芳', '娜', '敏', '静', '丽', '强', '磊', '洋', '勇')
    );

-- 验证修复结果
SELECT id, status, payment_method, shipping_address, receiver_name 
FROM orders 
LIMIT 10;
