# NL2SQL 企业级智能查询系统 v2.0.0

<div align="center">

**用自然语言查询数据库，让数据分析触手可及**

[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/java-1.8+-orange.svg)](https://www.oracle.com/java/)
[![Spring Boot](https://img.shields.io/badge/spring%20boot-2.7.18-green.svg)](https://spring.io/projects/spring-boot)
[![Status](https://img.shields.io/badge/status-production%20ready-brightgreen.svg)]()

</div>

## 🎯 项目简介

NL2SQL是一款基于Spring Boot + LangChain4j的企业级自然语言转SQL智能查询系统。用户只需用自然语言描述数据需求（如“查询最近7天的订单总额”），系统即可自动生成SQL、执行查询、并以可视化图表和AI总结的形式呈现结果。

### 核心价值

- ✅ **零SQL门槛**: 业务人员无需掌握SQL即可自助查询
- ✅ **秒级响应**: 平均3秒内返回结果（含LLM调用）
- ✅ **企业级安全**: 四层防护体系，保障数据安全
- ✅ **智能洞察**: AI自动分析数据，提供业务建议

### 快速体验

```bash
# 1. 启动依赖服务
mysql -u root -p < init_complete_database.sql
redis-server
ollama pull qwen2.5-coder:7b-instruct && ollama serve

# 2. 编译运行
cd "E:\work\idea workspace\NL2SQL"
mvn clean package -DskipTests
cd NL2SQL-web/target
java -jar NL2SQL-web-1.0.0.jar

# 3. 访问系统
浏览器打开: http://localhost:8080
```

详细部署文档请查看 [PROJECT_SUMMARY.md](PROJECT_SUMMARY.md)

## 🚀 核心功能

### 1. 自然语言转SQL (NL2SQL)
- ✅ 支持中文口语化提问自动生成MySQL标准SQL
- ✅ 自动理解查询意图：查什么、查哪张表、按什么条件过滤
- ✅ 基于真实数据库元数据自动匹配
- ✅ 双层向量检索（表 + 字段）
- ✅ LLM驱动SQL生成（Ollama + DeepSeek-R1）
- ✅ SQL格式化与美化
- ✅ **同义词词典** - 自动识别业务术语（订单/定单、用户/客户等）
- ✅ **时间表达式解析** - 智能理解“昨天”、“最近7天”等时间表达

### 2. 企业级安全控制
- ✅ 严格只读权限控制（仅允许SELECT/SHOW/DESC/EXPLAIN）
- ✅ 危险操作关键字拦截（DROP/ALTER/DELETE等）
- ✅ 表关联数量限制（最多2张表JOIN）
- ✅ 全表扫描检测与拦截
- ✅ 敏感字段自动脱敏（phone/email/password等）
- ✅ **表级权限控制** - 用户只能访问授权的表
- ✅ **列级权限控制** - 细粒度控制字段可见性
- ✅ **白名单机制** - 只有白名单用户可使用系统
- ✅ **Token认证** - JWT风格的安全认证

### 3. 智能缓存与性能优化
- ✅ **智能缓存层** - 基于SQL语义的Redis缓存，避免重复LLM调用
- ✅ **查询结果分页** - 支持大数据量分页返回，默认每页50条
- ✅ **SQL自动纠错** - 6种错误类型识别，最多3次自动重试
- ✅ **RAG检索增强** - 从历史问答对检索相似示例，提升SQL准确性
- ✅ **多模型智能路由** - 根据查询复杂度动态选择最优模型
- ✅ **性能监控** - 实时统计成功率、缓存命中率、慢查询
- ✅ 数据库连接重试机制

### 4. 多轮对话与上下文理解
- ✅ 保存最近10轮对话历史
- ✅ 支持连续追问、追加条件
- ✅ 上下文压缩
- ✅ 指代消解
- ✅ 智能澄清追问

### 5. 可视化与导出
- ✅ **智能图表推荐** - 根据数据特征自动推荐柱状图/折线图/饼图
- ✅ **Excel导出** - 一键导出查询结果为.xlsx文件
- ✅ 结果表格展示（支持分页）
- ✅ 结果自然语言总结

### 6. Web交互界面
- ✅ **统一入口** - index.html主界面，侧边栏导航
- ✅ 对话式查询页面
- ✅ 实时显示：槽位分析、匹配表、匹配字段、生成SQL、执行结果
- ✅ 手动SQL执行页面
- ✅ 表结构实时查看
- ✅ 执行日志查看
- ✅ 元数据管理
- ✅ **查询模板** - 保存和复用常用查询

### 7. 企业级运维与审计
- ✅ **JSON结构化日志** - 便于ELK采集分析
- ✅ **MDC上下文追踪** - requestId/userId/sessionId全链路追踪
- ✅ 完整操作审计日志
- ✅ 日志筛选与导出
- ✅ **性能监控告警** - 慢查询、失败率实时监控
- ✅ 缓存统计与清除

## 📋 技术栈

- **后端框架**: Spring Boot 2.7.18
- **JDK版本**: Java 21
- **ORM框架**: MyBatis Plus 3.5.5
- **LLM集成**: LangChain4j 0.27.1
- **向量模型**: all-MiniLM-L6-v2 / BGE-M3
- **本地大模型**: Ollama (qwen2.5-coder:7b, qwen3:8b)
- **缓存**: Redis
- **数据库**: MySQL 8.0
- **SQL解析**: JSqlParser 4.6
- **Excel导出**: Apache POI 5.2.5
- **日志框架**: Logback + Logstash Encoder 7.4
- **前端**: HTML5 + CSS3 + JavaScript

## 🛠️ 环境准备

### 1. 安装JDK 21
```bash
# 验证Java版本
java -version
```

### 2. 安装MySQL 8.0
```bash
# 创建数据库并初始化测试数据
mysql -u root -p < complete_ddl.sql
```

### 3. 安装Redis
```bash
# Windows: 下载并启动Redis
# Linux: sudo apt-get install redis-server
# 验证Redis
redis-cli ping
```

### 4. 安装Ollama并运行模型
```bash
# 下载安装Ollama: https://ollama.com/

# 拉取代码模型（SQL生成）
ollama pull qwen2.5-coder:7b

# 拉取推理模型（意图分析、总结）
ollama pull qwen3:8b

# 启动模型服务
ollama serve

# 测试模型
curl http://localhost:11434/api/generate -d '{
  "model": "qwen2.5-coder:7b",
  "prompt": "SELECT * FROM users",
  "stream": false
}'
```

## 🚀 快速启动

### 1. 修改配置文件
编辑 `NL2SQL-web/src/main/resources/application.yml`:
```yaml
spring.datasource.password=你的MySQL密码
```

### 2. 编译项目
```bash
cd "E:\work\idea workspace\NL2SQL"
mvn clean package -DskipTests
```

### 3. 启动应用
```bash
cd NL2SQL-web/target
java -jar NL2SQL-web-1.0.0.jar
```

或者在IDE中直接运行 `NL2SQLApplication.java`

### 4. 访问系统
打开浏览器访问: http://localhost:8080

## 📖 使用示例

### 自然语言查询示例

1. **简单查询**
   ```
   查询所有用户信息
   ```

2. **条件查询**
   ```
   查找北京的用户
   ```

3. **聚合查询**
   ```
   统计每个城市的用户数量
   ```

4. **多表关联**
   ```
   查询张三的所有订单
   ```

5. **时间范围（智能解析）**
   ```
   查询昨天的订单
   查看最近7天的销售数据
   上个月的收入是多少
   ```

6. **同义词识别**
   ```
   查下昨天的定单  （自动识别“定单”=“订单”）
   显示客户列表    （自动识别“客户”=“用户”）
   ```

7. **业务术语**
   ```
   DAU是多少      （自动解析为日活跃用户计算）
   GMV有多少      （自动解析为成交金额汇总）
   ```

8. **排序查询**
   ```
   按金额从高到低显示订单
   ```

9. **导出Excel**
   ```
   查询昨天的订单详情，并导出成excel
   ```

### 分页查询
```json
POST /api/query
{
  "query": "查看所有订单",
  "pageNum": 1,
  "pageSize": 50
}
```

返回结果包含分页信息：
```json
{
  "data": [...],
  "rowCount": 1000,
  "pageNum": 1,
  "pageSize": 50,
  "totalPages": 20,
  "hasNext": true,
  "hasPrevious": false
}
```

### 手动SQL执行

在界面的"手动SQL"标签页中输入SQL:
```sql
SELECT u.username, COUNT(o.id) as order_count 
FROM users u 
LEFT JOIN orders o ON u.id = o.user_id 
GROUP BY u.id 
LIMIT 10;
```

## 🔒 安全特性

### 1. SQL注入防护
- 使用JSqlParser解析和验证SQL
- 白名单机制：仅允许SELECT/SHOW/DESC/EXPLAIN

### 2. 危险操作拦截
```
❌ DROP TABLE users
❌ DELETE FROM orders
❌ UPDATE users SET ...
❌ INSERT INTO ...
```

### 3. 敏感数据脱敏
```
原始数据: phone = "13800138000"
脱敏后:   phone = "***"
```

### 4. 全表扫描防护
```
❌ SELECT * FROM users  (无WHERE且无LIMIT)
✅ SELECT * FROM users WHERE city = '北京'
✅ SELECT * FROM users LIMIT 10
```

### 5. JOIN数量限制
```
✅ 最多允许2张表JOIN
❌ 超过2张表JOIN会被拦截
```

## 📊 监控与审计

### 查看审计日志
```bash
GET http://localhost:8080/api/audit/logs?page=1&size=20
```

### 查看监控统计
```bash
GET http://localhost:8080/api/monitor/stats
```

返回示例:
```json
{
  "code": 200,
  "data": {
    "totalQueries": 150,
    "slowQueries": 5,
    "failedQueries": 3
  }
}
```

## 🏗️ 项目结构

```
NL2SQL/
├── NL2SQL-common/          # 公共模块
│   └── src/main/java/com/nl2sql/common/
│       ├── result/Result.java
│       └── util/LogContextUtil.java  # MDC日志上下文
│
├── NL2SQL-core/            # 核心业务模块
│   └── src/main/java/com/nl2sql/core/
│       ├── metadata/        # 元数据管理
│       ├── retriever/       # 向量检索
│       ├── llm/            # LLM服务
│       │   ├── MultiModelService.java      # 双模型协作
│       │   ├── ModelRouterService.java     # 多模型智能路由
│       │   ├── SynonymService.java         # 同义词词典
│       │   └── TimeExpressionParser.java   # 时间表达式解析
│       ├── rag/            # RAG知识库
│       │   └── RagKnowledgeBaseService.java # RAG检索增强
│       ├── template/       # 查询模板
│       │   └── QueryTemplateService.java   # 模板管理
│       ├── monitor/        # 性能监控
│       │   └── PerformanceMonitorService.java # 监控服务
│       ├── security/       # 安全控制
│       │   └── ColumnPermissionService.java # 列级权限
│       ├── executor/       # SQL执行器
│       │   ├── SQLExecutor.java
│       │   ├── SQLCorrectionService.java   # SQL自动纠错
│       │   ├── PaginationService.java      # 分页服务
│       │   └── ExcelExportService.java     # Excel导出
│       ├── cache/          # 缓存服务
│       │   └── QueryCacheService.java      # 智能缓存层
│       └── visualization/  # 可视化
│           └── ChartRecommendationService.java  # 图表推荐
│
├── NL2SQL-security/        # 安全模块
│   └── src/main/java/com/nl2sql/security/
│       ├── SQLSecurityValidator.java    # SQL安全校验
│       └── ColumnPermissionService.java # 列级权限
│
├── NL2SQL-conversation/    # 对话管理模块
│   └── src/main/java/com/nl2sql/conversation/
│       └── ConversationService.java
│
├── NL2SQL-audit/           # 审计模块
│   └── src/main/java/com/nl2sql/audit/
│       └── AuditService.java
│
├── NL2SQL-web/             # Web模块
│   └── src/main/
│       ├── java/com/nl2sql/web/
│       │   ├── NL2SQLApplication.java
│       │   ├── controller/
│       │   │   ├── NL2SQLController.java
│       │   │   └── AuthController.java
│       │   └── filter/LoggingFilter.java  # 日志过滤器
│       └── resources/
│           ├── application.yml
│           ├── logback-spring.xml         # JSON日志配置
│           └── static/
│               ├── index.html             # 主入口
│               ├── query.html             # 查询页面
│               ├── login.html             # 登录页面
│               └── ...
│
├── complete_ddl.sql         # 完整数据库DDL
├── README.md                # 项目说明
└── QUICKSTART.md            # 快速开始指南
```

## 🔧 API接口文档

### 1. 自然语言查询
```
POST /api/query
Content-Type: application/json

{
  "query": "查询所有用户",
  "sessionId": "session_123",
  "username": "admin"
}
```

### 2. 手动执行SQL
```
POST /api/manual-sql
Content-Type: application/json

{
  "sql": "SELECT * FROM users LIMIT 10"
}
```

### 3. 获取元数据
```
GET /api/metadata
```

### 4. 获取表结构
```
GET /api/metadata/{tableName}
```

### 5. 获取对话历史
```
GET /api/conversation/{sessionId}
```

### 6. 清空对话
```
DELETE /api/conversation/{sessionId}
```

### 7. 获取审计日志
```
GET /api/audit/logs?page=1&size=20
```

### 8. 获取监控统计
```
GET /api/monitor/stats
```

### 9. 清除缓存（管理员）
```
POST /api/cache/clear
Authorization: Bearer {token}
```

### 10. 导出Excel
```
POST /api/export/excel
Authorization: Bearer {token}
Content-Type: application/json

{
  "sql": "SELECT * FROM orders WHERE DATE(created_at) = CURDATE()",
  "fileName": "今日订单"
}
```

### 11. 用户登录
```
POST /api/auth/login
Content-Type: application/json

{
  "username": "admin",
  "password": "admin123"
}
```

返回:
```json
{
  "code": 200,
  "data": {
    "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
    "userId": 1,
    "username": "admin",
    "role": "admin"
  }
}
```

### 12. 保存查询模板
```
POST /api/template/save
Authorization: Bearer {token}
Content-Type: application/json

{
  "name": "每日订单统计",
  "description": "查看当天的订单数量和金额",
  "templateSql": "SELECT COUNT(*) as count, SUM(amount) as total FROM orders WHERE DATE(created_at) = CURDATE()",
  "category": "订单分析",
  "isPublic": true
}
```

### 13. 获取我的模板
```
GET /api/template/my?category=订单分析&page=1&size=20
Authorization: Bearer {token}
```

### 14. 获取公共模板
```
GET /api/template/public?page=1&size=20
```

## ⚙️ 配置说明

### application.yml 主要配置项

```yaml
# 服务器端口
server.port=8080

# MySQL配置
spring.datasource.url=jdbc:mysql://localhost:3306/nl2sql_db
spring.datasource.username=root
spring.datasource.password=你的密码

# Redis配置
spring.data.redis.host=localhost
spring.data.redis.port=6379

# Ollama配置
ollama.base-url=http://localhost:11434
ollama.model=deepseek-r1:8b
```

## 🐛 常见问题

### 1. Ollama连接失败
```bash
# 检查Ollama是否启动
curl http://localhost:11434/api/tags

# 重启Ollama
ollama serve
```

### 2. Redis连接失败
```bash
# 检查Redis是否启动
redis-cli ping

# 启动Redis
redis-server
```

### 3. 数据库连接失败
- 检查MySQL是否启动
- 确认数据库nl2sql_db和nl2sql_auth_db已创建
- 验证用户名密码是否正确
- 执行 `complete_ddl.sql` 初始化表结构

### 4. 向量检索不准确
- 确保表注释和字段注释完整
- 增加训练数据量
- 调整相似度阈值（默认0.25）

### 5. Token认证失败
- 检查请求头是否包含 `Authorization: Bearer {token}`
- 确认Token未过期（默认2小时）
- 重新登录获取新Token

### 6. 白名单限制
- 确认用户ID已在whitelist表中
- 联系管理员添加白名单

## 📝 企业级特性说明

### 1. 智能缓存机制
- **缓存键**: 基于SQL语义的MD5哈希，忽略大小写和空格差异
- **缓存时间**: 默认30分钟，可配置
- **命中率**: 相同查询直接从Redis返回，避免重复LLM调用
- **清除策略**: 管理员可手动清除所有缓存

### 2. SQL自动纠错
- **错误类型**: TABLE_NOT_FOUND, COLUMN_NOT_FOUND, SYNTAX_ERROR等6种
- **重试次数**: 最多3次自动修正
- **修正策略**: 表名转换、字段名转换、语法修复
- **失败处理**: 提供友好建议和可能的修正方案

### 3. 日志追踪
- **JSON格式**: 便于ELK采集和分析
- **MDC上下文**: requestId、userId、sessionId全链路追踪
- **环境区分**: 支持dev/prod不同日志级别
- **性能监控**: 自动记录慢查询和执行时间

### 4. 权限控制
- **表级权限**: 用户只能访问授权的表
- **列级权限**: 细粒度控制字段可见性和脱敏
- **白名单**: 只有白名单用户可使用系统
- **角色管理**: admin/user角色区分

### 5. RAG检索增强
- **相似度检索**: 基于MySQL全文检索查找历史相似问题
- **Few-shot学习**: 自动注入3个最相关的SQL示例到Prompt
- **自动学习**: 成功执行的SQL自动存入知识库
- **质量评分**: 动态调整样本权重，清理低质量数据
- **使用统计**: 记录每个样本的使用次数和效果

### 6. 多模型智能路由
- **复杂度评估**: 根据关键词、聚合函数、JOIN等维度评分
- **动态路由**: SIMPLE/MEDIUM/COMPLEX三级复杂度
- **模型选择**: 简单查询用代码模型（快），复杂查询用推理模型（准）
- **日志追踪**: 记录每次路由决策和原因

## 📝 开发计划

### ✅ 已完成功能
- [x] 自然语言转SQL
- [x] 企业级安全控制（表级/列级权限）
- [x] 多轮对话与上下文理解
- [x] Web交互界面（统一入口）
- [x] 审计日志与监控
- [x] JSON结构化日志
- [x] SQL自动纠错与自愈
- [x] 智能缓存层
- [x] 可视化图表推荐
- [x] Excel导出
- [x] 查询结果分页
- [x] 同义词词典与业务术语
- [x] 时间表达式智能解析
- [x] RAG检索增强（历史问答对）
- [x] 多模型智能路由
- [x] 性能监控与告警
- [x] 查询模板与收藏

### 📋 待实现
- [ ] 支持更多数据库（PostgreSQL、Oracle等）
- [ ] WebSocket实时推送
- [ ] Docker容器化部署
- [ ] API文档（Swagger/OpenAPI）
- [ ] 测试覆盖率提升至80%

## 📄 License

MIT License

## 👥 贡献

欢迎提交Issue和Pull Request！

## 📧 联系方式

如有问题或建议，请提Issue。
