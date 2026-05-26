# -*- coding: utf-8 -*-
"""
查询trade库订单最新日期并生成补数SQL
补充5月18日至5月25日的订单数据
"""
import pymysql
from datetime import datetime, timedelta

# 数据库配置
DB_CONFIG = {
    'host': 'localhost',
    'port': 33061,
    'user': 'root',
    'password': 'root',
    'database': 'trade',
    'charset': 'utf8mb4'
}

def get_latest_order_date():
    """查询最新的订单日期"""
    conn = pymysql.connect(**DB_CONFIG)
    try:
        with conn.cursor() as cursor:
            cursor.execute("SELECT MAX(DATE(created_at)) as latest_date FROM orders")
            result = cursor.fetchone()
            return result['latest_date'] if result else None
    finally:
        conn.close()

def get_users():
    """获取所有用户"""
    conn = pymysql.connect(**DB_CONFIG)
    try:
        with conn.cursor(pymysql.cursors.DictCursor) as cursor:
            cursor.execute("SELECT id, real_name, province, city FROM users ORDER BY id")
            return cursor.fetchall()
    finally:
        conn.close()

def get_products():
    """获取所有商品"""
    conn = pymysql.connect(**DB_CONFIG)
    try:
        with conn.cursor(pymysql.cursors.DictCursor) as cursor:
            cursor.execute("SELECT id, product_name, price FROM products WHERE status = '上架' ORDER BY id")
            return cursor.fetchall()
    finally:
        conn.close()

def get_max_order_id():
    """获取当前最大订单ID"""
    conn = pymysql.connect(**DB_CONFIG)
    try:
        with conn.cursor() as cursor:
            cursor.execute("SELECT MAX(id) as max_id FROM orders")
            result = cursor.fetchone()
            return result['max_id'] if result else 0
    finally:
        conn.close()

def generate_orders_sql():
    """生成补数SQL"""
    # 获取基础数据
    latest_date = get_latest_order_date()
    print(f"最新订单日期: {latest_date}")
    
    if not latest_date:
        print("错误: 未找到订单数据")
        return
    
    users = get_users()
    products = get_products()
    start_id = get_max_order_id() + 1
    
    print(f"用户数量: {len(users)}")
    print(f"商品数量: {len(products)}")
    print(f"起始订单ID: {start_id}")
    
    # 确定补数日期范围
    start_date = latest_date + timedelta(days=1)
    end_date = datetime.now().date()
    
    print(f"补数日期范围: {start_date} 至 {end_date}")
    
    # 生成SQL文件
    sql_lines = []
    sql_lines.append("-- ========================================")
    sql_lines.append(f"-- 补充订单数据({start_date}至{end_date})")
    sql_lines.append("-- 确保 orders 和 order_items 表的数据完整性")
    sql_lines.append("-- 每个订单至少有1条订单项")
    sql_lines.append("-- ========================================")
    sql_lines.append("")
    sql_lines.append("USE trade;")
    sql_lines.append("")
    
    current_id = start_id
    payment_methods = ['alipay', 'wechat', 'credit_card']
    statuses = ['completed']  # 新订单都设为已完成
    
    all_order_items = []
    
    for single_date in (start_date + timedelta(n) for n in range((end_date - start_date).days + 1)):
        # 每天生成6-8条订单
        import random
        daily_count = random.randint(6, 8)
        
        sql_lines.append(f"-- {single_date}订单({daily_count}条)")
        order_values = []
        
        for i in range(daily_count):
            user = random.choice(users)
            product = random.choice(products)
            
            # 生成订单号
            hour = random.randint(8, 18)
            minute = random.randint(0, 59)
            second = random.randint(0, 59)
            order_no = f"ORD{single_date.strftime('%Y%m%d')}{hour:02d}{minute:02d}{second:02d}{current_id}"
            
            # 计算金额(可能有折扣)
            total_amount = float(product['price'])
            discount_rate = random.choice([1.0, 1.0, 1.0, 0.9, 0.95])  # 70%无折扣
            actual_amount = round(total_amount * discount_rate, 2)
            
            payment_method = random.choice(payment_methods)
            status = random.choice(statuses)
            
            # 生成地址
            address = f"{user['province']}{user['city']}XX路{random.randint(100, 999)}号"
            
            created_at = f"{single_date} {hour:02d}:{minute:02d}:{second:02d}"
            
            order_values.append(
                f"('{order_no}', {user['id']}, '{address}', {total_amount:.2f}, {actual_amount:.2f}, "
                f"'{status}', '{payment_method}', '{created_at}')"
            )
            
            # 记录订单项
            quantity = random.choice([1, 1, 1, 2])  # 大部分买1件
            subtotal = round(float(product['price']) * quantity, 2)
            all_order_items.append((current_id, product['id'], quantity, float(product['price']), subtotal))
            
            current_id += 1
        
        # 插入订单
        sql_lines.append("INSERT INTO orders (order_no, user_id, shipping_address, total_amount, actual_amount, status, payment_method, created_at) VALUES")
        sql_lines.append(",\n".join(order_values) + ";")
        sql_lines.append("")
    
    # 生成订单项
    sql_lines.append("-- ========================================")
    sql_lines.append("-- 插入订单项数据(order_items)")
    sql_lines.append("-- 每个订单对应1个商品项")
    sql_lines.append("-- ========================================")
    sql_lines.append("")
    
    item_values = []
    for order_id, product_id, quantity, unit_price, subtotal in all_order_items:
        # 获取商品名称
        product_name = next((p['product_name'] for p in products if p['id'] == product_id), '')
        item_values.append(
            f"({order_id}, {product_id}, '{product_name}', {quantity}, {unit_price:.2f}, {subtotal:.2f})"
        )
    
    sql_lines.append("INSERT INTO order_items (order_id, product_id, product_name, quantity, unit_price, subtotal) VALUES")
    sql_lines.append(",\n".join(item_values) + ";")
    sql_lines.append("")
    
    # 验证SQL
    sql_lines.append("-- ========================================")
    sql_lines.append("-- 验证数据")
    sql_lines.append("-- ========================================")
    sql_lines.append("SELECT DATE(created_at) as order_date, COUNT(*) as order_count, SUM(actual_amount) as total_amount")
    sql_lines.append("FROM orders")
    sql_lines.append(f"WHERE created_at >= '{start_date}'")
    sql_lines.append("GROUP BY DATE(created_at)")
    sql_lines.append("ORDER BY order_date;")
    sql_lines.append("")
    sql_lines.append(f"SELECT COUNT(*) as total_orders FROM orders WHERE created_at >= '{start_date}';")
    sql_lines.append(f"SELECT COUNT(*) as total_items FROM order_items WHERE order_id >= {start_id};")
    
    # 写入文件
    filename = f"generate_orders_{start_date}_to_{end_date}.sql"
    with open(filename, 'w', encoding='utf-8') as f:
        f.write('\n'.join(sql_lines))
    
    print(f"\nSQL文件已生成: {filename}")
    print(f"总订单数: {len(all_order_items)}")
    print(f"总订单项数: {len(all_order_items)}")

if __name__ == '__main__':
    try:
        generate_orders_sql()
    except Exception as e:
        print(f"错误: {e}")
        import traceback
        traceback.print_exc()
