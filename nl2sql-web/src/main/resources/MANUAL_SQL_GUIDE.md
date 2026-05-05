# 手动执行SQL使用指南

**最后更新**: 2026-05-04  
**适用版本**: DataMind AI v1.4.0+

## 📖 功能概述

手动执行SQL功能允许直接编写和执行 SQL 语句，支持权限校验和安全检查。

## 🎯 核心功能

### 1. SQL 编辑器
- 语法高亮显示
- 自动补全提示
- 错误检查

### 2. 权限校验
- 自动检查表访问权限
- 检查操作权限（SELECT/INSERT/UPDATE/DELETE）
- 越权操作自动拦截

### 3. 安全检查
- 危险操作拦截（DROP/TRUNCATE等）
- 批量操作确认
- 操作日志记录

## 💡 使用示例

### 执行查询
```sql
SELECT * FROM orders WHERE create_time >= '2024-01-01' LIMIT 100;
```

### 执行更新
```sql
UPDATE orders SET status = 'completed' WHERE id = 123;
```

## ⚠️ 注意事项

1. **权限限制**：只能操作有权限的表
2. **安全限制**：危险操作需要二次确认
3. **结果限制**：查询默认最多返回1000条

## 🔗 相关功能

- [执行日志](EXECUTION_LOG_GUIDE.md)
- [表授权管理](TABLE_PERMISSION_GUIDE.md)
