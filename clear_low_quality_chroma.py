#!/usr/bin/env python3
"""
删除Chroma中的低质量RAG知识（quality_score < 0.6）
"""
import chromadb
import os

# Chroma路径
chroma_path = os.path.join(os.path.dirname(__file__), "chroma")

print(f"连接Chroma: {chroma_path}")
client = chromadb.PersistentClient(path=chroma_path)

# 获取集合
collection_name = "NL2SQL_rag"
print(f"检查集合: {collection_name}")

try:
    collection = client.get_collection(name=collection_name)
    print(f"✅ 找到集合: {collection_name}")
    
    # 获取所有数据
    results = collection.get()
    total_count = len(results['ids'])
    print(f"总记录数: {total_count}")
    
    if total_count == 0:
        print("集合为空，无需清理")
        exit(0)
    
    # 检查是否有metadata
    if not results['metadatas'] or len(results['metadatas']) == 0:
        print("⚠️  所有记录都没有metadata（历史数据）")
        print("建议: 删除整个chroma目录重建")
        exit(0)
    
    # 统计有quality_score的记录
    has_quality_score = 0
    low_quality_ids = []
    
    for i, metadata in enumerate(results['metadatas']):
        if metadata and 'quality_score' in metadata:
            has_quality_score += 1
            quality_score = float(metadata['quality_score'])
            
            if quality_score < 0.6:
                low_quality_ids.append(results['ids'][i])
                print(f"  🗑️  低质量: id={results['ids'][i][:8]}..., quality_score={quality_score}, question={metadata.get('question', '')[:50]}")
    
    print(f"\n统计:")
    print(f"  有quality_score的记录: {has_quality_score}/{total_count}")
    print(f"  低质量记录(<0.6): {len(low_quality_ids)}")
    
    if len(low_quality_ids) > 0:
        confirm = input(f"\n确认删除 {len(low_quality_ids)} 条低质量记录? (yes/no): ")
        if confirm.lower() == 'yes':
            collection.delete(ids=low_quality_ids)
            print(f"✅ 已删除 {len(low_quality_ids)} 条低质量记录")
        else:
            print("❌ 取消删除")
    else:
        print("✅ 没有低质量记录需要删除")
        
except Exception as e:
    print(f"❌ 错误: {e}")
    import traceback
    traceback.print_exc()
