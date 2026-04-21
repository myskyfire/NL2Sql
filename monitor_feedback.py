import mysql.connector
import time
import json

conn = mysql.connector.connect(
    host='localhost', 
    port=3306, 
    user='root', 
    password='123456', 
    database='nl2sql_meta_db'
)

print("监控最新的反馈记录（按Ctrl+C停止）...\n")

last_id = 0
try:
    while True:
        cursor = conn.cursor(dictionary=True)
        cursor.execute("""
            SELECT id, question, generated_sql, execution_success, rating, feedback_text, created_at 
            FROM rag_feedback 
            WHERE id > %s
            ORDER BY id ASC
            LIMIT 10
        """, (last_id,))
        
        results = cursor.fetchall()
        
        for r in results:
            last_id = r['id']
            print(f"\n{'='*80}")
            print(f"ID={r['id']} | 时间={r['created_at']}")
            print(f"问题: {r['question']}")
            print(f"评分: {r['rating']}星 | 执行成功: {r['execution_success']}")
            print(f"\n生成的SQL:")
            print(f"  {r['generated_sql']}")
            
            # 提取EX分数
            feedback = r['feedback_text']
            if 'EX:' in feedback:
                ex_part = feedback.split('EX:')[1].split()[0]
                print(f"\nEX分数: {ex_part}")
                if r['execution_success'] == 0 and float(ex_part) > 0:
                    print(f"  ⚠️ 警告：执行失败但EX分数不为0！")
        
        cursor.close()
        time.sleep(2)
        
except KeyboardInterrupt:
    print("\n\n监控已停止")
finally:
    conn.close()
