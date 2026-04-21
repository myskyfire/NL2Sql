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
cursor.execute("""
    SELECT id, question, generated_sql, execution_success, rating, feedback_text, created_at 
    FROM rag_feedback 
    ORDER BY id ASC
""")

results = cursor.fetchall()
print(f"共找到 {len(results)} 条反馈记录\n")
print("="*100)

issues = []

for i, r in enumerate(results, 1):
    print(f"\n[{i}/{len(results)}] ID={r['id']} | 时间={r['created_at']}")
    print(f"问题: {r['question']}")
    print(f"评分: {r['rating']}星 | 执行成功: {r['execution_success']}")
    print(f"SQL: {r['generated_sql'][:150]}...")
    
    # 提取EX分数
    feedback = r['feedback_text']
    ex_score = None
    if 'EX:' in feedback:
        try:
            ex_part = feedback.split('EX:')[1].split()[0]
            ex_score = float(ex_part)
        except:
            pass
    
    # 分析评分合理性
    analysis = []
    is_problematic = False
    
    # 规则1: 执行失败但EX分数>0
    if r['execution_success'] == 0 and ex_score and ex_score > 0:
        analysis.append(f"❌ 严重错误: 执行失败但EX={ex_score}")
        is_problematic = True
    
    # 规则2: 执行成功但EX分数=0
    if r['execution_success'] == 1 and ex_score and ex_score == 0:
        analysis.append(f"⚠️ 异常: 执行成功但EX={ex_score}")
        is_problematic = True
    
    # 规则3: SQL明显语法错误但评分>=4
    sql_lower = r['generated_sql'].lower().strip()
    if not sql_lower.startswith('select'):
        if r['rating'] >= 4:
            analysis.append(f"❌ SQL不是SELECT语句但评分{r['rating']}星")
            is_problematic = True
    
    # 规则4: 缺少FROM子句但评分>=3
    if 'from' not in sql_lower and 'select' in sql_lower:
        if r['rating'] >= 3:
            analysis.append(f"❌ 缺少FROM子句但评分{r['rating']}星")
            is_problematic = True
    
    # 规则5: 执行失败但评分>=4
    if r['execution_success'] == 0 and r['rating'] >= 4:
        analysis.append(f"⚠️ 执行失败但评分{r['rating']}星（可能过高）")
        is_problematic = True
    
    if analysis:
        print("分析结果:")
        for a in analysis:
            print(f"  {a}")
        issues.append({
            'id': r['id'],
            'question': r['question'],
            'rating': r['rating'],
            'exec_success': r['execution_success'],
            'ex_score': ex_score,
            'issues': analysis
        })
    else:
        print("✅ 评分合理")

print("\n" + "="*100)
print(f"\n总结:")
print(f"总记录数: {len(results)}")
print(f"有问题的记录: {len(issues)}")
print(f"问题率: {len(issues)/len(results)*100:.1f}%")

if issues:
    print(f"\n问题详情:")
    for issue in issues:
        print(f"\n  ID={issue['id']}: {issue['question']}")
        print(f"    评分={issue['rating']}星, 执行={'成功' if issue['exec_success'] else '失败'}, EX={issue['ex_score']}")
        for msg in issue['issues']:
            print(f"    {msg}")

cursor.close()
conn.close()
