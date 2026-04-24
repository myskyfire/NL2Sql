#!/usr/bin/env python3
"""列出Chroma所有集合"""
import chromadb
import os

chroma_path = os.path.join(os.path.dirname(__file__), "chroma")
print(f"连接Chroma: {chroma_path}")
client = chromadb.PersistentClient(path=chroma_path)

collections = client.list_collections()
print(f"\n找到 {len(collections)} 个集合:")
for col in collections:
    print(f"  - {col.name} (记录数: {col.count()})")
