import chromadb
import json

client = chromadb.PersistentClient(path='./chroma_db')
collection = client.get_collection('query_cache')

# 查询高分缓存
results = collection.get()
print(f"Total cached queries: {len(results['ids'])}\n")

# 显示前10个
for i, (id, doc, meta) in enumerate(zip(results['ids'], results['documents'], results['metadatas'])):
    if i >= 10:
        break
    score = meta.get('score', 0)
    tables = meta.get('tables', '[]')
    print(f"[{i+1}] Score: {score:.3f}")
    print(f"    Query: {doc[:80]}...")
    print(f"    Tables: {tables}")
    print()
