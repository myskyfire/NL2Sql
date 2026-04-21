import mysql.connector

conn = mysql.connector.connect(
    host='localhost', 
    port=3306, 
    user='root', 
    password='123456', 
    database='nl2sql_meta_db'
)

cursor = conn.cursor()

sql = "SELECT product_name AS '商品名称', price AS '价格' FROM products ORDER BY price DESC LIMIT 1;"

print('尝试执行SQL:')
print(sql)
print()

try:
    cursor.execute(sql)
    result = cursor.fetchall()
    print('✅ 执行成功!')
    print(f'返回 {len(result)} 条记录')
    if result:
        print('第一条:', result[0])
except Exception as e:
    print(f'❌ 执行失败: {e}')
    print(f'错误类型: {type(e).__name__}')

cursor.close()
conn.close()
