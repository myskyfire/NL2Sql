import chromadb
import sys

# Chroma 数据目录
CHROMA_PATH = r"D:\WorkSpace\idea workspace\NL2Sql\chroma"

print(f"连接到 Chroma: {CHROMA_PATH}")

try:
    # 使用 PersistentClient
    client = chromadb.PersistentClient(path=CHROMA_PATH)
    
    # 列出所有集合
    collections = client.list_collections()
    print(f"\n找到 {len(collections)} 个集合:")
    
    for col in collections:
        print(f"  - {col.name} (维度: {col.metadata.get('embedding_dimension', '未知') if col.metadata else '未知'})")
    
    # 删除所有集合（因为维度不匹配）
    print("\n开始删除所有集合...")
    for col in collections:
        try:
            client.delete_collection(col.name)
            print(f"✅ 已删除集合: {col.name}")
        except Exception as e:
            print(f"❌ 删除集合 {col.name} 失败: {e}")
    
    print("\n✅ 所有集合已删除，应用重启后将自动重建")
    
except Exception as e:
    print(f"❌ 错误: {e}")
    sys.exit(1)
