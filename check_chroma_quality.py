#!/usr/bin/env python3
"""检查Chroma中是否有低质量示例"""
import chromadb
import os

chroma_path = os.path.join(os.path.dirname(__file__), "chroma")
print(f"连接Chroma: {chroma_path}")

try:
    client = chromadb.PersistentClient(path=chroma_path)
    collections = client.list_collections()
    
    if len(collections) == 0:
        print("❌ Chroma中没有集合(可能刚删除)")
        exit(0)
    
    for col in collections:
        print(f"\n集合: {col.name} (记录数: {col.count()})")
        
        if col.count() > 0:
            results = col.get()
            
            # 检查前5条记录的metadata
            for i in range(min(5, len(results['ids']))):
                metadata = results['metadatas'][i] if results['metadatas'] else {}
                question = results['documents'][i][:80] if results['documents'] else ''
                
                quality_score = metadata.get('quality_score', 'N/A')
                print(f"  [{i+1}] quality_score={quality_score}, question={question}")
                
except Exception as e:
    print(f"❌ 错误: {e}")
    import traceback
    traceback.print_exc()
