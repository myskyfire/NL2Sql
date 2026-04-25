import pymysql

conn = pymysql.connect(
    host='localhost',
    user='root',
    password='123456',
    database='nl2sql_meta_db',
    charset='utf8mb4'
)

cursor = conn.cursor()

# 读取SQL文件
with open(r'D:\WorkSpace\idea workspace\NL2Sql\init_ecommerce_industry_concepts.sql', 'r', encoding='utf-8') as f:
    lines = f.readlines()

# 过滤注释和空行,合并为完整SQL
sql_statements = []
current_stmt = []
for line in lines:
    line = line.strip()
    if not line or line.startswith('--'):
        continue
    current_stmt.append(line)
    if line.endswith(';'):
        sql_statements.append(' '.join(current_stmt))
        current_stmt = []

# 执行每个语句
success_count = 0
error_count = 0
for stmt in sql_statements:
    try:
        cursor.execute(stmt)
        success_count += 1
    except Exception as e:
        error_count += 1
        print(f"❌ Error: {e}")
        print(f"   Statement: {stmt[:150]}...\n")

conn.commit()
print(f"✓ Successfully executed {success_count} statements")

# 验证结果
cursor.execute('''
    SELECT concept_type, COUNT(*) as cnt 
    FROM industry_concept 
    WHERE industry_code="ecommerce" AND status="approved"
    GROUP BY concept_type
''')

print("\n📊 Ecommerce concepts by type:")
for row in cursor.fetchall():
    print(f"  {row[0]}: {row[1]}")

# 检查usage_rule
cursor.execute('''
    SELECT concept_key, LEFT(description, 50) as desc_preview
    FROM industry_concept 
    WHERE industry_code="ecommerce" AND concept_type="usage_rule"
''')

print("\n⚠️  Usage rules:")
for row in cursor.fetchall():
    print(f"  {row[0]}: {row[1]}...")

conn.close()
print("\n✅ Done!")
