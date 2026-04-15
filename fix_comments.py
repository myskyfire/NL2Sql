#!/usr/bin/env python3
# -*- coding: utf-8 -*-
import pymysql

def fix_comments():
    conn = pymysql.connect(
        host='localhost',
        user='root',
        password='123456',
        database='nl2sql_meta_db',
        charset='utf8mb4'
    )
    
    cursor = conn.cursor()
    
    # 修复表注释
    tables = {
        'users': '用户表',
        'user_sessions': '用户会话表',
        'whitelist': '白名单表',
        'table_permissions': '表权限表',
        'column_permissions': '列权限表',
        'operation_logs': '操作日志表',
        'sql_execution_logs': 'SQL执行日志表',
        'datasource_config': '数据源配置表',
        'table_metadata': '表元数据表',
        'column_metadata': '字段元数据表',
        'foreign_key_metadata': '外键关系表',
        'metadata_sync_log': '元数据采集日志表'
    }
    
    print("修复表注释...")
    for table, comment in tables.items():
        sql = f"ALTER TABLE `{table}` COMMENT=%s"
        cursor.execute(sql, (comment,))
        print(f"  ✓ {table}: {comment}")
    
    conn.commit()
    
    # 验证
    print("\n验证结果:")
    cursor.execute("""
        SELECT table_name, table_comment 
        FROM information_schema.tables 
        WHERE table_schema = 'nl2sql_meta_db'
        ORDER BY table_name
    """)
    
    for row in cursor.fetchall():
        print(f"  {row[0]}: {row[1]}")
    
    cursor.close()
    conn.close()
    
    print("\n✅ 修复完成！")

if __name__ == '__main__':
    fix_comments()
