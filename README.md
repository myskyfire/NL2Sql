# DataMind AI

<div align="center">

[![License](https://img.shields.io/badge/license-MIT-blue.svg?style=flat-square)](LICENSE)
[![Java](https://img.shields.io/badge/Java-21-orange.svg?style=flat-square&logo=openjdk)](https://www.oracle.com/java/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2.5-green.svg?style=flat-square&logo=spring)](https://spring.io/projects/spring-boot)
[![LangChain4j](https://img.shields.io/badge/LangChain4j-1.12.2-purple.svg?style=flat-square)](https://github.com/langchain4j/langchain4j)
[![MySQL](https://img.shields.io/badge/MySQL-8.0-blue.svg?style=flat-square&logo=mysql)](https://www.mysql.com/)
[![Redis](https://img.shields.io/badge/Redis-6.x-red.svg?style=flat-square&logo=redis)](https://redis.io/)
[![Status](https://img.shields.io/badge/status-Production%20Ready-green.svg?style=flat-square)]()

**🚀 企业级自然语言数据分析平台 | Plan-and-Execute 架构驱动的智能数据洞察**

*让数据像对话一样简单，让分析像计划一样严谨*

[快速开始](#-快速开始) • [技术架构](#-技术架构) • [核心特性](#-核心特性) • [API文档](#-api接口)

</div>

---

## 📖 项目简介

DataMind AI（数智洞察）是一款基于 **Spring Boot 3.2 + LangChain4j** 构建的企业级自然语言数据分析平台。系统采用先进的 **Plan-and-Execute (规划与执行)** 架构，通过 `SupervisorAgent`、`PlannerAgent` 和 `WorkflowEngine` 协同工作，将复杂的自然语言查询拆解为结构化的执行计划，并交由 Skill 工作流引擎动态编排执行。

### 📌 核心定位
**DataMind AI 定位为特定业务场景的 NL2SQL 智能底座**：
- ✅ **已验证场景**: 电商订单查询、用户行为分析、多维度统计报表、趋势洞察报告
- ⚠️ **局限性**: 超大规模数据仓库（>1000张表）或极复杂的多步逻辑推理仍需人工介入
- 🔄 **持续迭代**: 当前聚焦垂直领域深耕，通用 Agent 能力正在持续增强中

### ✨ 核心价值
<table>
<tr>
<td width="25%" align="center">
<b>🎯 零门槛交互</b><br>
业务人员无需掌握 SQL<br>自然语言即可精准查询
</td>
<td width="25%" align="center">
<b>⚡ 秒级响应</b><br>
多级缓存命中 <1s<br>未命中时 3-5s 完成全链路
</td>
<td width="25%" align="center">
<b>🔒 企业级安全</b><br>
四层防护体系<br>保障数据资产安全
</td>
<td width="25%" align="center">
<b>🤖 智能洞察</b><br>
AI 自动分析趋势<br>提供业务决策建议
</td>
</tr>
</table>

---

## 🏗️ 技术架构：Plan-and-Execute 模式

系统已从早期的 ReAct 模式全面重构为 **Plan-and-Execute** 架构，实现了意图识别、任务规划与工作流编排的深度解耦。

### 1. 整体架构图

```
┌─────────────────────────────────────────────────────────┐
│                   前端展示层 (Web UI)                     │
│  查询页面 | Agent对话 | 管理后台 | 审计日志 | 知识库      │
└────────────────────┬────────────────────────────────────┘
                     │ HTTP/REST API + JWT Token + SSE
┌────────────────────▼────────────────────────────────────┐
│                 Web控制层 (Controller Layer)              │
│  AgentController | StreamChatController | Admin APIs    │
└────────────────────┬────────────────────────────────────┘
                     │
┌────────────────────▼────────────────────────────────────┐
│              核心业务层 (nl2sql-core)                     │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  │
│  │ Supervisor   │  │ Skill Router │  │ Intent       │  │
│  │ Agent        │  │ (规则+LLM)   │  │ Classifier   │  │
│  └──────┬───────┘  └──────────────┘  └──────────────┘  │
│         │                                               │
│  ┌──────▼──────────────────────────────────────────┐   │
│  │           Planner Agent (任务规划器)             │   │
│  │  - 复杂度评估 (SIMPLE/MEDIUM/COMPLEX)           │   │
│  │  - 结构化 QueryPlan 生成                         │   │
│  └──────┬──────────────────────────────────────────┘   │
│         │                                               │
│  ┌──────▼──────────────────────────────────────────┐   │
│  │          Workflow Engine (工作流引擎)            │   │
│  │  - SKILL.md 解析与动态编排                       │   │
│  │  - Tool 调用与变量传递                           │   │
│  │  - 条件分支与人机协同审批                        │   │
│  └──────┬──────────────────────────────────────────┘   │
│         │                                               │
│  ┌──────▼──────────┐ ┌──────────────┐ ┌──────────────┐  │
│  │ Tool Registry   │ │ Worker Pool  │ │ LLM Service  │  │
│  │ (原子能力注册)   │ │ (专用执行者)  │ │ (多云路由)    │  │
│  └─────────────────┘ └──────────────┘ └──────────────┘  │
└────┬──────────┬──────────┬──────────┬──────────┬────────┘
     │          │          │          │          │
┌────▼───┐ ┌───▼───┐ ┌───▼───┐ ┌───▼───┐ ┌───▼────┐
│RAG检索  │ │三级缓存│ │SQL执行  │ │元数据  │ │安全校验 │
│Service │ │Service │ │Executor│ │Service │ │Service │
└────────┘ └───┬───┘ └───┬───┘ └───┬───┘ └────────┘
               │          │          │
        ┌──────▼───┐ ┌───▼──────┐ ┌─▼──────────┐
        │Chroma    │ │Ollama/   │ │MySQL DB    │
        │向量库     │ │Qwen/     │ │(多数据源)  │
        │Milvus    │ │ChatGLM   │ │            │
        │Qdrant    │ │vLLM/TGI  │ │            │
        └──────────┘ └──────────┘ └────────────┘
```

### 2. 核心组件详解

#### A. Supervisor Agent (总指挥)
- **职责**: 接收用户请求，进行初步的意图分类与路由分发
- **策略**: 
  - **DIRECT**: 高置信度简单查询直接调用对应 Skill
  - **LLM_ASSISTED**: 复杂查询交由 Planner 生成详细计划
  - **FALLBACK**: 降级处理机制，确保系统可用性

#### B. Planner Agent (规划师)
- **职责**: 将模糊的自然语言转化为结构化的 `QueryPlan`
- **输出**: 包含数据源信息、涉及的表、SQL 步骤序列、图表需求及总结需求的 JSON 计划
- **优势**: 避免 LLM 在长上下文中的"幻觉"，提高复杂任务的执行成功率

#### C. Workflow Engine (工作流引擎)
- **职责**: 解析 `SKILL.md` 定义的工作流，按步骤调用底层 Tool
- **特性**: 
  - 支持条件分支 (`on_condition_false`)
  - 支持变量传递 (`{{variable}}`)
  - 支持人机协同（高风险 SQL 阻断并请求确认）

---

## 🔥 核心特性

### 1. 智能查询引擎

#### 🧠 RAG 检索增强生成
- **双层向量检索**: 表级 + 字段级语义匹配，使用 bge-m3 嵌入模型（1024维，中文优化）
- **迭代式表发现**: 最多 3 轮 LLM 交互，逐步精确定位所需表，解决复杂查询的表关联问题
- **历史 SQL 注入**: 自动检索相似问题的历史 SQL 作为参考，准确率 >85%
- **RAG 自动学习**: 验证通过的 SQL 自动保存到知识库，质量评分 >0.7 才入库，实现持续优化
- **跨编码器重排序**: 支持 Jina Reranker / Ollama bge-reranker 二次精排，提升检索精度

#### 🎯 多模型智能路由
- **三级复杂度评估**: SIMPLE / MEDIUM / COMPLEX，根据关键词数量、聚合函数、多表关联、时间范围等 6 个维度评分
- **动态模型选择**: 
  - 简单查询 → Code 模型（更快更准）
  - 中等查询 → Code 模型 + RAG 增强
  - 复杂查询 → NLP 推理模型 + RAG 增强（更强的语义理解）
- **多 LLM 后端支持**: Ollama / ChatGLM / Qwen / vLLM / TGI / TensorRT-LLM / llama.cpp / 阿里云通义千问，统一 `LLMProvider` 接口适配

#### 💬 行业语义理解
- **同义词词典**: 自动识别业务术语（订单/定单、用户/客户、DAU/GMV 等），从 `industry_concept` 表动态加载
- **行业概念字典**: 内置电商、金融、医疗、教育、制造业五大行业概念映射
- **语义映射扩展**: 可插拔的 `SemanticMappingExtension` 接口，支持自定义术语提取、概念学习等逻辑
- **行业 Prompt 注入**: `IndustryConceptExtension` 在 SQL 生成前后注入行业特定提示词和修正规则

#### ⏰ 时间表达式解析
- 智能理解"昨天"、"最近 7 天"、"上个月"、"本季度"等自然语言时间表达
- 自动注入当前日期、星期、相对时间基准等上下文信息

#### 🔄 SQL 自动纠错与 Self-Correction
- **6 种错误类型识别**: 表不存在、列不存在、语法错误、歧义列、死锁、超时
- **幻觉列名检测**: Schema 白名单校验，防止 LLM 生成不存在的列名
- **聚合合法性检查**: GROUP BY vs SELECT 非聚合字段一致性校验
- **JOIN 条件完整性检查**: 检测缺失的 JOIN ON 条件
- **最多 3 次自动重试**: 语法修正 → 聚合修正 → 幻觉修正，逐步修复

### 2. SKILL.md 工作流编排

#### 📋 声明式工作流定义
系统采用 YAML 格式的 `SKILL.md` 文件定义工作流，实现了业务逻辑与代码的深度解耦：

```yaml
workflow:
  steps:
    - id: validate_params
      action: call_tool
      tool: validateParams
      input:
        question: "{{question}}"
        datasourceId: "{{datasourceId}}"
      output_var: validation_result
      on_next: detect_chart

    - id: check_human_approval
      action: respond
      condition: "${risk_result.needsHumanApproval}==true"
      output:
        type: "human_approval_required"
        riskLevel: "{{risk_result.riskLevel}}"
      on_next: null
      on_condition_false: execute_sql
```

#### 🔧 内置 Skills
| Skill | 说明 | 适用场景 |
|-------|------|---------|
| `execute_standard_query` | 标准查询技能：参数校验→图表检测→Schema检索→SQL生成→风险评估→人机协同→执行 | 日常数据查询 |
| `generate_report_with_insights` | 报表洞察技能：标准查询 + AI总结 + 图表推荐 | 趋势分析、综合报告 |

#### 🛠️ 原子 Tool 注册表
系统通过 `ToolRegistry` 自动扫描 `@Tool` 注解，注册所有原子能力：

| Tool | 说明 | 可见性 |
|------|------|--------|
| `validateParams` | 参数校验 | INTERNAL |
| `clarifyDatasource` | 数据源智能选择/澄清 | PUBLIC |
| `retrieveSchema` | Schema 检索 | INTERNAL |
| `generateSQL` | SQL 生成 | INTERNAL |
| `analyzeSQLRisk` | SQL 风险评估（三层：快速判断→EXPLAIN→LLM） | INTERNAL |
| `executeSQL` | SQL 执行（带智能重试） | INTERNAL |
| `detectAndGenerateChart` | 图表意图检测 + ECharts 配置生成 | INTERNAL |
| `summarizeResult` | AI 结果总结 | PUBLIC |
| `correctSql` | SQL 纠错（语法修复→幻觉检测→LLM重写） | INTERNAL |

**Tool 可见性控制**：`PUBLIC` 对 LLM 可见可直接调用，`INTERNAL` 仅 Skill 内部调用，避免 LLM 选择错误的底层工具。

### 3. 企业级安全防护

#### 🛡️ 四层防护体系
```
Layer 1: JWT 认证         → 身份验证，确保合法用户访问
Layer 2: 表级权限         → 控制用户可访问的数据表
Layer 3: 列级脱敏         → 敏感字段自动替换为 ***
Layer 4: SQL AST 验证     → JSqlParser 解析，拦截危险操作
```

#### 🚫 SQL 安全校验器 (`SQLSecurityValidator`)
- **DDL 拦截**: DROP / ALTER / CREATE / TRUNCATE / RENAME 全部禁止
- **DML 写操作拦截**: DELETE / UPDATE / INSERT / MERGE 全部禁止
- **注入防护**: 禁止多语句执行（分号检测）、注释中隐藏危险操作检测
- **敏感函数拦截**: `LOAD_FILE` / `INTO OUTFILE` / `BENCHMARK` / `SLEEP` 等
- **JOIN 数量限制**: 最多 2 个 JOIN，防止复杂查询拖垮数据库
- **全表扫描防护**: 无 WHERE 且无 LIMIT 的查询被拦截

#### 🔒 数据安全
- **密码加密存储**: AES-256-GCM 加密数据库凭证，兼容明文开发模式
- **列级权限控制**: `ColumnPermissionService` 精确到列的访问权限和脱敏策略
- **审计日志**: `AuditService` 记录所有查询操作，Redis 存储 30 天

### 4. 性能优化与缓存

#### 🎯 三级缓存架构
```
L1: Redis 精确匹配
    └─ SQL 语义 MD5 哈希 → 完整查询结果
    └─ 命中率: ~40%，延迟 <1ms

L2: 归一化模板匹配
    └─ 规则引擎提取查询结构 → SQL 模板填充
    └─ 5星评分 SQL 自动入库，支持时间词/人名替换
    └─ 行业提取器: 电商/金融等特定领域参数提取

L3: Chroma 向量检索 + Jaccard 二次校验
    └─ bge-m3 语义嵌入 → 相似度 >0.75 自动匹配
    └─ Chroma 不可用时自动降级到 Jaccard 算法
```

- **综合命中率 >60%**，LLM 调用减少 60%
- **EXPLAIN 结果缓存**: Redis 缓存 12 小时，避免重复分析执行计划
- **表统计信息缓存**: Redis 缓存 12 小时，加速风险评估
- **向量检索缓存**: L1 精确匹配 + L2 模糊匹配，避免重复向量化

#### ⚡ 动态数据源管理
- **HikariCP 连接池**: 每个数据源独立连接池，懒加载创建，空闲自动回收
- **连接泄漏检测**: 60 秒阈值，自动发现连接未释放问题
- **UTF-8 强制设置**: 每次获取连接执行 `SET NAMES utf8mb4`
- **MySQL 8.0+ 兼容**: `caching_sha2_password` 认证支持

### 5. 智能对话系统

#### 🗨️ 多轮对话
- **对话历史**: 保存最近 10 轮对话历史，支持连续追问
- **上下文传递**: `EnhancedContext` 整合对话历史、表引用信息、用户偏好、时间上下文、行业概念
- **指代消解**: 基础指代理解（"它"、"这个"等简单代词）
- **会话管理**: Redis 存储会话，24 小时 TTL 自动过期

#### 📡 SSE 流式响应
前端可订阅实时进度事件：
```
creating → schema_retrieved → sql_generated → risk_analyzed → executing → completed
```

#### 📊 图表自动生成
- **意图检测**: 自动识别柱状图/折线图/饼图/面积图需求
- **ECharts 配置**: 根据数据特征自动生成完整的 ECharts 可视化配置
- **问题清洗**: 自动移除图表描述词，保证 SQL 生成质量

### 6. 动态 Prompt 工程

#### 🧩 模块化 Prompt 构建 (`DynamicPromptBuilder`)
根据用户意图动态组装最精简的 System Prompt，避免冗余 Token 消耗：

| 模块 | 职责 | Token 估算 |
|------|------|-----------|
| `BaseInstructionModule` | 基本角色与输出格式 | ~150 |
| `DataSourceWorkflowModule` | 数据源澄清流程 | ~150 |
| `QueryWorkflowModule` | 标准查询工作流 | ~350 |
| `SummaryWorkflowModule` | AI 总结工作流 | ~100 |
| `ChartWorkflowModule` | 图表生成工作流 | ~100 |
| `SkillsModule` | 动态注入可用 Skills | ~300-500 |
| `ToolListModule` | 工具列表说明 | ~50 |

- **Token 减少 60-70%**（从 ~2500 降至 ~800）
- **内置缓存机制**: 相同意图复用已构建的 Prompt
- **Tool 描述增强**: `ToolDescriptionEnhancer` 动态生成包含适用/不适用场景的工具说明

### 7. 元数据异步增强

#### ✨ Schema 自动推断
- 120+ 种字段名规则映射，零成本覆盖 50-60% 字段
- 自动推断字段含义、数据类型、业务语义

#### 🔄 反馈驱动增强
- 低分反馈自动触发相关表的 LLM 增强
- 问题驱动持续优化，越用越准

#### ⏱️ 定时批量增强
- 每周日凌晨 2 点自动增强高频低质表
- 增量更新，不影响在线服务

### 8. 可观测性与追踪

#### 📊 LangSmith 集成
- **全链路追踪**: SupervisorAgent → PlannerAgent → WorkflowEngine → Tool 调用，每一步可追踪
- **Run 树结构**: 父子 Run 关联，完整还原执行路径
- **自定义标签**: 支持按复杂度、数据源、用户等维度筛选
- **可插拔设计**: LangSmith 未配置时自动跳过，零性能损耗

#### 📈 监控上下文
- `MonitoringContext` 记录每次查询的缓存命中、LLM 调用、执行时间等指标
- 表组合缓存（LRU 1000 条），避免重复的表选择计算

---

## 🧩 项目模块结构

```
NL2Sql/
├── nl2sql-common          # 通用工具模块（加密、JSON、用户上下文等）
├── nl2sql-core            # 核心业务模块
│   ├── agent/             # Agent 架构（Supervisor、Planner、WorkflowEngine）
│   │   ├── tools/         # 原子 Tool 实现（20+ 个工具）
│   │   ├── skills/        # Skill 加载与执行
│   │   ├── prompt/        # 动态 Prompt 模块化构建
│   │   ├── context/       # 增强上下文（对话历史、时间、行业概念）
│   │   ├── response/      # 统一响应格式
│   │   ├── validation/    # SQL 验证服务（语法、聚合、JOIN）
│   │   └── extension/     # 语义映射扩展（电商、金融等行业）
│   ├── llm/               # LLM 服务层
│   │   ├── provider/      # 多 LLM 后端适配（Ollama/ChatGLM/Qwen/vLLM...）
│   │   ├── config/        # LLM 提供者自动配置
│   │   └── extension/     # 行业概念扩展接口
│   ├── rag/               # RAG 检索增强
│   │   ├── provider/      # 向量数据库适配（Chroma/Milvus/Qdrant/MySQL）
│   │   └── config/        # 向量存储自动配置
│   ├── cache/             # 三级缓存（Redis/模板/向量）
│   ├── retriever/         # 向量检索器（bge-m3 嵌入）
│   ├── rerank/            # 重排序（Jina/Cross-Encoder）
│   ├── executor/          # SQL 执行与风险分析
│   ├── datasource/        # 动态数据源管理（HikariCP）
│   ├── security/          # SQL 安全校验
│   ├── tracing/           # LangSmith 链路追踪
│   └── error/             # 错误分类器（6 种错误类型）
├── nl2sql-security        # 安全模块（列级权限、SQL AST 校验）
├── nl2sql-conversation    # 对话模块（多轮对话、会话管理）
├── nl2sql-audit           # 审计模块（查询日志、操作记录）
└── nl2sql-web             # Web 层（Controller、SSE、静态页面、SKILL.md）
    └── src/main/resources/
        └── skills/        # SKILL.md 工作流定义
            ├── standard-query/
            └── report-with-insights/
```

---

## 🔌 可扩展性设计

### LLM 提供者扩展
实现 `LLMProvider` 接口即可接入新的 LLM 后端：
```java
public interface LLMProvider {
    String getName();
    boolean isAvailable();
    String generate(String prompt, double temperature);
    String generateJson(String systemPrompt, String userPrompt, double temperature);
    default Map<String, Object> generateWithTools(...) { ... }
    default void generateStream(...) { ... }
}
```

### 向量数据库扩展
实现 `VectorStoreProvider` 接口即可接入新的向量数据库：
```java
public interface VectorStoreProvider {
    String getName();
    boolean isAvailable();
    String addKnowledge(String question, String answer, String sqlExample, ...);
    List<VectorSearchResult> searchSimilar(String question, int maxResults, double minScore);
}
```

### 行业概念扩展
实现 `IndustryConceptExtension` 接口，提供四层扩展能力：
```java
public interface IndustryConceptExtension {
    // Level 1: 术语理解增强
    List<Map<String, Object>> extractTerms(String question, Long datasourceId);
    List<String> suggestSynonyms(String conceptKey, String industryCode);
    
    // Level 2: SQL 生成干预
    String enhancePromptBeforeGeneration(String originalPrompt, String question, Long datasourceId);
    String correctSqlAfterGeneration(String generatedSql, String question, Long datasourceId);
    
    // Level 3: SQL 执行校验
    boolean validateSemanticConsistency(String question, String sql, Long datasourceId);
    Map<String, Object> validateBeforeExecution(String sql, String question, Long datasourceId, String userId);
    
    // Level 4: 学习与优化
    void learnFromExecution(String question, String sql, boolean success, ...);
}
```

### Skill 扩展
在 `skills/` 目录下创建新的 `SKILL.md` 文件即可定义新的工作流，系统自动扫描加载。

---

## 🆚 与主流开源框架对比

| 特性 | DataMind AI | Vanna AI | WrenAI | SuperSonic |
|------|-------------|----------|--------|------------|
| **核心定位** | 企业级 NL2SQL 平台 | Python RAG 框架 | GenBI 智能体 | Headless BI |
| **部署方式** | ✅ 完全本地化 | ⚠️ 需云端 API | ✅ Docker/本地 | ✅ Docker/JAR |
| **数据隐私** | ✅ 数据不出内网 | ⚠️ 依赖 OpenAI | ✅ 可本地部署 | ✅ 可本地部署 |
| **架构模式** | **Plan-and-Execute** | ReAct/CoT | GenBI Pipeline | Semantic Layer |
| **工作流编排** | ✅ SKILL.md 动态编排 | ❌ 硬编码逻辑 | ⚠️ 需理解 MDL | ❌ 需自行实现 |
| **人机协同** | ✅ 高风险 SQL 审批 | ❌ 无 | ❌ 无 | ❌ 无 |
| **SQL Self-Correction** | ✅ 6类错误+幻觉检测 | ⚠️ 基础重试 | ⚠️ 基础重试 | ❌ 无 |
| **多模型路由** | ✅ 复杂度自适应 | ❌ 单模型 | ❌ 单模型 | ⚠️ 配置切换 |
| **行业扩展** | ✅ 5大行业+可插拔 | ❌ 无 | ❌ 无 | ⚠️ 语义模型 |
| **向量数据库** | ✅ Chroma/Milvus/Qdrant | ✅ Chroma | ❌ 无 | ❌ 无 |
| **中文支持** | ✅ 原生优化 | ⚠️ 依赖 Prompt | ⚠️ 英文优先 | ✅ 中文优化 |
| **可观测性** | ✅ LangSmith 集成 | ❌ 无 | ❌ 无 | ⚠️ 基础日志 |

---

## 🚀 快速开始

### 前置要求
| 依赖 | 版本 | 说明 |
|------|------|------|
| JDK | 21+ | **强制使用路径**: `D:\Program Files\Java\jdk-21.0.6` |
| MySQL | 8.0+ | 存储业务数据与元数据 |
| Redis | 6.x+ | 分布式缓存与会话管理 |
| Ollama | 最新 | 推荐 qwen2.5-coder:7b + qwen3:8b |
| Chroma | 可选 | 语义缓存与 RAG 知识库 |

### 5分钟启动指南

#### Step 1: 初始化数据库
```bash
mysql -u root -p < init_complete_database.sql
```

#### Step 2: 配置应用
编辑 `nl2sql-web/src/main/resources/application.yml`:
```yaml
spring:
  datasource:
    password: your_mysql_password
  data:
    redis:
      host: localhost
      port: 6379

ollama:
  base-url: http://localhost:11434
  code-model: qwen2.5-coder:7b-instruct-q4_0
  nlp-model: qwen3:8b

# 可选：启用 Chroma 语义缓存
chroma:
  enabled: true
  url: http://localhost:8000

# 可选：启用 LangSmith 追踪
langsmith:
  enabled: true
  api-key: your_langsmith_api_key
  project: datamind-ai
```

#### Step 3: 编译运行
```bash
cd "D:\WorkSpace\idea workspace\NL2Sql"
mvn clean package -DskipTests
java -jar nl2sql-web/target/nl2sql-web-1.0.0.jar
```

#### Step 4: 访问系统
打开浏览器访问: **http://localhost:8080**
默认账号: `admin` / `admin123`

---

## 📊 典型查询流程

```
用户: "统计上个月各地区的订单总额"
  │
  ├─ 1. SupervisorAgent 接收请求
  │     └─ 意图分类: QUERY → 路由到 execute_standard_query
  │
  ├─ 2. 参数校验 (validateParams)
  │     └─ datasourceId ✅, question ✅
  │
  ├─ 3. 图表意图检测 (detectAndGenerateChart)
  │     └─ 未检测到图表意图, cleanedQuestion = "统计上个月各地区的订单总额"
  │
  ├─ 4. Schema 检索 (retrieveSchema)
  │     ├─ 同义词扩展: "总额" → "金额/销售额"
  │     ├─ 向量检索: orders, order_items, regions
  │     └─ 返回表结构 + 关联关系
  │
  ├─ 5. SQL 生成 (generateSQL)
  │     ├─ 模型路由: MEDIUM → Code模型 + RAG增强
  │     ├─ 行业概念注入: "总额" → SUM(actual_amount)
  │     └─ 生成: SELECT r.name, SUM(o.actual_amount) ...
  │
  ├─ 6. 风险评估 (analyzeSQLRisk)
  │     ├─ 快速判断: 有WHERE + 有LIMIT → LOW
  │     └─ 不需要人机协同
  │
  ├─ 7. SQL 执行 (executeSQL)
  │     ├─ JSqlParser 安全校验 ✅
  │     ├─ 列级脱敏检查 ✅
  │     └─ 返回 15 行数据
  │
  └─ 8. 结果返回
        ├─ data: [{region: "华东", total: 128500}, ...]
        ├─ sql: "SELECT ..."
        ├─ followUpSuggestions: ["🤖 AI 总结", "📊 生成图表"]
        └─ executionTime: 1.2s
```

---

## 📝 贡献指南

我们欢迎任何形式的贡献！如果您想参与开发：
1. Fork 本仓库
2. 创建您的特性分支 (`git checkout -b feature/AmazingFeature`)
3. 提交您的改动 (`git commit -m 'Add some AmazingFeature'`)
4. 推送到分支 (`git push origin feature/AmazingFeature`)
5. 开启一个 Pull Request

---

## 📄 许可证

本项目采用 MIT 许可证。详情请参阅 [LICENSE](LICENSE) 文件。
