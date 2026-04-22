"""
表描述自动增强脚本 - 使用LLM生成业务化表说明
"""
import mysql.connector
import requests
import json

# 数据库配置
DB_CONFIG = {
    'host': 'localhost',
    'port': 3306,
    'user': 'root',
    'password': '123456',
    'database': 'nl2sql_meta_db'
}

# Ollama配置
OLLAMA_URL = "http://localhost:11434/api/generate"
MODEL = "qwen3:8b"

def get_table_info(datasource_id):
    """获取表的字段信息"""
    conn = mysql.connector.connect(**DB_CONFIG)
    cursor = conn.cursor(dictionary=True)
    
    # 获取表名和当前描述
    cursor.execute("""
        SELECT table_name, table_comment 
        FROM table_metadata 
        WHERE datasource_id = %s
    """, (datasource_id,))
    tables = cursor.fetchall()
    
    result = []
    for table in tables:
        table_name = table['table_name']
        
        # 获取字段信息
        cursor.execute("""
            SELECT column_name, data_type, column_comment
            FROM column_metadata
            WHERE datasource_id = %s AND table_name = %s
            ORDER BY ordinal_position
        """, (datasource_id, table_name))
        columns = cursor.fetchall()
        
        result.append({
            'table_name': table_name,
            'current_comment': table['table_comment'],
            'columns': columns
        })
    
    cursor.close()
    conn.close()
    return result

def generate_description_with_llm(table_info):
    """使用LLM生成业务化表描述"""
    prompt = f"""你是一个数据库专家,请根据以下表结构信息,生成一段简洁的业务化表描述(50字以内)。

表名: {table_info['table_name']}
当前描述: {table_info['current_comment']}
字段列表:
{json.dumps([{'字段': c['column_name'], '类型': c['data_type'], '说明': c['column_comment']} for c in table_info['columns']], ensure_ascii=False, indent=2)}

要求:
1. 包含核心业务指标关键词(如订单量、销售额、用户数等)
2. 说明表的主要用途和关联关系
3. 简洁明了,便于向量检索匹配
4. 只返回描述文本,不要其他内容

生成的描述:"""

    try:
        response = requests.post(OLLAMA_URL, json={
            'model': MODEL,
            'prompt': prompt,
            'stream': False
        }, timeout=60)
        
        if response.status_code == 200:
            result = response.json()
            description = result.get('response', '').strip()
            return description
        else:
            print(f"❌ LLM调用失败: {response.status_code}")
            return None
    except Exception as e:
        print(f"❌ LLM调用异常: {e}")
        return None

def update_table_description(datasource_id, table_name, new_description):
    """更新表描述"""
    conn = mysql.connector.connect(**DB_CONFIG)
    cursor = conn.cursor()
    
    cursor.execute("""
        UPDATE table_metadata 
        SET table_comment = %s 
        WHERE datasource_id = %s AND table_name = %s
    """, (new_description, datasource_id, table_name))
    
    conn.commit()
    cursor.close()
    conn.close()

def main():
    datasource_id = 1
    
    print(f"🔍 开始增强数据源 {datasource_id} 的表描述...")
    
    tables = get_table_info(datasource_id)
    print(f"📊 共找到 {len(tables)} 个表\n")
    
    for i, table in enumerate(tables, 1):
        print(f"[{i}/{len(tables)}] 处理表: {table['table_name']}")
        print(f"  原描述: {table['current_comment']}")
        
        # 生成新描述
        new_desc = generate_description_with_llm(table)
        
        if new_desc and len(new_desc) > 10:
            print(f"  新描述: {new_desc}")
            
            # 更新数据库
            update_table_description(datasource_id, table['table_name'], new_desc)
            print(f"  ✅ 已更新\n")
        else:
            print(f"  ⚠️ 生成失败,跳过\n")
    
    print("✅ 所有表描述增强完成!")
    print("⚠️ 请重启应用以重新加载向量索引")

if __name__ == "__main__":
    main()
