import subprocess

# 生成中文地址和姓名
districts = ['朝阳区', '海淀区', '西城区', '东城区', '丰台区', '昌平区']
surnames = ['张', '李', '王', '刘', '陈', '杨', '赵', '黄', '周', '吴']
names = ['伟', '芳', '娜', '敏', '静', '丽', '强', '磊', '洋', '勇']
statuses = ['pending', 'paid', 'shipped', 'completed', 'cancelled']
payments = ['alipay', 'wechat', 'card', 'cod']

sql_statements = []

for id in range(1, 151):
    district = districts[id % len(districts)]
    surname = surnames[id % len(surnames)]
    name = names[(id * 7) % len(names)]
    status = statuses[id % len(statuses)]
    payment = payments[id % len(payments)]
    
    address = f"北京市{district}路{id:03d}号"
    receiver = surname + name
    
    sql = f"UPDATE orders SET status='{status}', payment_method='{payment}', shipping_address='{address}', receiver_name='{receiver}' WHERE id={id};"
    sql_statements.append(sql)

# 写入SQL文件
with open('E:\\work\\idea workspace\\NLP2Sql\\update_orders.sql', 'w', encoding='utf-8') as f:
    f.write("USE trade;\n\n")
    for sql in sql_statements:
        f.write(sql + "\n")
    f.write("\nSELECT id, status, payment_method, shipping_address, receiver_name FROM orders LIMIT 10;\n")

print(f"已生成 {len(sql_statements)} 条UPDATE语句")
