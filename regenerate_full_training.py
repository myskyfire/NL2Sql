# -*- coding: utf-8 -*-
"""
重新生成完整的training_test_cases.txt (L1-L9)
基于trade数据库实际表结构
"""

def generate_l1():
    """L1: 简单单表查询 (50条)"""
    cases = []
    
    # 用户ID查询
    for uid in range(1, 21):
        cases.append(f"查询用户ID为{uid}的订单|SELECT * FROM orders WHERE user_id = {uid}|orders|WHERE")
    
    # 订单号查询
    cases.append("查询订单号ORD20260401090001的详情|SELECT * FROM orders WHERE order_no = 'ORD20260401090001'|orders|WHERE")
    
    # 基础统计
    cases.extend([
        "订单总数|SELECT COUNT(*) FROM orders|orders|COUNT",
        "商品总数|SELECT COUNT(*) FROM products|products|COUNT",
        "用户总数|SELECT COUNT(*) FROM users|users|COUNT",
        "已完成订单数量|SELECT COUNT(*) FROM orders WHERE status = 'completed'|orders|COUNT,WHERE",
        "待支付订单数量|SELECT COUNT(*) FROM orders WHERE status = 'pending'|orders|COUNT,WHERE",
        "所有订单总金额|SELECT SUM(total_amount) FROM orders|orders|SUM",
        "平均订单金额|SELECT AVG(total_amount) FROM orders|orders|AVG",
        "最高订单金额|SELECT MAX(total_amount) FROM orders|orders|MAX",
        "最低订单金额|SELECT MIN(total_amount) FROM orders|orders|MIN",
    ])
    
    # 商品查询
    products = [
        ("MacBook Pro 14", "price"),
        ("索尼65寸电视", "*"),
        ("海尔冰箱", "price"),
        ("Nike运动鞋", "price"),
        ("Adidas外套", "*"),
        ("优衣库衬衫", "price"),
    ]
    for pname, col in products:
        if col == "*":
            cases.append(f"{pname}的信息|SELECT * FROM products WHERE product_name = '{pname}'|products|WHERE")
        else:
            cases.append(f"{pname}的价格|SELECT {col} FROM products WHERE product_name = '{pname}'|products|WHERE")
    
    # 排序查询
    cases.extend([
        "最贵的商品|SELECT * FROM products ORDER BY price DESC LIMIT 1|products|ORDER BY,LIMIT",
        "最便宜的商品|SELECT * FROM products ORDER BY price ASC LIMIT 1|products|ORDER BY,LIMIT",
    ])
    
    # 状态查询
    cases.extend([
        "查询已支付订单|SELECT * FROM orders WHERE status = 'paid'|orders|WHERE",
        "查询已发货订单|SELECT * FROM orders WHERE status = 'shipped'|orders|WHERE",
        "查询已完成订单|SELECT * FROM orders WHERE status = 'completed'|orders|WHERE",
    ])
    
    # 支付方式查询
    cases.extend([
        "微信支付订单列表|SELECT * FROM orders WHERE payment_method = 'wechat'|orders|WHERE",
        "支付宝订单列表|SELECT * FROM orders WHERE payment_method = 'alipay'|orders|WHERE",
        "银行卡支付订单|SELECT * FROM orders WHERE payment_method = 'credit_card'|orders|WHERE",
    ])
    
    # 库存查询
    cases.extend([
        "库存大于100的商品|SELECT p.product_name, i.stock_quantity FROM products p JOIN inventory i ON p.id = i.product_id WHERE i.stock_quantity > 100|products,inventory|JOIN,WHERE",
        "库存小于50的商品|SELECT p.product_name, i.stock_quantity FROM products p JOIN inventory i ON p.id = i.product_id WHERE i.stock_quantity < 50|products,inventory|JOIN,WHERE",
    ])
    
    # 退货查询
    cases.extend([
        "有退货的订单|SELECT DISTINCT o.order_no FROM orders o JOIN returns r ON o.id = r.order_id|orders,returns|JOIN,DISTINCT",
        "退货原因统计|SELECT reason, COUNT(*) FROM returns GROUP BY reason|returns|COUNT,GROUP BY",
    ])
    
    return cases

def generate_l2():
    """L2: 聚合与分组 (40条)"""
    cases = []
    
    # 用户订单数
    cases.append("每个用户的订单数量|SELECT user_id, COUNT(*) FROM orders GROUP BY user_id|orders|COUNT,GROUP BY")
    
    # 状态统计
    cases.append("每个状态订单数量|SELECT status, COUNT(*) FROM orders GROUP BY status|orders|COUNT,GROUP BY")
    
    # 分类统计
    cases.append("每个商品分类的商品数量|SELECT category_id, COUNT(*) FROM products GROUP BY category_id|products|COUNT,GROUP BY")
    
    # 城市用户数
    cities = ["深圳市", "广州市", "杭州市", "南京市", "成都市", "武汉市", "青岛市", "厦门市", "郑州市", "长沙市"]
    for city in cities:
        cases.append(f"{city}注册用户数量|SELECT COUNT(DISTINCT ua.user_id) FROM user_addresses ua WHERE ua.city = '{city}' AND ua.is_default = 1|user_addresses|COUNT,WHERE,DISTINCT")
    
    # 省份用户数
    cases.append("各省份注册用户数|SELECT ua.province, COUNT(DISTINCT ua.user_id) FROM user_addresses ua WHERE ua.is_default = 1 GROUP BY ua.province|user_addresses|COUNT,GROUP BY,DISTINCT,WHERE")
    
    # 分类均价
    categories = [
        ("手机数码", 1),
        ("电脑办公", 2),
        ("家用电器", 3),
        ("服装服饰", 4),
    ]
    for cname, cid in categories:
        cases.append(f"{cname}类商品均价|SELECT AVG(price) FROM products WHERE category_id = {cid}|products|AVG,WHERE")
    
    # 金额范围
    cases.extend([
        "订单金额大于5000的数量|SELECT COUNT(*) FROM orders WHERE total_amount > 5000|orders|COUNT,WHERE",
        "订单金额大于10000的数量|SELECT COUNT(*) FROM orders WHERE total_amount > 10000|orders|COUNT,WHERE",
        "订单金额在1000到5000之间的数量|SELECT COUNT(*) FROM orders WHERE total_amount BETWEEN 1000 AND 5000|orders|COUNT,WHERE,BETWEEN",
    ])
    
    # 价格范围
    cases.extend([
        "价格高于5000的商品数量|SELECT COUNT(*) FROM products WHERE price > 5000|products|COUNT,WHERE",
        "价格低于1000的商品数量|SELECT COUNT(*) FROM products WHERE price < 1000|products|COUNT,WHERE",
    ])
    
    # 分类聚合
    cases.extend([
        "每个分类的平均价格|SELECT category_id, AVG(price) FROM products GROUP BY category_id|products|AVG,GROUP BY",
        "每个分类的最高价格|SELECT category_id, MAX(price) FROM products GROUP BY category_id|products|MAX,GROUP BY",
        "每个分类的最低价格|SELECT category_id, MIN(price) FROM products GROUP BY category_id|products|MIN,GROUP BY",
    ])
    
    # 用户消费总额
    for uid in range(1, 6):
        cases.append(f"查询用户ID为{uid}的订单总金额|SELECT SUM(total_amount) FROM orders WHERE user_id = {uid}|orders|SUM,WHERE")
    
    # TOP-N
    cases.extend([
        "订单金额超过10000的订单号|SELECT order_no FROM orders WHERE total_amount > 10000|orders|WHERE",
        "订单金额最高的前5个订单|SELECT order_no, total_amount FROM orders ORDER BY total_amount DESC LIMIT 5|orders|ORDER BY,LIMIT",
        "订单金额最低的前5个订单|SELECT order_no, total_amount FROM orders ORDER BY total_amount ASC LIMIT 5|orders|ORDER BY,LIMIT",
        "商品价格高于5000的商品名称|SELECT product_name FROM products WHERE price > 5000|products|WHERE",
        "价格最高的3个商品|SELECT product_name, price FROM products ORDER BY price DESC LIMIT 3|products|ORDER BY,LIMIT",
        "价格最低的3个商品|SELECT product_name, price FROM products ORDER BY price ASC LIMIT 3|products|ORDER BY,LIMIT",
    ])
    
    # 状态均价
    cases.append("不同状态的订单平均金额|SELECT status, AVG(total_amount) FROM orders GROUP BY status|orders|AVG,GROUP BY")
    
    # 分类商品数
    cases.append("不同分类商品数量统计|SELECT c.category_name, COUNT(p.id) FROM product_categories c LEFT JOIN products p ON c.id = p.category_id GROUP BY c.category_name|product_categories,products|COUNT,GROUP BY,LEFT JOIN")
    
    # 省份订单数
    provinces = ["广东省", "浙江省", "江苏省", "四川省", "湖北省", "山东省", "福建省", "河南省", "湖南省"]
    for prov in provinces:
        cases.append(f"发往{prov}的订单总数|SELECT COUNT(*) FROM orders o JOIN users u ON o.user_id = u.id WHERE u.province = '{prov}'|orders,users|COUNT,JOIN,WHERE")
    
    # 性别统计
    cases.extend([
        "男性用户数量|SELECT COUNT(*) FROM users WHERE gender = '男'|users|COUNT,WHERE",
        "女性用户数量|SELECT COUNT(*) FROM users WHERE gender = '女'|users|COUNT,WHERE",
    ])
    
    # 城市用户数
    for city in ["北京市", "深圳市", "上海市"]:
        cases.append(f"{city}用户数量|SELECT COUNT(*) FROM users WHERE city = '{city}'|users|COUNT,WHERE")
    
    # 库存统计
    cases.extend([
        "库存总量|SELECT SUM(stock_quantity) FROM inventory|inventory|SUM",
        "可用库存总量|SELECT SUM(available_quantity) FROM inventory|inventory|SUM",
        "锁定库存总量|SELECT SUM(locked_quantity) FROM inventory|inventory|SUM",
    ])
    
    # 退货统计
    cases.extend([
        "退货总数|SELECT COUNT(*) FROM returns|returns|COUNT",
        "已完成的退货数|SELECT COUNT(*) FROM returns WHERE status = '已完成'|returns|COUNT,WHERE",
        "审核中的退货数|SELECT COUNT(*) FROM returns WHERE status = '审核中'|returns|COUNT,WHERE",
        "总退款金额|SELECT SUM(refund_amount) FROM returns|returns|SUM",
    ])
    
    return cases

def generate_l3():
    """L3: 双表关联 (40条)"""
    cases = []
    
    # 用户订单
    users = ["张三", "李四", "王五"]
    for uname in users:
        cases.append(f"{uname}的订单及金额|SELECT o.order_no, o.total_amount FROM orders o JOIN users u ON o.user_id = u.id WHERE u.real_name = '{uname}'|orders,users|JOIN,WHERE")
    
    # 分类商品
    categories = ["手机数码", "电脑办公", "家用电器", "服装服饰", "食品饮料", "图书音像", "运动户外", "美妆个护", "家居家装"]
    for cat in categories:
        cases.append(f"{cat}类商品列表|SELECT p.product_name, p.price FROM products p JOIN product_categories c ON p.category_id = c.id WHERE c.category_name = '{cat}'|products,product_categories|JOIN,WHERE")
    
    # 订单用户
    order_nos = ["ORD20260401090001", "ORD20260401093002", "ORD20260401100003", "ORD20260401103004", "ORD20260401110005"]
    for ono in order_nos:
        cases.append(f"订单{ono}的用户|SELECT u.real_name FROM orders o JOIN users u ON o.user_id = u.id WHERE o.order_no = '{ono}'|orders,users|JOIN,WHERE")
    
    # 用户地址
    users_addr = ["张三", "李四", "王五", "赵六", "孙七"]
    for uname in users_addr[:2]:
        cases.append(f"{uname}的默认收货地址|SELECT ua.province, ua.city, ua.detail_address FROM users u JOIN user_addresses ua ON u.id = ua.user_id WHERE u.real_name = '{uname}' AND ua.is_default = 1|users,user_addresses|JOIN,WHERE")
    for uname in users_addr[2:]:
        cases.append(f"{uname}的所有收货地址|SELECT ua.province, ua.city, ua.detail_address FROM users u JOIN user_addresses ua ON u.id = ua.user_id WHERE u.real_name = '{uname}'|users,user_addresses|JOIN,WHERE")
    
    # 商品分类
    products_cat = [
        "MacBook Pro 14", "ThinkPad X1 Carbon", "戴尔XPS 15",
        "华为Mate 60 Pro", "小米14", "iPhone 15 Pro",
        "OPPO Find X7", "vivo X100 Pro", "三星Galaxy S24", "荣耀Magic6",
    ]
    for pname in products_cat:
        cases.append(f"{pname}所属分类|SELECT c.category_name FROM products p JOIN product_categories c ON p.category_id = c.id WHERE p.product_name = '{pname}'|products,product_categories|JOIN,WHERE")
    
    # 城市订单
    cities = ["北京市", "深圳市", "上海市"]
    for city in cities:
        cases.append(f"{city}用户下的订单|SELECT o.* FROM orders o JOIN users u ON o.user_id = u.id WHERE u.city = '{city}'|orders,users|JOIN,WHERE")
    
    # 省份订单
    provinces = ["广东省", "浙江省", "江苏省", "四川省", "湖北省", "山东省", "福建省", "河南省", "湖南省"]
    for prov in provinces:
        cases.append(f"{prov}用户下的订单|SELECT o.* FROM orders o JOIN users u ON o.user_id = u.id WHERE u.province = '{prov}'|orders,users|JOIN,WHERE")
    
    # 更多用户订单
    more_users = ["周八", "吴九", "郑十", "陈一", "林二", "黄三", "何四", "高五", "罗六", "梁七", "宋八", "唐九", "冯十", "曹一", "薛二"]
    for uname in more_users:
        cases.append(f"{uname}的订单|SELECT o.* FROM orders o JOIN users u ON o.user_id = u.id WHERE u.real_name = '{uname}'|orders,users|JOIN,WHERE")
    
    return cases

def generate_l4():
    """L4: 多表复杂查询 (40条)"""
    cases = []
    
    # 用户购买商品
    users = ["张三", "李四", "王五", "周八", "吴九", "郑十", "陈一", "林二", "黄三", "何四", "高五", "罗六", "梁七", "宋八", "唐九", "冯十", "曹一", "薛二"]
    for uname in users:
        cases.append(f"{uname}购买的商品列表|SELECT DISTINCT p.product_name FROM orders o JOIN order_items oi ON o.id = oi.order_id JOIN products p ON oi.product_id = p.id JOIN users u ON o.user_id = u.id WHERE u.real_name = '{uname}'|orders,order_items,products,users|JOIN,DISTINCT,WHERE")
    
    # 分类销售额
    categories = ["手机数码", "电脑办公", "家用电器", "服装服饰"]
    for cat in categories:
        cases.append(f"{cat}类商品销售总额|SELECT SUM(oi.subtotal) FROM order_items oi JOIN products p ON oi.product_id = p.id JOIN product_categories c ON p.category_id = c.id WHERE c.category_name = '{cat}'|order_items,products,product_categories|SUM,JOIN,WHERE")
    
    # 用户订单次数
    users_count = ["张三", "李四", "王五", "赵六", "孙七"]
    for uname in users_count:
        cases.append(f"{uname}买过几次订单|SELECT COUNT(*) FROM orders o JOIN users u ON o.user_id = u.id WHERE u.real_name = '{uname}'|orders,users|COUNT,JOIN,WHERE")
    
    # 城市订单数
    cities = ["北京市", "深圳市", "上海市"]
    for city in cities:
        cases.append(f"{city}用户订单数量|SELECT COUNT(*) FROM orders o JOIN users u ON o.user_id = u.id WHERE u.city = '{city}'|orders,users|COUNT,JOIN,WHERE")
    
    # 省份订单数
    provinces = ["广东省", "浙江省", "江苏省", "四川省", "湖北省", "山东省", "福建省", "河南省", "湖南省"]
    for prov in provinces:
        cases.append(f"{prov}用户订单数量|SELECT COUNT(*) FROM orders o JOIN users u ON o.user_id = u.id WHERE u.province = '{prov}'|orders,users|COUNT,JOIN,WHERE")
    
    # 城市订单总额
    for city in cities:
        cases.append(f"{city}用户购买的订单总额|SELECT SUM(o.total_amount) FROM orders o JOIN users u ON o.user_id = u.id WHERE u.city = '{city}'|orders,users|SUM,JOIN,WHERE")
    
    # 省份订单总额
    for prov in provinces:
        cases.append(f"{prov}用户购买的订单总额|SELECT SUM(o.total_amount) FROM orders o JOIN users u ON o.user_id = u.id WHERE u.province = '{prov}'|orders,users|SUM,JOIN,WHERE")
    
    return cases

def generate_l5():
    """L5: 高级函数与复杂场景 (30条)"""
    cases = []
    
    # TOP-N用户
    cases.append("订单金额排名前10的用户|SELECT u.real_name, SUM(o.total_amount) as total FROM users u JOIN orders o ON u.id = o.user_id GROUP BY u.id, u.real_name ORDER BY total DESC LIMIT 10|users,orders|SUM,GROUP BY,ORDER BY,LIMIT,JOIN")
    cases.append("消费最少的后5个用户|SELECT u.real_name, SUM(o.total_amount) as total_spent FROM users u JOIN orders o ON u.id = o.user_id GROUP BY u.id, u.real_name ORDER BY total_spent ASC LIMIT 5|users,orders|SUM,GROUP BY,ORDER BY,LIMIT,JOIN")
    
    # 城市/省份均价
    cases.append("不同城市的平均订单金额|SELECT u.city, AVG(o.total_amount) as avg_amount FROM users u JOIN orders o ON u.id = o.user_id GROUP BY u.city|users,orders|AVG,GROUP BY,JOIN")
    cases.append("不同省份的平均订单金额|SELECT u.province, AVG(o.total_amount) as avg_amount FROM users u JOIN orders o ON u.id = o.user_id GROUP BY u.province|users,orders|AVG,GROUP BY,JOIN")
    
    # 分类销售
    cases.append("每个分类销售额|SELECT c.category_name, SUM(oi.subtotal) as revenue FROM product_categories c JOIN products p ON c.id = p.category_id JOIN order_items oi ON p.id = oi.product_id GROUP BY c.category_name|product_categories,products,order_items|SUM,GROUP BY,JOIN")
    cases.append("每个分类销售数量|SELECT c.category_name, SUM(oi.quantity) as qty FROM product_categories c JOIN products p ON c.id = p.category_id JOIN order_items oi ON p.id = oi.product_id GROUP BY c.category_name|product_categories,products,order_items|SUM,GROUP BY,JOIN")
    cases.append("销售额最高的分类|SELECT c.category_name, SUM(oi.subtotal) as revenue FROM product_categories c JOIN products p ON c.id = p.category_id JOIN order_items oi ON p.id = oi.product_id GROUP BY c.category_name ORDER BY revenue DESC LIMIT 1|product_categories,products,order_items|SUM,GROUP BY,ORDER BY,LIMIT,JOIN")
    cases.append("销售额最低的分类|SELECT c.category_name, SUM(oi.subtotal) as revenue FROM product_categories c JOIN products p ON c.id = p.category_id JOIN order_items oi ON p.id = oi.product_id GROUP BY c.category_name ORDER BY revenue ASC LIMIT 1|product_categories,products,order_items|SUM,GROUP BY,ORDER BY,LIMIT,JOIN")
    
    # 状态聚合
    cases.extend([
        "不同状态订单总额|SELECT status, SUM(total_amount) FROM orders GROUP BY status|orders|SUM,GROUP BY",
        "不同状态订单平均金额|SELECT status, AVG(total_amount) FROM orders GROUP BY status|orders|AVG,GROUP BY",
        "不同状态订单最大金额|SELECT status, MAX(total_amount) FROM orders GROUP BY status|orders|MAX,GROUP BY",
        "不同状态订单最小金额|SELECT status, MIN(total_amount) FROM orders GROUP BY status|orders|MIN,GROUP BY",
    ])
    
    # HAVING过滤
    cases.append("订单数大于2的用户及其订单总额|SELECT u.real_name, COUNT(o.id) as cnt, SUM(o.total_amount) as total FROM users u JOIN orders o ON u.id = o.user_id GROUP BY u.id, u.real_name HAVING cnt > 2|users,orders|COUNT,SUM,GROUP BY,HAVING,JOIN")
    cases.append("订单数大于5的用户及其平均订单金额|SELECT u.real_name, COUNT(o.id) as cnt, AVG(o.total_amount) as avg_amt FROM users u JOIN orders o ON u.id = o.user_id GROUP BY u.id, u.real_name HAVING cnt > 5|users,orders|COUNT,AVG,GROUP BY,HAVING,JOIN")
    cases.append("消费超过10000的用户|SELECT u.real_name, SUM(o.total_amount) as total FROM users u JOIN orders o ON u.id = o.user_id GROUP BY u.id, u.real_name HAVING total > 10000|users,orders|SUM,GROUP BY,HAVING,JOIN")
    cases.append("消费超过20000的用户|SELECT u.real_name, SUM(o.total_amount) as total FROM users u JOIN orders o ON u.id = o.user_id GROUP BY u.id, u.real_name HAVING total > 20000|users,orders|SUM,GROUP BY,HAVING,JOIN")
    cases.append("消费超过50000的用户|SELECT u.real_name, SUM(o.total_amount) as total FROM users u JOIN orders o ON u.id = o.user_id GROUP BY u.id, u.real_name HAVING total > 50000|users,orders|SUM,GROUP BY,HAVING,JOIN")
    
    # LEFT JOIN
    cases.append("每个用户的订单数和总金额|SELECT u.real_name, COUNT(o.id) as order_count, SUM(o.total_amount) as total_amount FROM users u LEFT JOIN orders o ON u.id = o.user_id GROUP BY u.id, u.real_name|users,orders|COUNT,SUM,GROUP BY,LEFT JOIN")
    
    # 城市/省份均价（具体）
    cities_prov = [
        ("北京市", "city"), ("深圳市", "city"), ("上海市", "city"),
        ("广东省", "province"), ("浙江省", "province"), ("江苏省", "province"),
        ("四川省", "province"), ("湖北省", "province"), ("山东省", "province"),
        ("福建省", "province"), ("河南省", "province"), ("湖南省", "province"),
    ]
    for name, col_type in cities_prov:
        col = "city" if col_type == "city" else "province"
        cases.append(f"{name}平均订单金额|SELECT AVG(o.total_amount) FROM orders o JOIN users u ON o.user_id = u.id WHERE u.{col} = '{name}'|orders,users|AVG,JOIN,WHERE")
    
    # 用户消费总额
    more_users = ["周八", "吴九", "郑十", "陈一", "林二", "黄三", "何四", "高五", "罗六", "梁七", "宋八", "唐九", "冯十", "曹一", "薛二"]
    for uname in more_users:
        cases.append(f"{uname}的订单总金额|SELECT SUM(o.total_amount) FROM orders o JOIN users u ON o.user_id = u.id WHERE u.real_name = '{uname}'|orders,users|SUM,JOIN,WHERE")
        cases.append(f"{uname}买过几次订单|SELECT COUNT(*) FROM orders o JOIN users u ON o.user_id = u.id WHERE u.real_name = '{uname}'|orders,users|COUNT,JOIN,WHERE")
    
    return cases

# 生成所有用例
all_cases = []
all_cases.extend(generate_l1())
all_cases.extend(generate_l2())
all_cases.extend(generate_l3())
all_cases.extend(generate_l4())
all_cases.extend(generate_l5())

print(f"Generated L1-L5: {len(all_cases)} cases")

# 读取现有的L6-L9
with open('training_test_cases.txt', 'r', encoding='utf-8') as f:
    lines = f.readlines()
    l6_start = None
    for i, line in enumerate(lines):
        if 'L6' in line and '时间查询' in line:
            l6_start = i
            break
    
    if l6_start:
        l6_to_end = ''.join(lines[l6_start:])
        print(f"Found L6-L9 starting at line {l6_start + 1}")
    else:
        l6_to_end = ""
        print("Warning: L6-L9 not found!")

# 写入完整文件
with open('training_test_cases_complete.txt', 'w', encoding='utf-8', newline='') as f:
    # Header
    f.write("# NL2SQL Training Test Cases\n")
    f.write("# Format: question|expected_sql|tables|functions\n")
    f.write("# Address Mapping Rules:\n")
    f.write("# 1. User registration location -> users.city/province\n")
    f.write("# 2. Order delivery location -> orders.shipping_address (LIKE match)\n")
    f.write("# 3. User address book -> user_addresses table (add is_default=1 for default address)\n\n")
    
    # L1-L5
    for case in all_cases:
        f.write(case + '\n')
    
    # L6-L9
    if l6_to_end:
        f.write('\n' + l6_to_end)

# 验证
with open('training_test_cases_complete.txt', 'r', encoding='utf-8') as f:
    valid_lines = [l.strip() for l in f.readlines() if l.strip() and not l.startswith('#')]
    parsed = [l for l in valid_lines if len(l.split('|')) >= 4]
    print(f"\n✅ Total valid lines: {len(valid_lines)}")
    print(f"✅ Successfully parsed: {len(parsed)}")
    print(f"✅ Parse rate: {len(parsed)/len(valid_lines)*100:.1f}%")
