import mysql.connector
import json

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
LIMIT 5
"""

cursor.execute(query)
results = cursor.fetchall()

print(f"找到 {len(results)} 条低分反馈\n")
print("="*120)

# 读取训练测试用例，建立问题到预期SQL的映射
training_cases = {}
with open('training_test_cases.txt', 'r', encoding='utf-8') as f:
    for line in f:
        line = line.strip()
        if line and not line.startswith('#'):
            parts = line.split('|')
            if len(parts) >= 2:
                question = parts[0].strip()
                expected_sql = parts[1].strip()
                training_cases[question] = expected_sql

for i, row in enumerate(results, 1):
    print(f"\n【第{i}条】")
    print(f"ID: {row['id']}")
    print(f"评分: {row['rating']}星")
    print(f"问题: {row['question']}")
    print(f"\n生成的SQL:")
    print(row['generated_sql'])
    
    # 查找对应的预期SQL
    question = row['question']
    if question in training_cases:
        print(f"\n预期SQL:")
        print(training_cases[question])
    else:
        # 尝试模糊匹配
        matched = False
        for train_q, train_sql in training_cases.items():
            if question in train_q or train_q in question:
                print(f"\n预期SQL (模糊匹配 '{train_q}'):")
                print(train_sql)
                matched = True
                break
        if not matched:
            print(f"\n⚠️ 未在训练集中找到对应问题")
    
    if row['feedback_text']:
        print(f"\n用户反馈:")
        print(row['feedback_text'])
    
    print(f"\n时间: {row['created_at']}")
    print("-"*120)

cursor.close()
conn.close()
