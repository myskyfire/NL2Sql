import mysql.connector

try:
    conn = mysql.connector.connect(
        host='192.168.31.200',
        port=3306,
        user='root',
        password='Qq123456.',
        database='nl2sql_meta_db'
    )
    
    cursor = conn.cursor()
    cursor.execute("DELETE FROM rag_feedback")
    deleted_count = cursor.rowcount
    conn.commit()
    
    print(f"已删除 {deleted_count} 条rag_feedback记录")
    
    cursor.close()
    conn.close()
except Exception as e:
    print(f"错误: {e}")
