import mysql.connector
import json

conn = mysql.connector.connect(
    host='localhost', 
    port=3306, 
    user='root', 
    password='123456', 
    database='nl2sql_meta_db'
)
cursor = conn.cursor(dictionary=True)

# 查询"用户总数"相关的记录
cursor.execute("""
    SELECT id, question, generated_sql, execution_success, rating, feedback_text, created_at 
    FROM rag_feedback 
    WHERE question = '用户总数' 
    ORDER BY id DESC 
    LIMIT 5
""")

results = cursor.fetchall()
print(f"找到 {len(results)} 条'用户总数'相关记录:\n")

for i, r in enumerate(results, 1):
    print(f"{'='*80}")
    print(f"记录 #{i} (ID={r['id']})")
    print(f"创建时间: {r['created_at']}")
    print(f"评分: {r['rating']}星")
    print(f"执行成功: {r['execution_success']}")
    print(f"\n生成的SQL:")
    print(f"  {r['generated_sql']}")
    print(f"\n反馈文本:")
    print(f"  {r['feedback_text'][:300]}...")
    print()

cursor.close()
conn.close()
