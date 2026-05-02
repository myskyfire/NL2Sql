import pymysql

conn = pymysql.connect(host='localhost', user='root', password='123456', database='trade')
cursor = conn.cursor()

tables = ['users','orders','order_items','products','product_categories','user_addresses','inventory','returns']

print('=== Trade 库表结构和数据量 ===\n')

for table in tables:
    print(f'--- {table} ---')
    
    # 表结构
    cursor.execute(f'DESCRIBE {table}')
    columns = cursor.fetchall()
    for col in columns:
        pk_mark = ' [PK]' if col[3] == 'PRI' else ''
        null_mark = ' NULL' if col[2] == 'YES' else ' NOT NULL'
        print(f'  {col[0]:20s} {col[1]:15s}{null_mark}{pk_mark}')
    
    # 数据量
    cursor.execute(f'SELECT COUNT(*) FROM {table}')
    count = cursor.fetchone()[0]
    print(f'  数据量: {count}\n')

# 示例数据
print('\n=== 示例数据 ===\n')
for table in ['users', 'orders', 'products']:
    print(f'--- {table} (前3条) ---')
    cursor.execute(f'SELECT * FROM {table} LIMIT 3')
    rows = cursor.fetchall()
    cursor.execute(f'DESCRIBE {table}')
    cols = [r[0] for r in cursor.fetchall()]
    print(f'  字段: {", ".join(cols)}')
    for row in rows:
        print(f'  {row}')
    print()

conn.close()
