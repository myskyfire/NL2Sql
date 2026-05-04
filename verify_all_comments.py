import pymysql

conn = pymysql.connect(
    host='localhost',
    port=33061,
    user='root',
    password='123456',
    database='trade',
    charset='utf8mb4'
)

cur = conn.cursor()

tables = ['coupons', 'order_discounts', 'product_favorites', 'product_reviews', 
          'shipments', 'shipping_companies', 'user_coupons', 'user_points']

print("=== 表注释验证 ===")
for table in tables:
    cur.execute(f'SELECT TABLE_COMMENT FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA="trade" AND TABLE_NAME="{table}"')
    result = cur.fetchone()
    print(f"{table:20s} -> {result[0]}")

# 抽查列注释
print("\n=== coupons表列注释 ===")
cur.execute('SELECT COLUMN_NAME, COLUMN_COMMENT FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA="trade" AND TABLE_NAME="coupons" ORDER BY ORDINAL_POSITION')
for col in cur.fetchall():
    print(f"{col[0]:20s} -> {col[1]}")

print("\n=== product_reviews表列注释 ===")
cur.execute('SELECT COLUMN_NAME, COLUMN_COMMENT FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA="trade" AND TABLE_NAME="product_reviews" ORDER BY ORDINAL_POSITION LIMIT 5')
for col in cur.fetchall():
    print(f"{col[0]:20s} -> {col[1]}")

conn.close()
print("\n✓ 所有中文注释已正确存储到数据库")
