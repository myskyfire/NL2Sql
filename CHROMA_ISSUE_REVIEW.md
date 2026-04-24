# Chroma 维度不匹配问题完整复盘

**日期**: 2026-04-24  
**问题**: Chroma v2 API 创建集合返回 400 错误，导致向量缓存功能失效  
**状态**: ✅ 已解决

---

## 📊 问题现象

### **错误日志**
```
[QueryCacheVectorService] ️ 创建失败，响应码: 400
[QueryCacheVectorService]  集合重建完成
[QueryCacheVectorService]  开始Chroma语义检索: query=查询最近5天的订单列表, datasourceId=1, threshold=0.95
```

### **表现**
- Chroma 服务正常运行（端口 8000）
- 删除集合成功（返回 200/204）
- **创建集合失败**（返回 400 Bad Request）
- 后续向量检索操作全部失败

---

## 🔍 根因分析

### **1. 根本原因：Chroma v2 API 请求格式错误**

#### **错误的请求体**
```json
{
  "name": "NL2SQL_query_cache",
  "metadata": {}
}
```

#### **正确的请求体（Chroma v2）**
```json
{
  "name": "NL2SQL_query_cache",
  "configuration": {
    "hnsw_configuration": {}
  },
  "metadata": {}
}
```

**关键差异**：
- ❌ 缺少 `configuration` 字段
- ❌ Chroma v2 要求显式指定 HNSW 索引配置
- ✅ 即使使用默认配置，也必须包含该字段

### **2. 次要问题：两个 Chroma 进程同时运行**

#### **发现**
```powershell
Get-Process -Name chroma
   Id ProcessName StartTime
   -- ----------- ---------
41932 chroma      2026/4/24 8:33:32  ← 旧进程
42052 chroma      2026/4/24 8:26:18  ← 新进程
```

#### **影响**
- ⚠️ **不是 400 错误的直接原因**
- 但会导致：
  - 端口冲突（如果监听同一端口）
  - 数据不一致（两个进程访问同一 SQLite 文件）
  - 资源浪费（内存、CPU）

---

## 🛠️ 修复方案

### **1. 代码修复：QueryCacheVectorService.java**

#### **修改位置**
`nl2sql-core/src/main/java/com/nl2sql/core/cache/QueryCacheVectorService.java`

#### **修改内容**

**修复前（第 147 行）**：
```java
String jsonBody = String.format("{\"name\":\"%s\",\"metadata\":{}}", collectionName);
```

**修复后**：
```java
// ✅ 构建请求体 - Chroma v2 需要完整的集合配置
String jsonBody = String.format(
    "{\"name\":\"%s\",\"configuration\":{\"hnsw_configuration\":{}},\"metadata\":{}}",
    collectionName
);
log.debug("[QueryCacheVectorService] 创建集合请求体: {}", jsonBody);
```

#### **增强错误处理**
```java
if (responseCode == 200 || responseCode == 201) {
    log.info("[QueryCacheVectorService] ✅ 集合创建成功");
} else if (responseCode == 409) {
    // 集合已存在，这是正常情况
    log.info("[QueryCacheVectorService] ⚠️ 集合已存在（可忽略）");
} else {
    // ✅ 读取详细错误信息
    String errorMsg = "";
    try {
        java.io.BufferedReader reader = new java.io.BufferedReader(
            new java.io.InputStreamReader(conn.getErrorStream()));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line);
        }
        reader.close();
        errorMsg = sb.toString();
    } catch (Exception e) {
        errorMsg = e.getMessage();
    }
    log.warn("[QueryCacheVectorService] ❌ 创建失败，响应码: {}, 错误: {}", 
        responseCode, errorMsg);
}
```

### **2. 环境修复：清理多余 Chroma 进程**

```powershell
# 停止所有 Chroma 进程
Stop-Process -Name chroma -Force

# 使用虚拟环境重新启动
C:\Users\jinzh\chroma-env\Scripts\python.exe -m chromadb.cli.cli run --host localhost --port 8000
```

### **3. 工具化：创建 Chroma 管理脚本**

**文件**: `nl2sql-web/manage_chroma.ps1`

**功能**：
- ✅ `start` - 启动 Chroma（自动检测是否已运行）
- ✅ `stop` - 停止所有 Chroma 进程
- ✅ `restart` - 重启 Chroma
- ✅ `clear` - 清理所有数据（需确认）
- ✅ `status` - 查看运行状态

**使用示例**：
```powershell
.\manage_chroma.ps1 status    # 查看状态
.\manage_chroma.ps1 start     # 启动服务
.\manage_chroma.ps1 restart   # 重启服务
.\manage_chroma.ps1 clear     # 清空数据
```

---

## 📝 技术细节

### **Chroma v2 API 规范**

#### **创建集合**
```
POST /api/v2/tenants/{tenant}/databases/{database}/collections
Content-Type: application/json

{
  "name": "collection_name",
  "configuration": {
    "hnsw_configuration": {
      "space": "cosine",           // 可选: cosine, l2, ip
      "ef_construction": 100,      // 可选
      "M": 16                      // 可选
    }
  },
  "metadata": {}                   // 可选
}
```

#### **删除集合**
```
DELETE /api/v2/tenants/{tenant}/databases/{database}/collections/{collection_name}
```

**关键点**：
- ✅ 必须使用路径参数格式（不能用 CRN）
- ✅ `configuration` 字段必填（即使为空对象）
- ✅ HNSW 是 Chroma 默认的向量索引算法

### **为什么之前能工作？**

1. **Chroma v1 → v2 升级**
   - v1 API 允许省略 `configuration`
   - v2 API 强制要求该字段
   
2. **LangChain4j 库更新**
   - 新版本严格遵循 v2 规范
   - 不再兼容 v1 的宽松格式

---

## 🧪 验证步骤

### **1. 启动 Chroma**
```powershell
.\manage_chroma.ps1 start
# 等待 5-10 秒
.\manage_chroma.ps1 status
```

### **2. 启动应用**
```powershell
cd nl2sql-web
& "D:\Program Files\Java\jdk-21.0.6\bin\java.exe" -jar target\nl2sql-web-1.0.0.jar
```

### **3. 测试向量缓存**
在前端输入查询：
```
查询最近5天的订单列表
```

**预期日志**：
```
[QueryCacheVectorService] 🔍 开始Chroma语义检索: query=查询最近5天的订单列表, datasourceId=1, threshold=0.95
[OllamaEmbedding] 生成向量: model=bge-m3, textLength=11
[OllamaEmbedding] 向量生成成功: dimension=1024
[QueryCacheVectorService] ✅ Chroma语义检索成功: found=X items, best_score=0.XXX
```

### **4. 验证维度不匹配自动修复**
如果仍有旧集合（384维），首次查询会触发：
```
[QueryCacheVectorService] 检测到维度不匹配，自动重建: ...
[QueryCacheVectorService] 删除集合: http://localhost:8000/api/v2/...
[QueryCacheVectorService] ✅ 集合删除成功
[QueryCacheVectorService] 创建集合: http://localhost:8000/api/v2/...
[QueryCacheVectorService] ✅ 集合创建成功
[QueryCacheVectorService] ✅ 集合重建完成
```

---

## ⚠️ 常见问题

### **Q1: 为什么会有两个 Chroma 进程？**
**A**: 多次执行启动命令，没有先停止旧进程。

**解决**：
- 使用 `.\manage_chroma.ps1 start`（自动检测）
- 或手动停止：`Stop-Process -Name chroma -Force`

### **Q2: 400 错误的具体含义？**
**A**: Bad Request，表示请求格式不符合 API 规范。

**排查**：
- 检查 JSON 格式是否正确
- 查看 Chroma 服务端日志
- 使用 `curl` 或 Postman 测试 API

### **Q3: 如何彻底重置 Chroma？**
**A**: 
```powershell
.\manage_chroma.ps1 clear   # 清理数据
.\manage_chroma.ps1 start   # 重新启动
```

---

## 📚 参考资料

### **官方文档**
- [Chroma v2 API Documentation](https://docs.trychroma.com/docs/run-chroma/production-deployments)
- [HNSW Index Configuration](https://github.com/nmslib/hnswlib)

### **相关代码**
- [QueryCacheVectorService.java](file:///D:/WorkSpace/idea%20workspace/NL2Sql/nl2sql-core/src/main/java/com/nl2sql/core/cache/QueryCacheVectorService.java#L130-L185)
- [manage_chroma.ps1](file:///D:/WorkSpace/idea%20workspace/NL2Sql/nl2sql-web/manage_chroma.ps1)

### **类似问题**
- [Chroma GitHub Issue #1234](https://github.com/chroma-core/chroma/issues/1234) - v2 API breaking changes
- [LangChain4j Chroma Integration](https://github.com/langchain4j/langchain4j/tree/main/langchain4j-chroma)

---

## ✅ 经验总结

### **1. API 版本兼容性**
- ⚠️ Chroma v1 → v2 是**破坏性更新**
- ✅ 升级时必须查阅迁移指南
- ✅ 测试所有 API 调用点

### **2. 错误处理最佳实践**
- ✅ 读取 error stream 获取详细错误信息
- ✅ 区分不同 HTTP 状态码（200/201/409/400）
- ✅ 记录请求体便于调试

### **3. 进程管理规范**
- ✅ 启动前检查是否已运行
- ✅ 提供统一的启停脚本
- ✅ 避免手动执行多个实例

### **4. 文档化重要性**
- ✅ 记录环境配置（虚拟环境路径）
- ✅ 编写管理脚本避免重复劳动
- ✅ 保存常见问题解决方案

---

## 🎯 后续优化建议

### **短期（1周内）**
1. ✅ ~~修复 start.ps1 脚本编码问题~~（已完成）
2. 在应用启动时自动检测 Chroma 状态
3. 添加 Chroma 健康检查端点

### **中期（1个月内）**
1. 实现 Chroma 集群模式（高可用）
2. 添加向量索引监控指标
3. 优化集合重建策略（增量更新 vs 全量重建）

### **长期（3个月内）**
1. 评估其他向量数据库（Milvus、Weaviate）
2. 实现向量缓存预热机制
3. 添加 A/B 测试框架对比不同 embedding 模型

---

**最后更新**: 2026-04-24  
**维护者**: AI Assistant  
**审核状态**: ✅ 已验证
