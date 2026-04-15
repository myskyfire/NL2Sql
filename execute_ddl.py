#!/usr/bin/env python3
# -*- coding: utf-8 -*-
import pymysql
import sys

def execute_sql_file():
    try:
        # 连接数据库
        conn = pymysql.connect(
            host='localhost',
            user='root',
            password='123456',
            database='nl2sql_meta_db',
            charset='utf8mb4'
        )
        
        cursor = conn.cursor()
        
        # 读取SQL文件
        with open('init_complete_database.sql', 'r', encoding='utf-8') as f:
            sql_content = f.read()
        
        # 分割并执行每条SQL
        statements = sql_content.split(';')
        for stmt in statements:
            stmt = stmt.strip()
            if stmt and not stmt.startswith('--'):
                try:
                    cursor.execute(stmt)
                except Exception as e:
                    print(f"Warning: {e}")
        
        conn.commit()
        
        # 验证表注释
        cursor.execute("""
            SELECT table_name, table_comment 
            FROM information_schema.tables 
            WHERE table_schema = 'nl2sql_meta_db'
            ORDER BY table_name
        """)
        
        print("\n=== 表注释验证 ===")
        for row in cursor.fetchall():
            print(f"{row[0]}: {row[1]}")
        
        cursor.close()
        conn.close()
        
        print("\n✅ 数据库初始化成功！")
        return True
        
    except Exception as e:
        print(f"❌ 错误: {e}", file=sys.stderr)
        return False

if __name__ == '__main__':
    execute_sql_file()
