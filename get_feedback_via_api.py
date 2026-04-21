import requests
import json

# 调用后端API获取低分反馈
response = requests.get('http://localhost:8080/api/feedback/low-rating?limit=20')

if response.status_code == 200:
    data = response.json()
    if data['code'] == 200:
        feedbacks = data['data']
        print(f"找到 {len(feedbacks)} 条低分反馈\n")
        print("="*120)
        
        # 读取训练测试用例
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
        
        for i, fb in enumerate(feedbacks, 1):
            print(f"\n【第{i}条】")
            print(f"ID: {fb.get('id')}")
            print(f"评分: {fb.get('rating')}星")
            question = fb.get('question', '')
            print(f"问题: {question}")
            print(f"\n生成的SQL:")
            print(fb.get('generated_sql', ''))
            
            # 查找预期SQL
            if question in training_cases:
                print(f"\n预期SQL:")
                print(training_cases[question])
            else:
                matched = False
                for train_q, train_sql in training_cases.items():
                    if question == train_q:
                        print(f"\n预期SQL:")
                        print(train_sql)
                        matched = True
                        break
                
                if not matched:
                    # 尝试部分匹配
                    for train_q, train_sql in training_cases.items():
                        if question in train_q or train_q in question:
                            print(f"\n预期SQL (匹配 '{train_q}'):")
                            print(train_sql)
                            matched = True
                            break
                
                if not matched:
                    print(f"\n⚠️ 未在训练集中找到对应问题")
            
            feedback_text = fb.get('feedback_text', '')
            if feedback_text:
                print(f"\n用户反馈:")
                print(feedback_text)
            
            print("-"*120)
    else:
        print(f"API返回错误: {data.get('message')}")
else:
    print(f"HTTP错误: {response.status_code}")
    print(response.text)
