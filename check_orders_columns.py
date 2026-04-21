import mysql.connector

conn = mysql.connector.connect(
    host='192.168.31.200',
    port=3306,
    user='root',
    password='Qq123456.',
    database='nl2sql_meta_db'
)

cursor = conn.cursor()
cursor.execute("""
    SELECT column_name, column_comment 
    FROM column_metadata 
    WHERE table_name = 'orders' AND datasource_id = 1 
    ORDER BY ordinal_position
""")

rows = cursor.fetchall()
for row in rows:
    print(f"{row[0]}: {row[1]}")

cursor.close()
conn.close()
