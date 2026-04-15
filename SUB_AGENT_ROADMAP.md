# Sub-Agent 架构演进路线图

## 📋 文档说明

**目标**：从单体 ReAct Agent 演进到多 Agent 协作架构  
**状态**：规划阶段（Phase 0）  
**优先级**：P1（SQL准确性优化完成后立即启动）

---

## 🎯 为什么需要 Sub-Agent？

### 当前架构痛点

1. **职责过载**
   - 单个 Agent 需要处理：数据源澄清、表结构检索、SQL生成、执行、总结、图表推荐等所有任务
   - SystemMessage 过于复杂（200+行），LLM难以精准理解所有规则
   - 工具列表过长（10+个Tools），增加 LLM 决策负担

2. **上下文污染**
   - 所有对话历史都在同一个 Agent 中维护
   - 不同任务的中间结果相互干扰
   - 无法针对不同场景优化记忆策略

3. **扩展性受限**
   - 添加新功能需要修改主 Agent 的 SystemMessage
   - 无法独立测试和优化某个子流程
   - 难以实现并行化（如同时执行多个查询）

4. **错误恢复困难**
   - 某个环节失败会影响整个流程
   - 无法针对特定错误类型定制恢复策略
   - 重试机制粗糙（全局统一 MAX_ITERATIONS=10）

---

## 🏗️ 目标架构设计

### 核心概念

```
Master Agent (协调者)
├── IntentClassifier Sub-Agent (意图识别专家)
├── DataDiscovery Sub-Agent (数据探索专家)
├── SQLGenerator Sub-Agent (SQL生成专家)
├── QueryExecutor Sub-Agent (查询执行专家)
├── InsightAnalyzer Sub-Agent (分析洞察专家)
└── ReportBuilder Sub-Agent (报告构建专家)
```

### 各 Sub-Agent 职责

#### 1. IntentClassifier Sub-Agent
**职责**：精准识别用户意图并路由到合适的 Sub-Agent  
**输入**：用户问题 + 对话历史  
**输出**：`{ intent: "QUERY" | "ANALYSIS" | "REPORT" | "CLARIFICATION", confidence: 0.95, target_agent: "DataDiscovery" }`  
**工具**：
- `classify_intent(question)` - 分类意图
- `extract_entities(question)` - 提取关键实体（时间、指标、维度）

**优势**：
- ✅ 专注意图识别，准确率更高
- ✅ 可集成专门的 NER 模型
- ✅ 支持意图置信度判断，低置信度时主动追问

---

#### 2. DataDiscovery Sub-Agent
**职责**：智能检索相关表结构和元数据  
**输入**：用户问题 + 数据源ID  
**输出**：`{ tables: [...], relationships: [...], relevant_columns: [...] }`  
**工具**：
- `search_tables_by_semantic(query, datasourceId)` - 语义搜索表
- `get_table_schema(tableName, datasourceId)` - 获取表结构
- `discover_relationships(tables)` - 发现表关联关系
- `validate_datasource_access(datasourceId, userId)` - 权限校验

**优势**：
- ✅ 专注于元数据检索，避免遗漏关键表
- ✅ 可集成向量检索 + 图数据库联合查询
- ✅ 支持缓存热点表结构

---

#### 3. SQLGenerator Sub-Agent
**职责**：根据表结构和用户需求生成准确 SQL  
**输入**：用户问题 + 表结构 + 关系信息  
**输出**：`{ sql: "SELECT ...", explanation: "...", complexity: "SIMPLE" | "COMPLEX" }`  
**工具**：
- `generate_sql(question, schema_context)` - 生成 SQL
- `validate_sql_syntax(sql)` - 语法校验
- `check_sql_complexity(sql)` - 复杂度评估
- `suggest_optimizations(sql)` - 优化建议

**优势**：
- ✅ 专注 SQL 生成，可针对性优化 Prompt
- ✅ 集成 SQL 解析器进行预校验
- ✅ 支持多轮修正（Self-Correction）

---

#### 4. QueryExecutor Sub-Agent
**职责**：安全执行 SQL 并返回结果  
**输入**：SQL + 数据源ID + 用户ID  
**输出**：`{ success: true, data: [...], rowCount: 100, executionTime: 0.5s }`  
**工具**：
- `assess_risk(sql)` - 风险评估
- `explain_query(sql)` - 执行计划分析
- `execute_with_timeout(sql, timeout)` - 带超时的执行
- `paginate_result(data, page, pageSize)` - 分页处理

**优势**：
- ✅ 专注安全性和性能控制
- ✅ 统一的限流和熔断机制
- ✅ 自动降级策略（大结果集截断）

---

#### 5. InsightAnalyzer Sub-Agent
**职责**：对查询结果进行深度分析和洞察  
**输入**：查询结果 + 用户问题  
**输出**：`{ summary: "...", insights: [...], anomalies: [...], trends: [...] }`  
**工具**：
- `summarize_data(data, question)` - 数据总结
- `detect_anomalies(data)` - 异常检测
- `identify_trends(data)` - 趋势识别
- `calculate_statistics(data)` - 统计计算

**优势**：
- ✅ 专注数据分析，可集成统计算法库
- ✅ 支持自定义分析模板
- ✅ 可并行执行多种分析

---

#### 6. ReportBuilder Sub-Agent
**职责**：构建结构化报告（含图表推荐）  
**输入**：分析结果 + 原始数据  
**输出**：`{ report: {...}, chartRecommendations: [...], followUpQuestions: [...] }`  
**工具**：
- `generate_report_template(type)` - 选择报告模板
- `recommend_charts(data, insights)` - 图表推荐
- `generate_echarts_config(chartType, data)` - 生成 ECharts 配置
- `suggest_follow_up_questions(context)` - 生成追问建议

**优势**：
- ✅ 专注报告呈现，支持多种模板
- ✅ 智能图表匹配算法
- ✅ 上下文感知的追问生成

---

## 🔄 协作流程示例

### 场景1：简单查询
```
用户："查询最近10条订单"

1. Master Agent → IntentClassifier: 识别为 QUERY 意图
2. Master Agent → DataDiscovery: 检索 orders 表结构
3. Master Agent → SQLGenerator: 生成 SELECT * FROM orders ORDER BY create_time DESC LIMIT 10
4. Master Agent → QueryExecutor: 执行 SQL 并返回结果
5. Master Agent → 用户: 直接展示数据表格
```

### 场景2：复杂分析
```
用户："分析上月销售趋势并给出建议"

1. Master Agent → IntentClassifier: 识别为 ANALYSIS 意图
2. Master Agent → DataDiscovery: 检索 orders, products, customers 表
3. Master Agent → SQLGenerator: 生成聚合查询 SQL
4. Master Agent → QueryExecutor: 执行 SQL
5. Master Agent → InsightAnalyzer: 分析趋势、识别异常
6. Master Agent → ReportBuilder: 生成报告 + 折线图 + 追问建议
7. Master Agent → 用户: 展示完整分析报告
```

### 场景3：数据源不明确
```
用户："查询订单数据"

1. Master Agent → IntentClassifier: 识别为 CLARIFICATION 意图
2. Master Agent → DataDiscovery: 获取可用数据源列表
3. Master Agent → 用户: "您想查询哪个数据源的订单？A. 生产库 B. 测试库"
4. 用户选择后，继续正常流程
```

---

## 🚀 实施路线图

### Phase 1: 基础设施准备（2周）
**目标**：搭建 Sub-Agent 框架和通信机制

- [ ] **1.1 定义 Sub-Agent 接口规范**
  - 统一输入/输出格式
  - 定义错误码和异常处理
  - 设计消息传递协议（JSON Schema）

- [ ] **1.2 实现 Agent 通信总线**
  - 基于 Spring Event 或消息队列
  - 支持同步/异步调用
  - 实现超时和重试机制

- [ ] **1.3 创建 BaseSubAgent 抽象类**
  - 封装通用逻辑（日志、监控、错误处理）
  - 提供工具注册和管理
  - 支持依赖注入

- [ ] **1.4 实现 Master Agent 协调器**
  - 负责任务分发和结果聚合
  - 管理 Sub-Agent 生命周期
  - 实现 fallback 机制

**交付物**：
- `BaseSubAgent.java` - 抽象基类
- `AgentBus.java` - 通信总线
- `MasterAgent.java` - 协调器
- 单元测试覆盖率 > 80%

---

### Phase 2: 核心 Sub-Agent 实现（3周）
**目标**：实现 IntentClassifier、DataDiscovery、SQLGenerator

- [ ] **2.1 IntentClassifier Sub-Agent**
  - 集成意图分类模型（可使用 LLM 或专用分类器）
  - 实现实体提取（时间、指标、维度）
  - 添加置信度阈值配置
  - 编写 20+ 测试用例验证分类准确率

- [ ] **2.2 DataDiscovery Sub-Agent**
  - 重构现有的 VectorRetriever 和 MetadataService
  - 实现语义搜索 + 关键词搜索混合检索
  - 添加表关系推理逻辑
  - 实现结果缓存（Redis，TTL 1小时）

- [ ] **2.3 SQLGenerator Sub-Agent**
  - 迁移现有的 NL2SQLTool 逻辑
  - 集成 SQL 解析器（如 JSqlParser）
  - 实现 Self-Correction 机制（最多3次修正）
  - 添加 SQL 复杂度分级

- [ ] **2.4 集成测试**
  - 端到端测试简单查询流程
  - 验证错误处理和重试机制
  - 性能基准测试（P95 < 5s）

**交付物**：
- 3个 Sub-Agent 实现类
- 集成测试套件
- 性能基准报告

---

### Phase 3: 增强 Sub-Agent 实现（2周）
**目标**：实现 QueryExecutor、InsightAnalyzer、ReportBuilder

- [ ] **3.1 QueryExecutor Sub-Agent**
  - 迁移现有的 SQLExecutionTool
  - 实现风险评估引擎（集成 EXPLAIN 分析）
  - 添加限流和熔断（Resilience4j）
  - 实现智能分页和结果截断

- [ ] **3.2 InsightAnalyzer Sub-Agent**
  - 实现基础统计分析（均值、方差、分布）
  - 集成异常检测算法（3σ原则、IQR）
  - 实现趋势识别（线性回归、移动平均）
  - 支持自定义分析插件

- [ ] **3.3 ReportBuilder Sub-Agent**
  - 迁移现有的 AISummaryTool 和 ChartRecommendationTool
  - 实现报告模板引擎
  - 优化图表推荐算法
  - 生成上下文感知的追问建议

- [ ] **3.4 全链路测试**
  - 测试复杂分析场景
  - 验证报告生成质量
  - 压力测试（并发 50 QPS）

**交付物**：
- 3个增强 Sub-Agent 实现类
- 报告模板库（5+模板）
- 压力测试报告

---

### Phase 4: 迁移与优化（2周）
**目标**：将现有 Groovy Skills 迁移到 Sub-Agent 架构

- [ ] **4.1 StandardQuerySkill 迁移**
  - 拆解为 DataDiscovery → SQLGenerator → QueryExecutor 流水线
  - 保持向后兼容（保留原有 API）
  - 对比新旧实现的性能和准确性

- [ ] **4.2 ReportWithInsightsSkill 迁移**
  - 拆解为完整的多 Agent 协作流程
  - 优化并行执行（InsightAnalyzer 和 ReportBuilder 可并行）
  - 添加增量更新支持

- [ ] **4.3 性能优化**
  - 识别瓶颈（使用 Arthas 或 JProfiler）
  - 优化串行调用为并行（CompletableFuture）
  - 添加多级缓存（本地缓存 + Redis）

- [ ] **4.4 监控与告警**
  - 集成 Micrometer + Prometheus
  - 关键指标：各 Sub-Agent 耗时、成功率、错误率
  - 配置告警规则（P95 > 10s 告警）

**交付物**：
- 迁移后的 Skills 实现
- 性能对比报告
- 监控看板（Grafana）

---

### Phase 5: 高级特性（可选，3周）
**目标**：实现智能化和自适应能力

- [ ] **5.1 动态路由优化**
  - 基于历史数据学习最优路由策略
  - 实现 A/B Testing 框架
  - 支持灰度发布新 Sub-Agent

- [ ] **5.2 自适应重试策略**
  - 根据错误类型动态调整重试次数
  - 实现指数退避算法
  - 添加 Circuit Breaker 模式

- [ ] **5.3 分布式部署支持**
  - Sub-Agent 可独立部署为微服务
  - 实现服务发现和负载均衡
  - 支持跨区域部署

- [ ] **5.4 持续学习机制**
  - 收集用户反馈（点赞/点踩）
  - 自动优化 IntentClassifier 模型
  - 定期更新向量索引

**交付物**：
- A/B Testing 平台
- 分布式部署文档
- 自动化训练 pipeline

---

## 📊 关键技术选型

| 组件 | 选型 | 理由 |
|------|------|------|
| **通信机制** | Spring Event + CompletableFuture | 轻量级、无需额外中间件、支持异步 |
| **服务治理** | Resilience4j | 成熟的熔断、限流、重试实现 |
| **SQL解析** | JSqlParser | Java生态最成熟的SQL解析器 |
| **统计分析** | Apache Commons Math | 丰富的统计算法库 |
| **异常检测** | 自研（3σ + IQR） | 简单有效，无需训练模型 |
| **缓存** | Caffeine + Redis | 两级缓存，平衡性能和一致性 |
| **监控** | Micrometer + Prometheus + Grafana | 标准技术栈，易于集成 |
| **测试框架** | JUnit 5 + TestContainers | 支持真实数据库集成测试 |

---

## ⚠️ 风险与应对

### 风险1：复杂性增加
**影响**：开发和维护成本上升  
**应对**：
- 严格的接口规范和文档
- 完善的单元测试和集成测试
- 逐步迁移，保持向后兼容

### 风险2：性能下降
**影响**：多 Agent 协作引入额外开销  
**应对**：
- 并行化设计（非依赖任务并行执行）
- 多级缓存策略
- 性能基准测试和持续监控

### 风险3：调试困难
**影响**：问题定位复杂  
**应对**：
- 统一的 Trace ID 贯穿全流程
- 详细的结构化日志
- 可视化调用链（Zipkin 或 SkyWalking）

### 风险4：LLM 成本增加
**影响**：多次调用 LLM 导致成本上升  
**应对**：
- 小任务使用轻量模型（qwen2.5-coder:7b）
- 大任务使用强模型（qwen2.5:72b）
- 缓存常见问题的回答

---

## 📈 成功指标

### 技术指标
- ✅ SQL 生成准确率提升至 95%+（当前 ~85%）
- ✅ P95 响应时间 < 5s（简单查询 < 2s）
- ✅ 系统可用性 > 99.9%
- ✅ 支持并发 100+ QPS

### 业务指标
- ✅ 用户满意度评分 > 4.5/5.0
- ✅ 平均对话轮次减少 30%
- ✅ 首次查询成功率 > 90%
- ✅ 用户留存率提升 20%

### 工程指标
- ✅ 单元测试覆盖率 > 80%
- ✅ 集成测试覆盖率 > 60%
- ✅ 代码重复率 < 5%
- ✅ CI/CD 流水线自动化率 100%

---

## 🎓 学习资源

### 理论参考
- [Multi-Agent Systems: An Introduction](https://www.amazon.com/Multi-Agent-Systems-Introduction-Distributed-Artificial/dp/0201360489)
- [LangGraph Documentation](https://langchain-ai.github.io/langgraph/) - 多 Agent 编排最佳实践
- [AutoGen Framework](https://microsoft.github.io/autogen/) - Microsoft 的多 Agent 框架

### 技术文章
- "Building Multi-Agent Systems with LangChain" - LangChain 官方博客
- "Microservices vs Multi-Agent Architecture" - Martin Fowler 博客
- "Event-Driven Architecture Patterns" - Confluent 技术博客

### 开源项目参考
- [LangGraph](https://github.com/langchain-ai/langgraph) - Python 多 Agent 编排
- [CrewAI](https://github.com/joaomdmoura/crewAI) - 角色扮演的多 Agent 框架
- [Microsoft AutoGen](https://github.com/microsoft/autogen) - 微软多 Agent 框架

---

## 📝 附录

### A. Sub-Agent 接口定义（草案）

```java
public interface SubAgent {
    /**
     * 执行 Sub-Agent 任务
     * @param request 标准化请求
     * @return 标准化响应
     */
    SubAgentResponse execute(SubAgentRequest request);
    
    /**
     * 获取 Sub-Agent 元数据
     */
    SubAgentMetadata getMetadata();
    
    /**
     * 健康检查
     */
    HealthStatus healthCheck();
}

@Data
public class SubAgentRequest {
    private String requestId;          // 唯一请求ID
    private String traceId;            // 链路追踪ID
    private Map<String, Object> payload; // 业务数据
    private RequestContext context;    // 上下文（用户信息、会话ID等）
    private long timeoutMs;            // 超时时间
}

@Data
public class SubAgentResponse {
    private String requestId;
    private boolean success;
    private Map<String, Object> data;
    private ErrorInfo error;           // 失败时填充
    private long executionTimeMs;
    private List<String> warnings;     // 警告信息
}
```

### B. 消息格式示例

**IntentClassifier 请求**：
```json
{
  "requestId": "req_123456",
  "traceId": "trace_abc",
  "payload": {
    "question": "查询上月销售额",
    "conversationHistory": [...]
  },
  "context": {
    "userId": 123,
    "sessionId": "sess_xyz"
  },
  "timeoutMs": 3000
}
```

**IntentClassifier 响应**：
```json
{
  "requestId": "req_123456",
  "success": true,
  "data": {
    "intent": "QUERY",
    "confidence": 0.95,
    "entities": {
      "timeRange": "last_month",
      "metric": "sales_amount"
    },
    "targetAgent": "DataDiscovery"
  },
  "executionTimeMs": 150
}
```

### C. 错误码定义

| 错误码 | 含义 | 处理策略 |
|--------|------|----------|
| AGENT_TIMEOUT | Sub-Agent 超时 | 重试或降级 |
| AGENT_UNAVAILABLE | Sub-Agent 不可用 | 切换到备用 Agent |
| INVALID_INPUT | 输入参数无效 | 返回明确错误信息 |
| SQL_GENERATION_FAILED | SQL 生成失败 | 触发 Self-Correction |
| EXECUTION_RISK_HIGH | SQL 执行风险高 | 拒绝执行，提示用户 |
| DATA_EMPTY | 查询结果为空 | 返回友好提示 |

---

## 🔄 版本历史

| 版本 | 日期 | 变更说明 |
|------|------|----------|
| v0.1 | 2026-04-12 | 初始版本，完成架构设计和路线图规划 |

---

**下一步行动**：
1. 团队评审此路线图，确认优先级和时间安排
2. 开始 Phase 1 的基础设施准备工作
3. 创建 GitHub Project 跟踪任务进度
