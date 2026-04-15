#!/usr/bin/env python3
# -*- coding: utf-8 -*-
import pymysql

def fix_column_comments():
    conn = pymysql.connect(
        host='localhost',
        user='root',
        password='123456',
        database='nl2sql_meta_db',
        charset='utf8mb4'
    )
    
    cursor = conn.cursor()
    
    # 定义每个表的字段注释
    column_comments = {
        'users': {
            'id': '用户ID',
            'username': '用户名',
            'password': '密码(BCrypt加密)',
            'real_name': '真实姓名',
            'email': '邮箱',
            'phone': '手机号',
            'role': '角色: admin/user',
            'status': '状态: 1启用 0禁用',
            'last_login_at': '最后登录时间',
            'created_at': '创建时间',
            'updated_at': '更新时间'
        },
        'user_sessions': {
            'id': '主键ID',
            'user_id': '用户ID',
            'token': 'Token',
            'ip_address': 'IP地址',
            'user_agent': 'User-Agent',
            'expires_at': '过期时间',
            'created_at': '创建时间'
        },
        'whitelist': {
            'id': '主键ID',
            'user_id': '用户ID',
            'added_by': '添加人ID',
            'reason': '添加原因',
            'expires_at': '过期时间(NULL表示永久)',
            'is_active': '是否激活',
            'created_at': '创建时间'
        },
        'table_permissions': {
            'id': '主键ID',
            'user_id': '用户ID',
            'table_name': '表名',
            'granted_by': '授权人ID',
            'granted_at': '授权时间',
            'expires_at': '过期时间(NULL表示永久)',
            'is_active': '是否激活'
        },
        'column_permissions': {
            'id': '主键ID',
            'user_id': '用户ID',
            'table_name': '表名',
            'column_name': '列名',
            'can_view': '是否可查看',
            'need_desensitize': '是否需脱敏',
            'granted_by': '授权人ID',
            'granted_at': '授权时间'
        },
        'operation_logs': {
            'id': '主键ID',
            'user_id': '用户ID',
            'username': '用户名',
            'operation': '操作类型',
            'target_type': '目标类型',
            'target_id': '目标ID',
            'details': '操作详情',
            'ip_address': 'IP地址',
            'created_at': '操作时间'
        },
        'sql_execution_logs': {
            'id': '日志ID',
            'user_id': '用户ID',
            'username': '用户名',
            'sql_text': '执行的SQL',
            'execution_time_ms': '执行时间(毫秒)',
            'row_count': '返回行数',
            'is_slow_query': '是否慢查询',
            'status': '状态: SUCCESS/FAILED/TIMEOUT',
            'error_message': '错误信息',
            'ip_address': 'IP地址',
            'created_at': '执行时间'
        },
        'datasource_config': {
            'id': '主键ID',
            'name': '数据源名称',
            'db_type': '数据库类型: MYSQL/ORACLE/DAMENG/POSTGRESQL',
            'host': '主机地址',
            'port': '端口',
            'database_name': '数据库名',
            'username': '用户名',
            'password_encrypted': '加密后的密码',
            'encryption_algorithm': '加密算法',
            'is_active': '是否激活: 0-禁用, 1-启用',
            'description': '描述',
            'created_by': '创建人ID',
            'created_at': '创建时间',
            'updated_at': '更新时间'
        },
        'table_metadata': {
            'id': '主键ID',
            'datasource_id': '数据源ID',
            'table_name': '表名',
            'table_comment': '表注释',
            'table_type': '表类型: TABLE/VIEW',
            'schema_name': 'Schema名称',
            'row_count_estimate': '预估行数',
            'data_size_kb': '数据大小(KB)',
            'index_size_kb': '索引大小(KB)',
            'created_at': '采集时间',
            'updated_at': '更新时间'
        },
        'column_metadata': {
            'id': '主键ID',
            'datasource_id': '数据源ID',
            'table_name': '表名',
            'column_name': '字段名',
            'data_type': '数据类型',
            'column_size': '字段长度',
            'decimal_digits': '小数位数',
            'is_nullable': '是否可空: 0-否, 1-是',
            'column_default': '默认值',
            'column_comment': '字段注释',
            'is_primary_key': '是否主键: 0-否, 1-是',
            'is_unique': '是否唯一: 0-否, 1-是',
            'ordinal_position': '字段顺序',
            'character_set_name': '字符集',
            'collation_name': '排序规则',
            'extra_info': '额外信息(auto_increment等)',
            'created_at': '采集时间',
            'updated_at': '更新时间'
        },
        'foreign_key_metadata': {
            'id': '主键ID',
            'datasource_id': '数据源ID',
            'fk_name': '外键约束名',
            'table_name': '主表名',
            'column_name': '主表字段',
            'ref_table_name': '引用表名',
            'ref_column_name': '引用字段',
            'update_rule': '更新规则',
            'delete_rule': '删除规则',
            'created_at': '采集时间'
        },
        'metadata_sync_log': {
            'id': '主键ID',
            'datasource_id': '数据源ID',
            'sync_status': '同步状态: SUCCESS/FAILED/PARTIAL',
            'table_count': '同步表数量',
            'column_count': '同步字段数量',
            'foreign_key_count': '同步外键数量',
            'error_message': '错误信息',
            'started_at': '开始时间',
            'completed_at': '完成时间',
            'duration_seconds': '耗时(秒)',
            'created_by': '操作人ID',
            'created_at': '记录时间'
        }
    }
    
    print("修复字段注释...")
    total_fixed = 0
    
    for table, columns in column_comments.items():
        for column, comment in columns.items():
            try:
                sql = f"ALTER TABLE `{table}` MODIFY COLUMN `{column}` "
                
                # 先查询字段类型
                cursor.execute(f"""
                    SELECT COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT, EXTRA
                    FROM information_schema.COLUMNS 
                    WHERE TABLE_SCHEMA = 'nl2sql_meta_db' 
                      AND TABLE_NAME = %s 
                      AND COLUMN_NAME = %s
                """, (table, column))
                
                row = cursor.fetchone()
                if row:
                    col_type = row[0]
                    is_nullable = row[1]
                    col_default = row[2]
                    extra = row[3]
                    
                    sql += f"{col_type} "
                    if is_nullable == 'NO':
                        sql += "NOT NULL "
                    if col_default is not None:
                        if col_default == 'CURRENT_TIMESTAMP':
                            sql += f"DEFAULT {col_default} "
                        elif col_default.startswith('\''):
                            sql += f"DEFAULT {col_default} "
                        else:
                            sql += f"DEFAULT '{col_default}' "
                    if extra:
                        sql += f"{extra} "
                    
                    sql += f"COMMENT %s"
                    cursor.execute(sql, (comment,))
                    total_fixed += 1
            except Exception as e:
                print(f"  ⚠ {table}.{column}: {e}")
        
        print(f"  ✓ {table}: {len(columns)} 个字段")
    
    conn.commit()
    print(f"\n✅ 共修复 {total_fixed} 个字段注释")
    
    cursor.close()
    conn.close()

if __name__ == '__main__':
    fix_column_comments()
