#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
删除NL2SQL项目使用的Chroma集合
只删除nl2sql_query_cache，保留其他RAG数据
"""

import requests
import json

CHROMA_URL = "http://localhost:8000"

def delete_nl2sql_collections():
    """使用v2 API删除NL2SQL相关的Chroma集合"""
    
    # 需要删除的集合列表
    collections_to_delete = [
        "NL2SQL_query_cache",  # QueryCacheVectorService使用
        "NL2SQL_rag",          # RAG使用
    ]
    
    print(f"连接到 Chroma: {CHROMA_URL}")
    
    for collection_name in collections_to_delete:
        try:
            # v2 API: DELETE /api/v2/tenants/default_tenant/databases/default_database/collections/{name}
            url = f"{CHROMA_URL}/api/v2/tenants/default_tenant/databases/default_database/collections/{collection_name}"
            response = requests.delete(url)
            
            if response.status_code == 200:
                print(f"✅ 成功删除集合: {collection_name}")
            elif response.status_code == 404:
                print(f"⚠️  集合不存在: {collection_name}")
            else:
                print(f"❌ 删除失败: {collection_name}, 状态码: {response.status_code}")
                print(f"   响应: {response.text}")
                
        except Exception as e:
            print(f"❌ 删除集合 {collection_name} 时出错: {e}")

if __name__ == "__main__":
    delete_nl2sql_collections()
