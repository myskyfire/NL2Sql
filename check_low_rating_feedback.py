import mysql.connector

# 连接数据库
conn = mysql.connector.connect(
    host='192.168.31.200',
    port=3306,
    user='root',
    password='123456',
    database='nl2sql_meta_db'
)

cursor = conn.cursor(dictionary=True)

# 查询低分反馈（rating <= 3）
query = """
SELECT id, question, generated_sql, rating, feedback_text, created_at
FROM rag_feedback
WHERE rating <= 3
ORDER BY created_at DESC
LIMIT 10
"""

cursor.execute(query)
results = cursor.fetchall()

print(f"找到 {len(results)} 条低分反馈\n")
print("="*100)

for i, row in enumerate(results, 1):
    print(f"\n【第{i}条】")
    print(f"ID: {row['id']}")
    print(f"评分: {row['rating']}星")
    print(f"问题: {row['question']}")
    print(f"生成的SQL:\n{row['generated_sql']}")
    if row['feedback_text']:
        print(f"用户反馈: {row['feedback_text']}")
    print(f"时间: {row['created_at']}")
    print("-"*100)

cursor.close()
conn.close()
