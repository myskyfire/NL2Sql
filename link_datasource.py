import pymysql

conn = pymysql.connect(
    host='localhost',
    user='root',
    password='123456',
    database='nl2sql_meta_db',
    charset='utf8mb4'
)

cursor = conn.cursor()

# 关联数据源1到电商行业
cursor.execute('''
    INSERT INTO datasource_industry_mapping (datasource_id, industry_code, priority) 
    VALUES (1, 'ecommerce', 1) 
    ON DUPLICATE KEY UPDATE 
        industry_code=VALUES(industry_code), 
        priority=VALUES(priority)
''')

conn.commit()
print('✓ Data source 1 linked to ecommerce industry')

# 验证
cursor.execute('SELECT datasource_id, industry_code, priority FROM datasource_industry_mapping WHERE datasource_id=1')
result = cursor.fetchone()
print(f'  Mapping: datasource_id={result[0]}, industry={result[1]}, priority={result[2]}')

conn.close()
