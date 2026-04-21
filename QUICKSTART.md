# NL2SQL 快速启动指南 v1.3.0

## 📋 环境要求

- **JDK**: Java 21+
- **Maven**: 3.9+（Spring Boot 3.x 要求）
- **MySQL**: 8.0+
- **Redis**: 6.0+
- **Ollama**: 最新版本
- **Chroma** (可选): 向量数据库，用于L3语义检索增强
- **IDE**: IntelliJ IDEA (推荐)

---

## 🚀 5分钟快速启动

### 步骤1: 克隆项目
```bash
cd "E:\work\idea workspace\NL2SQL"
```

### 步骤2: 初始化数据库
```bash
# 连接MySQL
mysql -u root -p

# 执行DDL脚本（会自动创建两个数据库）
source complete_ddl.sql
```

**默认管理员账户**:
- 用户名: `admin`
- 密码: `admin123`

### 步骤3: 启动Redis
```bash
# Windows
redis-server

# Linux
sudo systemctl start redis

# 验证
redis-cli ping
# 应返回: PONG
```

### 步骤4: 安装并启动Ollama模型

#### 4.1 安装Ollama
访问 https://ollama.com/ 下载并安装

#### 4.2 拉取模型
```bash
# 代码模型（用于SQL生成）
ollama pull qwen2.5-coder:7b

# 推理模型（用于意图分析、总结）
ollama pull qwen3:8b
```

#### 4.3 启动服务
```bash
ollama serve
```

#### 4.4 测试模型
```bash
curl http://localhost:11434/api/generate -d '{
  "model": "qwen2.5-coder:7b",
  "prompt": "SELECT * FROM users LIMIT 1",
  "stream": false
}'
```

### 步骤5: 配置应用

编辑 `NL2SQL-web/src/main/resources/application.yml`:

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/nl2sql_db?useUnicode=true&characterEncoding=utf8
    username: root
    password: 你的MySQL密码  # ⚠️ 修改这里
  
  data:
    redis:
      host: localhost
      port: 6379

ollama:
  base-url: http://localhost:11434
  code-model: qwen2.5-coder:7b    # SQL生成模型
  reasoning-model: qwen3:8b       # 推理模型
```

### 步骤6: 编译项目
```bash
cd "E:\work\idea workspace\NL2SQL"
mvn clean package -DskipTests
```

### 步骤7: 启动应用

#### 方式1: IDE运行（推荐开发时使用）
```
在IDEA中打开项目
找到 NL2SQL-web/src/main/java/com/nl2sql/web/NL2SQLApplication.java
右键 -> Run 'NL2SQLApplication'
```

#### 方式2: JAR包运行
```bash
cd NL2SQL-web/target
java -jar NL2SQL-web-1.0.0.jar
```

### 步骤8: 访问系统

打开浏览器访问: **http://localhost:8080**

---

## 🔐 首次登录

1. 访问 http://localhost:8080
2. 点击"登录"按钮
3. 输入默认账户:
   - 用户名: `admin`
   - 密码: `admin123`
4. 登录成功后自动加入白名单

---

## 💡 使用示例

### 1. 简单查询
```
查询所有用户信息
```

### 2. 时间表达式
```
查询昨天的订单
查看最近7天的销售数据
上个月的收入是多少
```

### 3. 同义词识别
```
查下昨天的定单      # 自动识别"定单"="订单"
显示客户列表        # 自动识别"客户"="用户"
```

### 4. 业务术语
```
DAU是多少          # 日活跃用户
GMV有多少          # 成交金额总和
转化率怎么样        # 订单完成率
```

### 5. 导出Excel
```
查询昨天的订单详情，并导出成excel
```

### 6. 分页查询
前端界面支持分页控件，或API调用:
```json
POST /api/query
{
  "query": "查看所有订单",
  "pageNum": 1,
  "pageSize": 50
}
```

---

## 🏗️ 项目结构

```
NL2SQL/
├── NL2SQL-common/          # 公共模块
│   ├── result/              # 统一返回结果
│   └── util/                # 工具类（MDC日志等）
│
├── NL2SQL-core/            # 核心业务
│   ├── metadata/            # 元数据管理
│   ├── retriever/           # 向量检索
│   ├── llm/                 # LLM服务
│   │   ├── MultiModelService.java      # 双模型协作
│   │   ├── SynonymService.java         # 同义词词典
│   │   └── TimeExpressionParser.java   # 时间解析
│   ├── executor/            # SQL执行器
│   │   ├── SQLExecutor.java
│   │   ├── SQLCorrectionService.java   # 自动纠错
│   │   ├── PaginationService.java      # 分页
│   │   └── ExcelExportService.java     # Excel导出
│   ├── cache/               # 缓存服务
│   │   └── QueryCacheService.java      # 智能缓存
│   └── visualization/       # 可视化
│       └── ChartRecommendationService.java  # 图表推荐
│
├── NL2SQL-security/        # 安全模块
│   ├── SQLSecurityValidator.java    # SQL校验
│   └── ColumnPermissionService.java # 列级权限
│
├── NL2SQL-conversation/    # 对话管理
├── NL2SQL-audit/           # 审计日志
│
├── NL2SQL-web/             # Web层
│   ├── controller/          # REST API
│   ├── filter/              # 过滤器（日志等）
│   └── resources/static/    # 前端页面
│       ├── index.html       # 主入口
│       ├── login.html       # 登录页
│       ├── query.html       # 查询页
│       └── ...
│
└── complete_ddl.sql         # 完整数据库DDL
```

---

## 🔧 API接口速查

### 认证相关
```bash
# 登录
POST /api/auth/login
{
  "username": "admin",
  "password": "admin123"
}

# 返回token，后续请求需携带:
# Authorization: Bearer {token}
```

### 查询相关
```bash
# 自然语言查询
POST /api/query
Authorization: Bearer {token}
{
  "query": "查询昨天的订单",
  "pageNum": 1,
  "pageSize": 50
}

# 手动执行SQL
POST /api/manual-sql
Authorization: Bearer {token}
{
  "sql": "SELECT * FROM orders LIMIT 10"
}

# 导出Excel
POST /api/export/excel
Authorization: Bearer {token}
{
  "sql": "SELECT * FROM orders",
  "fileName": "订单数据"
}
```

### 管理相关
```bash
# 清除缓存（仅管理员）
POST /api/cache/clear
Authorization: Bearer {token}

# 获取审计日志
GET /api/audit/logs?page=1&size=20
Authorization: Bearer {token}

# 获取监控统计
GET /api/monitor/stats
Authorization: Bearer {token}
```

---

## 🐛 常见问题

### 1. 编译失败
```bash
# 清理Maven缓存
mvn clean

# 重新下载依赖
mvn dependency:purge-local-repository

# 重新编译
mvn clean package -DskipTests
```

### 2. Ollama连接失败
```bash
# 检查Ollama是否运行
curl http://localhost:11434/api/tags

# 查看已安装的模型
ollama list

# 重启Ollama
ollama serve
```

### 3. Redis连接失败
```bash
# Windows检查
redis-cli ping

# Linux检查
sudo systemctl status redis
sudo systemctl start redis
```

### 4. 数据库连接失败
- ✅ 确认MySQL已启动
- ✅ 检查application.yml中的密码
- ✅ 确认已执行complete_ddl.sql
- ✅ 验证两个数据库已创建: nl2sql_db, nl2sql_auth_db

### 5. Token认证失败
- ✅ 检查请求头: `Authorization: Bearer {token}`
- ✅ Token有效期2小时，过期需重新登录
- ✅ 确认用户已在白名单中

### 6. 查询结果为空
- ✅ 检查是否有表权限
- ✅ 确认表中有数据
- ✅ 查看审计日志排查错误

### 7. 向量检索不准确
- ✅ 确保表注释和字段注释完整
- ✅ 增加样本数据量
- ✅ 调整相似度阈值（默认0.25）

---

## 📊 核心功能说明

### 1. 智能缓存
- **机制**: 基于SQL语义的MD5哈希
- **优势**: 相同查询直接返回，避免重复LLM调用
- **TTL**: 默认30分钟
- **清除**: 管理员可手动清除

### 2. SQL自动纠错
- **错误类型**: 表名错误、字段名错误、语法错误等6种
- **重试次数**: 最多3次
- **修正策略**: 大小写转换、单复数转换、常见拼写错误修复

### 3. 日志追踪
- **格式**: JSON结构化日志
- **上下文**: requestId、userId、sessionId全链路追踪
- **采集**: 可直接对接ELK栈

### 4. 权限控制
- **表级**: 用户只能访问授权的表
- **列级**: 细粒度控制字段可见性
- **白名单**: 只有白名单用户可使用系统

---

## 🎯 性能优化建议

### 1. JVM参数调优
```bash
java -Xms2g -Xmx4g -XX:+UseG1GC -jar NL2SQL-web-1.0.0.jar
```

### 2. Redis配置
```yaml
spring:
  data:
    redis:
      lettuce:
        pool:
          max-active: 20
          max-idle: 10
          min-idle: 5
```

### 3. 数据库连接池
```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 20
      minimum-idle: 5
      connection-timeout: 30000
```

---

## 📝 下一步

### 学习路径
1. ✅ 完成快速启动
2. 📖 阅读README.md了解详细功能
3. 🔍 查看API文档尝试更多接口
4. 🛠️ 根据业务需求定制开发

### 进阶功能
- 配置列级权限
- 添加自定义同义词
- 设置查询模板
- 集成告警通知

---

## 📞 技术支持

- 📖 详细文档: README.md
- 🐛 问题反馈: 提交Issue
- 💬 技术交流: 欢迎PR和讨论

---

**祝使用愉快！** 🎉
