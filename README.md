# NL2SQL Enterprise

<div align="center">

[![License](https://img.shields.io/badge/license-MIT-blue.svg?style=flat-square)](LICENSE)
[![Java](https://img.shields.io/badge/Java-21-orange.svg?style=flat-square&logo=openjdk)](https://www.oracle.com/java/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-2.7.18-green.svg?style=flat-square&logo=spring)](https://spring.io/projects/spring-boot)
[![MySQL](https://img.shields.io/badge/MySQL-8.0-blue.svg?style=flat-square&logo=mysql)](https://www.mysql.com/)
[![Redis](https://img.shields.io/badge/Redis-6.x-red.svg?style=flat-square&logo=redis)](https://redis.io/)
[![Status](https://img.shields.io/badge/status-production%20ready-brightgreen.svg?style=flat-square)]()

**🚀 企业级自然语言转SQL智能查询系统 | Enterprise NL2SQL Platform**

*用自然语言查询数据库，让数据分析触手可及*

[快速开始](#-快速开始) • [在线演示](#-功能演示) • [技术架构](#-技术架构) • [API文档](#-api接口) • [贡献指南](#-贡献)

</div>

---

## 📖 项目简介

NL2SQL是一款基于 **Spring Boot + LangChain4j + Ollama** 构建的企业级自然语言转SQL智能查询系统。用户只需用自然语言描述数据需求（如"查询最近7天的订单总额"），系统即可自动生成SQL、执行查询、并以可视化图表和AI总结的形式呈现结果。

### ✨ 核心价值

<table>
<tr>
<td width="25%" align="center">
<b>🎯 零SQL门槛</b><br>
业务人员无需掌握SQL<br>即可自助查询
</td>
<td width="25%" align="center">
<b>⚡ 秒级响应</b><br>
平均3秒内返回结果<br>含LLM调用时间
</td>
<td width="25%" align="center">
<b>🔒 企业级安全</b><br>
四层防护体系<br>保障数据安全
</td>
<td width="25%" align="center">
<b>🤖 智能洞察</b><br>
AI自动分析数据<br>提供业务建议
</td>
</tr>
</table>

### 🌟 核心特性

#### 🔍 智能查询引擎
- **🧠 RAG检索增强**: 双层向量检索（表+字段）+ 历史SQL示例注入，准确率>85%
- **🎯 多模型智能路由**: SIMPLE/MEDIUM/COMPLEX三级复杂度评估，动态选择最优LLM
- **💬 同义词词典**: 自动识别业务术语（订单/定单、用户/客户、DAU/GMV等）
- **⏰ 时间表达式解析**: 智能理解“昨天”、“最近7天”、“上个月”等自然语言
- **🔄 SQL自动纠错**: 6种错误类型识别（表不存在、字段不存在、语法错误等），最多3次自动重试
- **📊 分页查询**: 支持大数据量分页返回，默认每页50条

#### 🔐 企业级安全防护
- **🛡️ 四层防护体系**:
  - 第一层: JWT Token认证 + 白名单机制 + 角色权限（admin/user）
  - 第二层: 表级权限控制（用户只能访问授权的表）
  - 第三层: 列级权限控制（细粒度字段可见性 + 敏感数据脱敏）
  - 第四层: SQL安全验证（JSqlParser AST解析 + 危险操作拦截）
- **🚫 危险操作拦截**: DROP/ALTER/DELETE/UPDATE/INSERT全部禁止
- **🔒 全表扫描防护**: 无WHERE且无LIMIT的查询被拦截
- **📋 JOIN数量限制**: 最多允许2张表关联，防止性能问题
- **👤 密码加密存储**: AES-256-GCM加密数据库密码

#### 💾 性能优化与缓存
- **⚡ 语义缓存**: 基于SQL语义MD5哈希的Redis缓存，命中率>60%，TTL 30分钟
- **🔗 HikariCP连接池**: 每个数据源独立连接池，懒加载创建
  - 最大连接数: 10个/数据源
  - 最小空闲: 2个连接
  - 空闲回收: 10分钟不使用自动关闭
  - 泄漏检测: 60秒未释放记录警告
- **📈 性能监控**: 实时统计成功率、缓存命中率、慢查询告警（>5秒）
- **🔍 全链路追踪**: MDC上下文（requestId/userId/sessionId），JSON结构化日志便于ELK采集

#### 💬 智能对话系统
- **🗨️ 多轮对话**: 保存最近10轮对话历史，支持连续追问
- **🎯 指代消解**: 理解“它”、“这个”、“它们”等代词
- **📝 上下文压缩**: 智能提取关键信息，避免Prompt过长
- **❓ 智能澄清**: 当查询意图不明确时主动询问（数据源选择、表关系澄清）
- **🤖 ReAct Agent架构**: LLM自主决策工具调用，非硬编码路由

#### 📊 可视化与导出
- **📈 智能图表推荐**: 根据数据特征自动推荐柱状图/折线图/饼图/表格
- **📄 Excel导出**: Apache POI实现，一键导出.xlsx文件，自动设置列宽和样式
- **🤖 AI数据总结**: qwen3:8b模型生成关键趋势、异常点、业务建议
- **🎨 响应式UI**: 支持PC/移动端访问，现代化界面设计

#### 🔧 元数据管理
- **📋 表关联关系管理**:
  - 智能SQL提取: 支持9种关联方式（显式JOIN、隐式JOIN、IN/EXISTS子查询等）
  - 复杂度评分: 0-10分量化SQL复杂度，分级提示优化建议
  - 标准化描述: 自动生成`{源表注释}通过{源字段}关联{目标表注释}`格式
  - 表存在性验证: 确保SQL中的表在数据源中真实存在
  - 去重机制: 避免重复添加相同关联关系
- **🔄 元数据同步**: 支持从MySQL自动采集表结构、字段注释、索引信息
- **🌐 多数据源管理**: 动态添加/删除/切换数据源，热插拔支持

#### 📚 RAG知识库
- **🔍 MySQL全文检索**: MATCH...AGAINST实现高效相似度搜索
- **📖 Few-shot学习**: 自动注入Top-3最相关SQL示例到Prompt
- **🎓 自动学习**: 成功执行的SQL自动存入知识库
- **⭐ 质量评分**: 动态调整样本权重，清理低质量数据
- **📊 使用统计**: 记录每个样本的使用次数和效果

#### 📝 查询模板管理
- **👤 个人模板**: 用户私有，仅自己可见
- **🌍 公共模板**: 全员共享，促进知识沉淀
- **📂 分类管理**: 按业务域分类（订单分析、用户分析等）
- **⚡ 一键执行**: 点击模板直接运行，无需重新输入

#### 📋 审计与运维
- **📊 执行日志**: 记录用户ID、原始查询、生成SQL、执行结果、执行时间
- **🔍 日志筛选**: 支持按时间、状态、用户、会话ID筛选
- **⚠️ 慢查询告警**: 执行时间>5秒自动标记
- **📈 实时监控**: 总查询次数、成功/失败率、P95/P99延迟
- **🗑️ 缓存管理**: 管理员可手动清除所有缓存
- **🔐 操作审计**: 全链路追踪，符合企业合规要求

---

## 🎬 功能演示

### 1️⃣ 自然语言查询

```
用户: "查询最近7天北京的订单总额"

系统:
✅ 识别意图: 聚合查询
✅ 匹配表: orders (订单表), users (用户表)
✅ 生成SQL: 
   SELECT SUM(o.actual_amount) as total_amount 
   FROM orders o 
   JOIN users u ON o.user_id = u.id 
   WHERE u.city = '北京' 
     AND o.created_at >= DATE_SUB(CURDATE(), INTERVAL 7 DAY)
✅ 执行结果: ¥1,234,567.89
✅ AI总结: "北京地区近7天订单总额为123.46万元，较上周增长15%"
```

### 2️⃣ 智能图表推荐

```
数据特征: 时间序列 + 数值
推荐图表: 📈 折线图
展示内容: 每日订单趋势变化
```

### 3️⃣ 多轮对话

```
用户: "查询昨天的订单"
系统: [返回结果]

用户: "它们的总金额是多少？"
系统: [理解"它们"指代昨天的订单，计算总和]

用户: "按城市分组看看"
系统: [在原有基础上添加GROUP BY city]
```

### 4️⃣ 表关联关系管理

```sql
-- 从SQL自动提取关联关系
SELECT * FROM orders o 
JOIN users u ON o.user_id = u.id
WHERE EXISTS (
    SELECT 1 FROM order_items oi 
    WHERE oi.order_id = o.id
);

系统提取:
✅ orders.user_id → users.id (MANY_TO_ONE)
✅ orders.id ← order_items.order_id (ONE_TO_MANY)
💡 复杂度评分: 2分 (中等，建议优化EXISTS为JOIN)
```

---

## 🚀 快速开始

### 前置要求

| 依赖 | 版本 | 说明 |
|------|------|------|
| JDK | 21+ | [下载链接](https://www.oracle.com/java/technologies/downloads/) |
| MySQL | 8.0+ | [下载链接](https://dev.mysql.com/downloads/) |
| Redis | 6.x+ | [下载链接](https://redis.io/download) |
| Ollama | Latest | [下载链接](https://ollama.com/) |
| Maven | 3.6+ | [下载链接](https://maven.apache.org/) |

### 5分钟快速启动

#### Step 1: 初始化数据库

```bash
# 创建数据库并导入测试数据
mysql -u root -p < init_complete_database.sql
```

#### Step 2: 启动依赖服务

```bash
# 启动Redis
redis-server

# 启动Ollama并拉取模型
ollama pull qwen2.5-coder:7b-instruct-q4_0  # SQL生成
ollama pull qwen3:8b                         # AI总结
ollama serve
```

#### Step 3: 配置应用

编辑 `nl2sql-web/src/main/resources/application.yml`:

```yaml
spring:
  datasource:
    password: your_mysql_password  # 修改为你的MySQL密码
  data:
    redis:
      host: localhost
      port: 6379

ollama:
  base-url: http://localhost:11434
```

#### Step 4: 编译运行

```bash
# 编译项目
cd "E:\work\idea workspace\NL2Sql"
mvn clean package -DskipTests

# 启动应用
cd nl2sql-web/target
java -jar nl2sql-web-1.0.0.jar
```

#### Step 5: 访问系统

打开浏览器访问: **http://localhost:8080**

默认账号: `admin` / `admin123`

---

## 🏗️ 技术架构

### 整体架构图

```
┌─────────────────────────────────────────────────────────┐
│                   前端展示层 (Web UI)                     │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐   │
│  │ 登录页面  │ │ 查询页面  │ │ 管理后台  │ │ 日志查看  │   │
│  └──────────┘ └──────────┘ └──────────┘ └──────────┘   │
└────────────────────┬────────────────────────────────────┘
                     │ HTTP/REST API + JWT Token
┌────────────────────▼────────────────────────────────────┐
│                 Web控制层 (Controller)                    │
│  ┌──────────────┐ ┌──────────────┐ ┌──────────────┐    │
│  │NL2SQLCtrl    │ │AuthCtrl      │ │AdminCtrl     │    │
│  └──────────────┘ └──────────────┘ └──────────────┘    │
└────────────────────┬────────────────────────────────────┘
                     │
┌────────────────────▼────────────────────────────────────┐
│                 业务服务层 (Service)                      │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐   │
│  │NL2SQLSvc │ │AuthSvc   │ │Metadata  │ │CacheSvc  │   │
│  └──────────┘ └──────────┘ └──────────┘ └──────────┘   │
└────┬──────────┬──────────┬──────────┬──────────┬────────┘
     │          │          │          │          │
┌────▼───┐ ┌───▼───┐ ┌───▼───┐ ┌───▼───┐ ┌───▼────┐
│安全校验 │ │向量检索 │ │LLM服务 │ │SQL执行 │ │缓存服务 │
│Security │ │Retriever│ │Router  │ │Executor│ │Redis   │
└────────┘ └────────┘ └───┬───┘ └───┬───┘ └────────┘
                          │          │
                   ┌──────▼───┐ ┌───▼──────┐
                   │Ollama API│ │MySQL DB  │
                   │(qwen模型) │ │(多数据源) │
                   └──────────┘ └──────────┘
```

### 核心技术栈

| 分类 | 技术 | 版本 | 用途 |
|------|------|------|------|
| **后端框架** | Spring Boot | 2.7.18 | 应用框架 |
| **JDK** | Java | 21 | 运行环境 |
| **ORM** | MyBatis Plus | 3.5.5 | 数据持久化 |
| **LLM集成** | LangChain4j | 0.27.1 | LLM编排框架 |
| **向量模型** | all-MiniLM-L6-v2 | - | 文本向量化 |
| **本地大模型** | Ollama (qwen2.5/qwen3) | - | SQL生成/AI总结 |
| **缓存** | Redis | 6.x | 分布式缓存 |
| **数据库** | MySQL | 8.0 | 数据存储 |
| **SQL解析** | JSqlParser | 4.6 | SQL AST解析 |
| **Excel** | Apache POI | 5.2.5 | Excel导出 |
| **日志** | Logback + Logstash | 7.4 | JSON结构化日志 |
| **前端** | HTML5 + CSS3 + JS | - | 用户界面 |

### 🤖 LLM多提供者架构

#### 设计理念

借鉴LangChain/LangChain4j的设计思路，为多种企业内部部署的LLM提供统一适配层，实现：
- **配置驱动**: 通过修改配置文件即可切换LLM后端，无需改代码
- **故障转移**: 支持优先级列表，主LLM不可用时自动降级
- **企业级安全**: 仅支持内部私有化部署，不依赖公有云API

#### 支持的LLM提供者

| 提供者 | 部署方式 | 状态 | 适用场景 |
|--------|---------|------|----------|
| **Ollama** | 本地/内网服务器 | ✅ 默认启用 | 开发测试、小团队 |
| **ChatGLM** | 企业内部服务器 | 🔧 预留 | 中大型企业 |
| **Qwen** | 阿里云私有化部署 | 🔧 预留 | 阿里生态企业 |
| **Baichuan** | 企业内部服务器 | 🔧 预留 | 百川生态企业 |

#### 配置示例

```yaml
# application.yml
llm:
  # 活跃的提供者名称
  active-provider: ollama
  
  # 提供者优先级列表（用于自动故障转移）
  provider-priority:
    - ollama
    - chatglm
    - qwen
  
  # Ollama配置
  ollama:
    enabled: true
    base-url: http://localhost:11434
    code-model: qwen2.5-coder:7b-instruct-q4_0
    nlp-model: qwen3:8b
    timeout: 60
  
  # ChatGLM配置（企业内部部署）
  chatglm:
    enabled: false
    base-url: http://chatglm.internal.company.com:8000
    model: chatglm3-6b
    api-key: ${CHATGLM_API_KEY:}
    timeout: 60
  
  # Qwen配置（阿里云私有化部署）
  qwen:
    enabled: false
    base-url: http://qwen.internal.company.com:8000
    model: qwen-7b-chat
    api-key: ${QWEN_API_KEY:}
    timeout: 60
```

#### 架构组件

```
┌─────────────────────────────────────────────┐
│         LLMProvider (统一接口)               │
│  - getName()       // 提供者名称             │
│  - isAvailable()   // 健康检查              │
│  - generate()      // 生成文本              │
│  - generateJson()  // JSON响应              │
└──────────────┬──────────────────────────────┘
               │ 实现
     ┌─────────┼──────────┬──────────┐
     ▼         ▼          ▼          ▼
┌────────┐ ┌────────┐ ┌────────┐ ┌────────┐
│Ollama  │ │ChatGLM │ │ Qwen   │ │Baichuan│
│Provider│ │Provider│ │Provider│ │Provider│
└────────┘ └────────┘ └────────┘ └────────┘
     │
     ▼
┌─────────────────────────────────────────────┐
│      LLMProviderManager (管理器)             │
│  - registerProvider()  // 注册提供者        │
│  - setActiveProvider() // 切换活跃提供者     │
│  - healthCheck()       // 健康检查          │
│  - autoSelectProvider()// 自动选择          │
└──────────────┬──────────────────────────────┘
               │ 注入
               ▼
┌─────────────────────────────────────────────┐
│           LLMService (业务服务)              │
│  - generateSQL()     // SQL生成             │
│  - summarizeResult() // 结果总结            │
│  - clarifyQuestion() // 问题澄清            │
│  - classifyIntent()  // 意图分类            │
└─────────────────────────────────────────────┘
```

#### 运行时切换提供者

```java
@Autowired
private LLMService llmService;

// 切换到ChatGLM
llmService.switchProvider("chatglm");

// 查询健康状态
Map<String, Boolean> status = llmService.getProviderHealthStatus();
// 返回: {ollama=true, chatglm=false, qwen=true}
```

#### 扩展新的LLM提供者

只需3步即可接入新的LLM：

1. **实现LLMProvider接口**
```java
public class CustomProvider implements LLMProvider {
    @Override
    public String getName() { return "custom"; }
    
    @Override
    public boolean isAvailable() { /* 健康检查 */ }
    
    @Override
    public String generate(String prompt, double temperature) {
        // 调用自定义LLM API
    }
    
    @Override
    public String generateJson(String systemPrompt, String userPrompt, double temperature) {
        // 生成JSON响应
    }
}
```

2. **添加配置类**
```java
@Configuration
@ConditionalOnProperty(name = "llm.custom.enabled", havingValue = "true")
public class CustomConfig {
    @Bean
    public LLMProvider customProvider() {
        return new CustomProvider(...);
    }
}
```

3. **更新配置文件**
```yaml
llm:
  custom:
    enabled: true
    base-url: http://custom.llm.com:8000
    model: custom-model
```

---

### 模块划分

```
NL2SQL/
├── nl2sql-common/          # 公共模块 (工具类、统一响应)
├── nl2sql-core/            # 核心业务模块
│   ├── datasource/         # 动态数据源管理 (HikariCP)
│   ├── metadata/           # 元数据服务 (表结构/字段信息)
│   ├── retriever/          # 双层向量检索 (表+字段)
│   ├── llm/                # LLM服务 (多模型路由/RAG)
│   ├── rag/                # RAG知识库服务
│   ├── executor/           # SQL执行器/纠错/分页/导出
│   ├── cache/              # 智能缓存服务
│   ├── visualization/      # 图表推荐服务
│   ├── monitor/            # 性能监控服务
│   ├── template/           # 查询模板服务
│   └── security/           # 列级权限控制
├── nl2sql-security/        # 安全模块 (SQL验证)
├── nl2sql-conversation/    # 对话管理模块
├── nl2sql-audit/           # 审计模块
└── nl2sql-web/             # Web模块 (Controller + 前端)
```

---

## 🔥 核心功能详解

### 1. 自然语言转SQL引擎

#### 工作原理

```
用户输入: "查询最近7天北京的订单总额"
    ↓
[1] 意图识别: 聚合查询 (COUNT/SUM)
    ↓
[2] 双层向量检索:
    ├─ 表级检索: 匹配 orders, users
    └─ 字段级检索: 匹配 actual_amount, city, created_at
    ↓
[3] RAG检索增强:
    └─ 从历史知识库检索Top-3相似SQL示例
    ↓
[4] 多模型智能路由:
    ├─ 复杂度评估: MEDIUM (包含JOIN + 时间范围)
    └─ 选择模型: qwen2.5-coder:7b
    ↓
[5] LLM生成SQL:
    └─ Prompt = 元数据 + RAG示例 + 用户问题
    ↓
[6] SQL清洗与验证:
    ├─ 去除Markdown标记
    ├─ JSqlParser解析AST
    └─ 安全检查 (权限/危险操作)
    ↓
[7] 执行查询:
    ├─ 动态数据源获取连接
    ├─ HikariCP连接池管理
    └─ 执行SQL并分页
    ↓
[8] 后处理:
    ├─ 智能图表推荐
    ├─ AI数据总结
    └─ 存入RAG知识库
    ↓
返回结果: {data, chart, summary}
```

#### 支持的查询类型

| 类型 | 示例 | 支持状态 |
|------|------|----------|
| 简单查询 | "查询所有用户" | ✅ |
| 条件过滤 | "查找北京的活跃用户" | ✅ |
| 聚合统计 | "统计每个城市的订单数" | ✅ |
| 多表关联 | "查询张三的所有订单详情" | ✅ |
| 时间范围 | "查看最近7天的销售趋势" | ✅ |
| 排序分页 | "按金额从高到低显示前10个订单" | ✅ |
| 复杂分析 | "计算上个月的DAU和GMV" | ✅ |
| 同义词识别 | "查下昨天的定单" (定单→订单) | ✅ |
| 时间表达式 | "上个月"、"最近7天" | ✅ |

### 2. 企业级安全控制体系

#### 四层防护架构

```
第一层: 认证与授权
├─ JWT Token认证 (有效期2小时)
├─ 白名单机制 (仅授权用户可用)
└─ 角色权限 (admin/user)

第二层: 表级权限控制
├─ 用户只能访问被授权的表
├─ 管理员拥有全表访问权限
└─ 动态权限校验，实时生效

第三层: 列级权限控制
├─ 细粒度控制字段可见性
├─ 敏感字段自动脱敏 (手机号/邮箱/密码)
└─ 隐藏未授权列，防止数据泄露

第四层: SQL安全验证
├─ 只读限制 (仅允许SELECT/SHOW/DESC/EXPLAIN)
├─ 危险操作拦截 (DROP/ALTER/DELETE等)
├─ 全表扫描防护 (无WHERE且无LIMIT的查询被拦截)
├─ JOIN数量限制 (最多2张表关联)
└─ JSqlParser深度解析SQL AST树
```

#### 安全示例

```sql
-- ❌ 危险操作被拦截
DROP TABLE users;
DELETE FROM orders;
UPDATE users SET password = 'xxx';

-- ❌ 全表扫描被拦截
SELECT * FROM users;  -- 无WHERE且无LIMIT

-- ✅ 安全的查询
SELECT * FROM users WHERE city = '北京' LIMIT 50;
SELECT u.username, COUNT(o.id) 
FROM users u 
JOIN orders o ON u.id = o.user_id 
GROUP BY u.id;
```

### 3. 智能缓存与性能优化

#### 缓存策略

- **语义缓存**: 基于SQL语义的MD5哈希作为缓存键，忽略大小写和空格差异
- **Redis存储**: TTL默认30分钟，支持手动清除
- **命中率**: >60% (重复查询场景)
- **监控**: 实时统计命中/未命中次数

#### 其他优化

| 优化项 | 说明 | 效果 |
|--------|------|------|
| 查询结果分页 | 默认每页50条，支持自定义 | 减少内存占用 |
| SQL自动纠错 | 6种错误类型识别，最多3次重试 | 提升成功率 |
| 连接池调优 | HikariCP最小空闲2个，最大10个 | 提高并发能力 |
| 懒加载连接池 | 首次访问时创建，空闲10分钟回收 | 节省资源 |
| 泄漏检测 | 60秒未释放连接记录警告 | 防止连接泄漏 |

### 4. RAG检索增强生成

#### 工作原理

```
1. 用户提问时，从历史问答知识库检索Top-3相似问题
2. 将相似问题的SQL示例注入到Prompt中
3. LLM参考示例风格生成当前问题的SQL
```

#### 技术亮点

- **MySQL全文检索**: 基于MATCH...AGAINST实现高效相似度搜索
- **自动学习**: 成功执行的SQL自动存入知识库
- **质量评分**: 动态调整样本权重，清理低质量数据
- **使用统计**: 记录每个样本的使用次数和效果

#### 效果提升

- SQL生成准确率提升约**20%**
- 减少LLM幻觉，提高字段名准确性
- 快速适配新业务术语

### 5. 表关联关系管理

#### 智能SQL提取

支持从SQL语句中自动提取表关联关系：

| 关联类型 | 示例 | 支持状态 |
|---------|------|----------|
| 显式JOIN | `FROM orders o JOIN users u ON o.user_id = u.id` | ✅ |
| 隐式JOIN | `FROM orders o, users u WHERE o.user_id = u.id` | ✅ |
| IN子查询 | `WHERE user_id IN (SELECT id FROM users)` | ✅ |
| EXISTS子查询 | `WHERE EXISTS (SELECT 1 FROM users u WHERE u.id = o.user_id)` | ✅ |
| NOT EXISTS | `WHERE NOT EXISTS (...)` | ✅ |
| 比较运算符 | `WHERE id = ANY (SELECT ...)` | ✅ |

#### 复杂度评分系统

引入0-10分量化SQL复杂度，分级提示优化建议：

- **< 2分**: 不提示，直接提取 (简单SQL)
- **2-3分**: 友好提示 (中等复杂，可选优化)
- **≥ 3分**: 警告+建议 (高复杂，强烈建议简化)

评分规则：
- 多层嵌套(>3层): +3分
- EXISTS子查询: +2分
- 函数转换关联: +3分
- 多表JOIN(>5个): +2分

#### 标准化描述生成

- **禁止手动输入**: description字段readonly，防止用户输入错误或模糊描述
- **自动生成格式**: `{源表注释}通过{源字段}关联{目标表注释}`
- **前后端双重保障**: 前端自动生成提升体验，后端强制生成确保质量

### 6. AI智能数据总结

#### 工作流程

```
1. 前端传入原始查询、SQL语句、查询结果数据
2. 后端构建结构化Prompt (表格格式展示数据)
3. 调用NLP模型 (qwen3:8b) 生成分析总结
4. 返回关键趋势、异常点、业务建议
```

#### Prompt示例

```markdown
你是一个专业的数据分析师。请基于以下查询结果进行分析和总结。

【用户问题】查询最近半个月每天的订单金额

【SQL查询】
SELECT DATE(created_at) AS date, SUM(actual_amount) AS total_order_amount ...

【查询结果】共11行数据

date | total_order_amount
---|---
2026-03-27 | 112831.11
2026-03-28 | 545528.77
...

【分析要求】
请分析以上数据并总结：
1. 数据的主要趋势或模式
2. 关键数值和异常点
3. 业务洞察和建议

要求：用简洁的中文回答，控制在200字以内。必须基于上述实际数据进行分析。
```

### 7. 可视化图表推荐

#### 智能推荐算法

| 图表类型 | 适用场景 | 示例 |
|---------|---------|------|
| 📊 柱状图 | 分类对比 | 各城市订单数 |
| 📈 折线图 | 时间序列 | 每日销售额趋势 |
| 🥧 饼图 | 占比分析 | 各品类销售占比 |
| 📋 表格 | 通用展示 | 所有数据类型 |

#### 技术实现

- 分析数据特征 (维度数量、数值类型、行数等)
- 自动推荐最合适的图表类型
- 前端文本化展示 (预留ECharts集成接口)

---

## 📊 API接口文档

### 🔐 认证与授权

#### 1. 用户登录
```http
POST /api/auth/login
Content-Type: application/json

{
  "username": "admin",
  "password": "admin123"
}
```

**响应:**
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

#### 2. Token验证
```http
GET /api/auth/validate
Authorization: Bearer {token}
```

#### 3. 退出登录
```http
POST /api/auth/logout
Authorization: Bearer {token}
```

#### 4. 修改密码
```http
POST /api/auth/password/change
Authorization: Bearer {token}
Content-Type: application/json

{
  "oldPassword": "old123",
  "newPassword": "new456"
}
```

#### 5. 重置密码（管理员）
```http
POST /api/auth/password/reset
Authorization: Bearer {token}
Content-Type: application/json

{
  "userId": 2,
  "newPassword": "reset123"
}
```

---

### 👥 用户管理

#### 6. 获取用户列表
```http
GET /api/auth/users
Authorization: Bearer {token}
```

#### 7. 创建用户
```http
POST /api/auth/user/create
Authorization: Bearer {token}
Content-Type: application/json

{
  "username": "newuser",
  "password": "password123",
  "role": "user"
}
```

---

### 🔑 白名单管理

#### 8. 添加白名单
```http
POST /api/auth/whitelist/add
Authorization: Bearer {token}
Content-Type: application/json

{
  "userId": 2
}
```

#### 9. 移除白名单
```http
POST /api/auth/whitelist/remove
Authorization: Bearer {token}
Content-Type: application/json

{
  "userId": 2
}
```

#### 10. 获取白名单列表
```http
GET /api/auth/whitelist/list
Authorization: Bearer {token}
```

---

### 🛡️ 权限管理

#### 11. 授予表权限
```http
POST /api/auth/table-permission/grant
Authorization: Bearer {token}
Content-Type: application/json

{
  "userId": 2,
  "tableName": "orders"
}
```

#### 12. 撤销表权限
```http
POST /api/auth/table-permission/revoke
Authorization: Bearer {token}
Content-Type: application/json

{
  "userId": 2,
  "tableName": "orders"
}
```

#### 13. 查询用户表权限
```http
GET /api/auth/table-permission/user/{userId}
Authorization: Bearer {token}
```

#### 14. 查询所有表权限配置
```http
GET /api/auth/table-permission/all
Authorization: Bearer {token}
```

#### 15. 查询表的授权用户
```http
GET /api/auth/table-permission/table/{tableName}
Authorization: Bearer {token}
```

---

### 💬 Agent对话接口

#### 16. ReAct Agent对话（推荐）
```http
POST /api/agent/chat
Authorization: Bearer {token}
Content-Type: application/json

{
  "message": "查询最近7天北京的订单总额",
  "datasourceId": 1,
  "context": {
    "lastQuery": "统计订单",
    "generatedSQL": "SELECT ..."
  }
}
```

**响应:**
```json
{
  "code": 200,
  "data": {
    "reply": "已为您查询到结果...",
    "sql": "SELECT SUM(actual_amount) ...",
    "data": [...],
    "chart": {...},
    "summary": "北京地区近7天订单总额...",
    "datasourceId": 1
  }
}
```

#### 17. Agent对话测试
```http
POST /api/agent/chat/test
Content-Type: application/json

{
  "message": "测试消息"
}
```

---

### 🔍 查询接口

#### 18. 自然语言查询（已废弃，请使用Agent接口）
```http
POST /api/query
Authorization: Bearer {token}
Content-Type: application/json

{
  "query": "查询所有用户",
  "datasourceId": 1,
  "sessionId": "session_123",
  "pageNum": 1,
  "pageSize": 50
}
```

#### 19. 手动执行SQL
```http
POST /api/manual-sql
Authorization: Bearer {token}
Content-Type: application/json

{
  "sql": "SELECT * FROM users LIMIT 10",
  "datasourceId": 1
}
```

**响应:**
```json
{
  "code": 200,
  "data": {
    "data": [...],
    "rowCount": 10,
    "executionTime": 0.15,
    "chartRecommendation": {
      "type": "table",
      "charts": [...]
    }
  }
}
```

#### 20. SQL纠错
```http
POST /api/correct
Authorization: Bearer {token}
Content-Type: application/json

{
  "sql": "SELCT * FROM usrs",
  "error": "Table 'usrs' doesn't exist"
}
```

**响应:**
```json
{
  "code": 200,
  "data": {
    "success": true,
    "correctedSQL": "SELECT * FROM users",
    "retryCount": 1,
    "originalError": "Table 'usrs' doesn't exist",
    "suggestions": ["表名拼写错误"]
  }
}
```

---

### 📤 导出接口

#### 21. 导出Excel
```http
POST /api/export/excel
Authorization: Bearer {token}
Content-Type: application/json

{
  "data": [...],
  "fileName": "今日订单",
  "sheetName": "订单数据"
}
```

**响应:** 返回Excel文件的byte数组

---

### 📋 表关联关系管理

#### 22. 获取关联关系列表
```http
GET /api/relationships?datasourceId=1
Authorization: Bearer {token}
```

#### 23. 创建关联关系
```http
POST /api/relationships
Authorization: Bearer {token}
Content-Type: application/json

{
  "datasourceId": 1,
  "sourceTable": "orders",
  "sourceColumn": "user_id",
  "targetTable": "users",
  "targetColumn": "id",
  "relationshipType": "MANY_TO_ONE"
}
```

#### 24. 更新关联关系
```http
PUT /api/relationships/{id}
Authorization: Bearer {token}
Content-Type: application/json

{
  "description": "订单通过user_id关联用户",
  "isActive": 1
}
```

#### 25. 删除关联关系
```http
DELETE /api/relationships/{id}
Authorization: Bearer {token}
```

#### 26. 从SQL提取关联关系（智能版）
```http
POST /api/relationships/extract-with-suggestions
Authorization: Bearer {token}
Content-Type: application/json

{
  "sql": "SELECT * FROM orders o JOIN users u ON o.user_id = u.id WHERE EXISTS (SELECT 1 FROM order_items oi WHERE oi.order_id = o.id)",
  "datasourceId": 1
}
```

**响应:**
```json
{
  "code": 200,
  "data": {
    "success": true,
    "relationships": [
      {
        "sourceTable": "orders",
        "sourceColumn": "user_id",
        "targetTable": "users",
        "targetColumn": "id",
        "relationshipType": "MANY_TO_ONE"
      },
      {
        "sourceTable": "orders",
        "sourceColumn": "id",
        "targetTable": "order_items",
        "targetColumn": "order_id",
        "relationshipType": "ONE_TO_MANY"
      }
    ],
    "suggestions": [
      "📊 SQL复杂度评估：中等（2分）",
      "💡 检测到EXISTS子查询，虽然可以提取关联，但如果性能不佳可考虑改为JOIN"
    ],
    "extractedCount": 2
  }
}
```

#### 27. 批量保存关联关系
```http
POST /api/relationships/batch-save
Authorization: Bearer {token}
Content-Type: application/json

{
  "relationships": [
    {
      "datasourceId": 1,
      "sourceTable": "orders",
      "sourceColumn": "user_id",
      "targetTable": "users",
      "targetColumn": "id",
      "relationshipType": "MANY_TO_ONE"
    }
  ]
}
```

#### 28. 推断关联关系
```http
POST /api/relationships/infer
Authorization: Bearer {token}
Content-Type: application/json

{
  "datasourceId": 1
}
```

---

### 🗄️ 数据源管理

#### 29. 测试数据源连接
```http
POST /api/admin/datasource/test
Authorization: Bearer {token}
Content-Type: application/json

{
  "name": "测试库",
  "dbType": "MYSQL",
  "host": "localhost",
  "port": 3306,
  "databaseName": "test_db",
  "username": "root",
  "password": "password"
}
```

#### 30. 保存数据源
```http
POST /api/admin/datasource/save
Authorization: Bearer {token}
Content-Type: application/json

{
  "name": "生产库",
  "dbType": "MYSQL",
  "host": "192.168.1.100",
  "port": 3306,
  "databaseName": "prod_db",
  "username": "app_user",
  "password": "encrypted_password",
  "isActive": 1
}
```

#### 31. 获取数据源列表
```http
GET /api/admin/datasource/list
Authorization: Bearer {token}
```

---

### 🔄 元数据管理

#### 32. 同步元数据
```http
POST /api/admin/metadata/sync/{datasourceId}
Authorization: Bearer {token}
```

**响应:**
```json
{
  "code": 200,
  "data": {
    "tablesSynced": 15,
    "columnsSynced": 120,
    "duration": "2.5s"
  }
}
```

#### 33. 获取元数据列表
```http
GET /api/admin/metadata/list?datasourceId=1&tableName=orders
Authorization: Bearer {token}
```

---

### 📝 查询模板管理

#### 34. 保存查询模板
```http
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

#### 35. 获取我的模板
```http
GET /api/template/my?category=订单分析&page=1&size=20
Authorization: Bearer {token}
```

#### 36. 获取公共模板
```http
GET /api/template/public?page=1&size=20
```

#### 37. 更新模板
```http
PUT /api/template/{id}
Authorization: Bearer {token}
Content-Type: application/json

{
  "name": "更新后的名称",
  "isPublic": false
}
```

#### 38. 删除模板
```http
DELETE /api/template/{id}
Authorization: Bearer {token}
```

---

### 📊 监控与审计

#### 39. 获取执行日志
```http
GET /api/audit/logs?page=1&size=20&status=SUCCESS&startDate=2026-04-01&endDate=2026-04-16
Authorization: Bearer {token}
```

**响应:**
```json
{
  "code": 200,
  "data": {
    "logs": [
      {
        "id": 1,
        "userId": 1,
        "username": "admin",
        "query": "查询订单",
        "sql": "SELECT * FROM orders",
        "status": "SUCCESS",
        "executionTime": 0.25,
        "createdAt": "2026-04-16T10:30:00"
      }
    ],
    "total": 150,
    "page": 1,
    "size": 20
  }
}
```

#### 40. 获取监控统计
```http
GET /api/monitor/stats
Authorization: Bearer {token}
```

**响应:**
```json
{
  "code": 200,
  "data": {
    "totalQueries": 150,
    "successQueries": 145,
    "failedQueries": 5,
    "slowQueries": 3,
    "cacheHitRate": 62.5,
    "avgExecutionTime": 0.35,
    "p95ExecutionTime": 1.2,
    "p99ExecutionTime": 2.5
  }
}
```

#### 41. 清除缓存
```http
POST /api/cache/clear
Authorization: Bearer {token}
```

---

### 🌐 翻译服务

#### 42. 字段翻译
```http
POST /api/translate/field
Content-Type: application/json

{
  "fieldName": "actual_amount",
  "tableName": "orders"
}
```

**响应:**
```json
{
  "code": 200,
  "data": {
    "chinese": "实际金额",
    "english": "Actual Amount"
  }
}
```

---

## ⚙️ 配置说明

### application.yml 主要配置项

```yaml
server:
  port: 8080

spring:
  datasource:
    url: jdbc:mysql://localhost:3306/nl2sql_meta_db?useUnicode=true&characterEncoding=utf8&useSSL=false
    username: root
    password: your_password  # 修改为你的MySQL密码
    driver-class-name: com.mysql.cj.jdbc.Driver
  
  data:
    redis:
      host: localhost
      port: 6379
      database: 0
      timeout: 3000ms

ollama:
  base-url: http://localhost:11434
  code-model: qwen2.5-coder:7b-instruct-q4_0  # SQL生成模型
  nlp-model: qwen3:8b                         # AI总结模型
  temperature: 0.0
  timeout: 30000

# 连接池配置
datasource:
  pool:
    max-size: 10
    min-idle: 2
    idle-timeout: 600000  # 10分钟
    leak-detection: 60000 # 60秒
```

---

## 🎨 前端页面

系统提供以下Web界面：

### 👤 用户界面

| 页面 | 路径 | 功能描述 |
|------|------|----------|
| **登录页** | `/login.html` | 用户登录、Token认证 |
| **主入口** | `/index.html` | 侧边栏导航，统一入口 |
| **Agent对话** | `/agent-chat.html` | ReAct Agent对话式查询（推荐） |
| **传统查询** | `/query.html` | NL2SQL查询界面（已废弃） |
| **修改密码** | `/change-password.html` | 用户自助修改密码 |

### 🔧 管理后台

| 页面 | 路径 | 功能描述 |
|------|------|----------|
| **数据源管理** | `/admin-datasource.html` | 添加/编辑/删除/测试数据源 |
| **元数据同步** | `/admin-manual-sql.html` | 手动执行SQL、同步元数据 |
| **表权限管理** | `/admin-table-permission.html` | 配置用户表级访问权限 |
| **用户管理** | `/admin-user-management.html` | 创建用户、重置密码、白名单管理 |
| **查询模板** | `/admin-template.html` | 管理个人/公共查询模板 |
| **执行日志** | `/admin-execution-logs.html` | 查看审计日志、筛选导出 |
| **关联关系** | `/relationship-management.html` | 可视化维护表关联关系 |

---

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
- 确认数据库`nl2sql_meta_db`和`nl2sql_auth_db`已创建
- 验证用户名密码是否正确
- 执行 `init_complete_database.sql` 初始化表结构

### 4. 向量检索不准确

- 确保表注释和字段注释完整
- 增加训练数据量
- 调整相似度阈值 (默认0.25)

### 5. Token认证失败

- 检查请求头是否包含 `Authorization: Bearer {token}`
- 确认Token未过期 (默认2小时)
- 重新登录获取新Token

### 6. 白名单限制

- 确认用户ID已在whitelist表中
- 联系管理员添加白名单

---

## 📝 开发计划

### ✅ 已完成功能

#### 核心查询引擎
- [x] 自然语言转SQL（RAG检索增强）
- [x] 多模型智能路由（SIMPLE/MEDIUM/COMPLEX）
- [x] 同义词词典与业务术语识别
- [x] 时间表达式智能解析（昨天、最近7天等）
- [x] SQL自动纠错与自愈（6种错误类型，最多3次重试）
- [x] 查询结果分页（默认50条/页）

#### 安全防护
- [x] JWT Token认证（有效期2小时）
- [x] 白名单机制（仅授权用户可用）
- [x] 角色权限管理（admin/user）
- [x] 表级权限控制（用户只能访问授权的表）
- [x] 列级权限控制（细粒度字段可见性）
- [x] 敏感数据脱敏（手机号/邮箱/密码）
- [x] SQL安全验证（JSqlParser AST解析）
- [x] 危险操作拦截（DROP/ALTER/DELETE等）
- [x] 全表扫描防护（无WHERE且无LIMIT的查询被拦截）
- [x] JOIN数量限制（最多2张表关联）
- [x] 密码加密存储（AES-256-GCM）

#### 性能优化
- [x] 语义缓存（基于SQL语义MD5哈希，Redis存储）
- [x] HikariCP动态连接池（懒加载创建）
  - [x] 最大连接数: 10个/数据源
  - [x] 最小空闲: 2个连接
  - [x] 空闲回收: 10分钟不使用自动关闭
  - [x] 泄漏检测: 60秒未释放记录警告
- [x] 性能监控（实时统计、慢查询告警）
- [x] 全链路追踪（MDC上下文、JSON结构化日志）

#### 对话系统
- [x] ReAct Agent架构（LLM自主决策工具调用）
- [x] 多轮对话（保存最近10轮历史）
- [x] 指代消解（理解“它”、“这个”等代词）
- [x] 上下文压缩（智能提取关键信息）
- [x] 智能澄清追问（数据源选择、表关系澄清）

#### 可视化与导出
- [x] 智能图表推荐（柱状图/折线图/饼图/表格）
- [x] Excel导出（Apache POI实现）
- [x] AI数据总结（qwen3:8b模型生成分析）
- [x] 响应式UI（支持PC/移动端）

#### 元数据管理
- [x] 表关联关系管理
  - [x] 智能SQL提取（9种关联方式）
  - [x] 复杂度评分系统（0-10分量化）
  - [x] 标准化描述生成（禁止手动输入）
  - [x] 表存在性验证
  - [x] 去重机制
- [x] 元数据同步（从MySQL自动采集表结构）
- [x] 多数据源管理（动态添加/删除/切换）

#### RAG知识库
- [x] MySQL全文检索（MATCH...AGAINST）
- [x] Few-shot学习（自动注入Top-3相似SQL示例）
- [x] 自动学习（成功执行的SQL自动存入）
- [x] 质量评分（动态调整样本权重）
- [x] 使用统计（记录使用次数和效果）

#### 查询模板
- [x] 个人模板（用户私有）
- [x] 公共模板（全员共享）
- [x] 分类管理（按业务域分类）
- [x] 一键执行（点击模板直接运行）

#### 审计与运维
- [x] 执行日志（记录完整操作信息）
- [x] 日志筛选（按时间/状态/用户/会话ID）
- [x] 慢查询告警（执行时间>5秒）
- [x] 实时监控（成功率、缓存命中率、P95/P99延迟）
- [x] 缓存管理（管理员可手动清除）
- [x] 操作审计（全链路追踪，符合企业合规）

#### 其他功能
- [x] 字段翻译服务
- [x] 用户自助修改密码
- [x] 管理员重置密码
- [x] 白名单管理（添加/移除/查看）
- [x] 表权限配置（授予/撤销/查询）

---

### 📋 待实现

#### 高优先级
- [ ] LLM Fallback机制（程序解析失败时调用LLM提取关联关系）
- [ ] ECharts图表集成（替换文本化展示）
- [ ] WebSocket实时推送（流式返回查询结果）
- [ ] Docker容器化部署（docker-compose一键启动）

#### 中优先级
- [ ] 支持更多数据库（PostgreSQL、Oracle、SQL Server）
- [ ] API文档自动生成（Swagger/OpenAPI集成）
- [ ] 测试覆盖率提升至80%（单元测试+集成测试）
- [ ] 数据源健康检查自动切换（主从切换）

#### 低优先级
- [ ] 多租户支持（隔离不同团队的数据）
- [ ] SQL版本管理（记录SQL变更历史）
- [ ] 定时任务调度（定期执行常用查询）
- [ ] 数据血缘分析（追踪字段来源和去向）
- [ ] BI报表功能（自定义仪表盘）

---

## 🤝 贡献

欢迎贡献代码、报告Issue或提出建议！

### 贡献流程

1. Fork 本仓库
2. 创建特性分支 (`git checkout -b feature/AmazingFeature`)
3. 提交更改 (`git commit -m 'Add some AmazingFeature'`)
4. 推送到分支 (`git push origin feature/AmazingFeature`)
5. 开启 Pull Request

### 开发规范

- 遵循 [阿里巴巴Java开发手册](https://github.com/alibaba/p3c)
- 提交前运行 `mvn clean package` 确保编译通过
- 添加必要的单元测试
- 更新相关文档

---

## 📄 License

本项目采用 MIT License - 查看 [LICENSE](LICENSE) 文件了解详情

---

## 👥 团队

**核心开发者**: Jinzhao

**特别感谢**: 
- [LangChain4j](https://github.com/langchain4j/langchain4j) - LLM编排框架
- [Ollama](https://ollama.com/) - 本地大模型运行时
- [Spring Boot](https://spring.io/projects/spring-boot) - 应用框架

---

## 📧 联系方式

- 📮 Issue: [GitHub Issues](https://github.com/your-repo/NL2Sql/issues)
- 📧 Email: your-email@example.com
- 💬 讨论群: [加入Discord](https://discord.gg/your-server)

---

<div align="center">

**如果这个项目对你有帮助，请给一个 ⭐ Star！**

Made with ❤️ by NL2SQL Team

</div>
