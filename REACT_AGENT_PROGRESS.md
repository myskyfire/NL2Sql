# NL2SQL 项目演进路线图 (Roadmap)

## 📊 当前状态概览

**核心架构**：ReAct Agent + LangChain4j + Ollama  
**已完成阶段**：Phase 1 & Phase 2 (基础架构 + Skills集成)  
**就绪度评估**：内部试用级别（需完成P0项可达生产环境）

---

## ✅ 已完成的工作

### Phase 1: 核心架构重构 (100% 完成)

#### Step 1: 重写 SystemMessage ✅
**文件**: `NL2SQL-core/src/main/java/com/nl2sql/core/agent/NL2SQLAgent.java`

**改动**:
- ❌ 移除：硬编码的7步固定流程
- ✅ 新增：ReAct 工作模式说明（Thought-Action-Observation循环）
- ✅ 新增：自主决策原则
- ✅ 优化：工具描述更清晰，包含用途、输入、输出、使用时机
- ✅ 新增：高级Skills介绍和使用策略

**关键改进**:
```java
// 之前：强制按顺序执行
"### 第一步：信息完整性检查"
"### 第二步：获取表结构"
"### 第三步：生成SQL"
...

// 现在：ReAct循环
"### 1. Thought（思考）"
"### 2. Action（行动）"
"### 3. Observation（观察）"
"### 4. 重复或结束"
```

#### Step 2: 增强 AgentConfig ✅
**文件**: `NL2SQL-core/src/main/java/com/nl2sql/core/agent/AgentConfig.java`

**改动**:
- ✅ 添加可选Tools的动态注册（ContextSummarizerTool, ConversationMemoryTool等）
- ✅ 添加详细的初始化日志
- ✅ 支持条件化启用增强Tools
- ✅ 改进代码结构和注释

**新增功能**:
```java
// 动态注册可选Tools
if (contextSummarizerTool != null) {
    log.info("启用 ContextSummarizerTool");
    toolsBuilder.tools(contextSummarizerTool);
}
```

#### Step 3: 增强 Controller 监控 ✅
**文件**: `NL2SQL-web/src/main/java/com/nl2sql/web/controller/AgentController.java`

**改动**:
- ✅ 添加执行时间统计
- ✅ 添加详细的请求/响应日志
- ✅ 添加分隔符便于日志追踪
- ✅ 在响应中返回 executionTime

**日志示例**:
```
[Agent对话] ========== 开始处理 ==========
[Agent对话] 用户消息: 查询上月订单
[Agent对话] SessionId: xxx
[Agent对话] DatasourceId: 1
[Agent对话] 调用 NL2SQLAgent.chat()...
[Agent对话] Agent响应长度: 1234 字符
[Agent对话] 执行耗时: 5678 ms
[Agent对话] ========== 处理完成 ==========
```

---

### Phase 2: Skills 深度集成 (100% 完成)

#### Step 4: 创建 StandardQuerySkillTool ✅
**文件**: `NL2SQL-core/src/main/java/com/nl2sql/core/agent/tools/StandardQuerySkillTool.java`

**功能**:
- ✅ 将 StandardQuerySkill 包装成 LangChain4j Tool
- ✅ 提供清晰的 @Tool 描述
- ✅ 格式化输出结果（成功/失败/澄清/权限拒绝/成本过高）
- ✅ 添加数据预览（前5行）

**Agent如何使用**:
```
Agent思考："用户想要查询上月订单，这是一个明确的查询需求"
Agent行动：调用 execute_standard_query("查询上月订单", datasourceId=1, userId=123, username="user")
Agent观察：收到查询结果，包含数据、SQL、执行时间
Agent决策：是否需要进一步分析？如果需要，可以调用 summarize_data
```

#### Step 5: 创建 ReportWithInsightsSkillTool ✅
**文件**: `NL2SQL-core/src/main/java/com/nl2sql/core/agent/tools/ReportWithInsightsSkillTool.java`

**功能**:
- ✅ 将 ReportWithInsightsSkill 包装成 LangChain4j Tool
- ✅ 自动生成AI总结和图表推荐
- ✅ 格式化输出完整报告

**Agent如何使用**:
```
Agent思考："用户要求分析销售趋势并给出结论，这需要深度分析"
Agent行动：调用 generate_report_with_insights("分析销售趋势", datasourceId=1, userId=123, username="user")
Agent观察：收到包含数据、AI总结、图表推荐的完整报告
Agent决策：直接返回给用户，无需额外操作
```

#### Step 6: 注册 Skills 到 Agent ✅
**文件**: `NL2SQL-core/src/main/java/com/nl2sql/core/agent/AgentConfig.java`

**改动**:
- ✅ 注入 StandardQuerySkillTool 和 ReportWithInsightsSkillTool
- ✅ 条件化注册到 Agent
- ✅ 添加启动日志

**注册的Tools列表**:
```
核心Tools:
- NL2SQLService
- SQLExecutionTool
- AISummaryTool
- ChartRecommendationTool
- DatasourceClarificationTool

高级Skills:
- StandardQuerySkillTool ⭐ 新增
- ReportWithInsightsSkillTool ⭐ 新增

可选Tools:
- ContextSummarizerTool
- ConversationMemoryTool
- IntentClassifierTool
- SQLOptimizerTool
- ReportGeneratorTool
```

---

## 🚀 企业级生产环境路线图

### 核心痛点：SQL生成准确性
当前最大瓶颈是LLM生成的SQL不准确，主要表现为：
1. **表选择遗漏**：向量检索可能漏掉中间表
2. **JOIN条件错误**：生成IN子查询而非直接JOIN
3. **聚合校验缺失**：GROUP BY与SELECT不匹配
4. **时间解析局限**：复杂时间表达无法识别

---

### Phase 3: SQL准确性提升 (预计3周) 🔴 P0

#### 3.1 动态关联关系推断
**问题**：依赖`table_relationships`配置，新表未配置时LLM仍会遗漏
**方案**：
```java
// NL2SQLService.retrieveSchema后自动推断
- 分析字段命名模式（user_id → users.id）
- 检测外键约束信息
- 临时添加到Prompt上下文
```
**预期效果**：表选择准确率提升至95%+

#### 3.2 JOIN条件自动修复
**问题**：StandardQuerySkill仅警告IN子查询，未实际修复
**方案**：
```java
// StandardQuerySkill.optimizeSQL实现自动修复
if (检测到ON ... IN (SELECT)) {
    String fixedSql = nl2sqlTool.fixJoinCondition(sql, relationshipInfo);
}
```
**预期效果**：消除子查询导致的逻辑错误

#### 3.3 聚合校验机制
**问题**：MySQL严格模式下GROUP BY报错
**方案**：
```java
// 使用JSqlParser解析SQL并校验
private void validateAggregation(String sql) {
    // 检查非聚合列是否都在GROUP BY中
}
```
**预期效果**：避免语法错误

#### 3.4 时间表达式优化
**问题**：规则解析覆盖场景有限
**方案**：
```java
// 移除TimeExpressionParser，让LLM直接生成
Prompt: "WHERE created_at >= DATE_SUB(NOW(), INTERVAL 7 DAY)"
```
**预期效果**：支持"上季度"、"财年Q3"等复杂表达

---

### Phase 4: 性能与稳定性 (预计1周) 🔴 P0

#### 4.1 查询超时控制
**方案**：
```yaml
spring.datasource.hikari.connection-timeout=30000
statement.setQueryTimeout(60);
```

#### 4.2 大数据量LIMIT保护
**方案**：
```java
// SQLExecutor.executeQuery前强制添加
if (!sql.contains("LIMIT")) {
    sql += " LIMIT 1000";
}
```

#### 4.3 列名翻译缓存
**方案**：
```java
// Caffeine本地缓存，TTL 24小时
LoadingCache<String, String> cache = Caffeine.newBuilder()...
```
**预期效果**：响应延迟降低2-5秒

#### 4.4 连接池监控
**方案**：集成Micrometer + Prometheus

---

### Phase 5: 安全与权限 (预计2周) 🔴 P0

#### 5.1 SQL注入防护增强
**方案**：使用JSqlParser白名单校验
```java
if (!(stmt instanceof Select)) return false;
// 禁止UNION、INTO OUTFILE等
```

#### 5.2 列级权限集成
**方案**：SQLExecutor中应用ColumnPermissionService
```java
List<String> allowedColumns = permissionService.getAllowedColumns(userId, table);
String filteredSql = applyColumnFilter(sql, allowedColumns);
```

#### 5.3 审计日志持久化
**方案**：异步写入数据库
```java
@Async
public void saveAuditLog(AuditLog log) { ... }
```

---

### Phase 6: 可观测性与测试 (预计2周) 🟡 P1

#### 6.1 分布式追踪
**方案**：集成Micrometer Tracing + Zipkin

#### 6.2 LLM降级策略
**方案**：
```java
@Retryable(maxAttempts = 2)
public String chatWithFallback(String prompt) {
    try { return primaryModel.chat(prompt); }
    catch (Exception e) { return backupModel.chat(prompt); }
}
```

#### 6.3 慢查询告警
**方案**：executionTime > 5秒触发AlertService

#### 6.4 测试覆盖率提升
**目标**：核心模块单元测试覆盖率>80%
**方案**：
- 编写AgentController集成测试
- JMeter性能基准测试（P95 < 3秒）

---

### Phase 7: 功能完善 (预计2周) 🟡 P1

#### 7.1 多轮对话上下文
**方案**：NL2SQLTool维护最近3轮对话历史

#### 7.2 图表推荐优化
**方案**：LLM根据数据特征生成ECharts配置

#### 7.3 查询模板管理
**方案**：支持保存/复用常用查询

---

### Phase 8: 部署与运维 (预计1周) 🟢 P2

#### 8.1 容器化部署
**方案**：Dockerfile + Docker Compose

#### 8.2 配置中心集成
**方案**：Spring Cloud Config或Nacos

#### 8.3 健康检查增强
**方案**：CustomHealthIndicator检查Ollama/Redis/DB

---

## 📈 优先级总结

| 阶段 | 类别 | 关键项数 | 预计工作量 | 就绪度提升 |
|------|------|----------|------------|------------|
| **Phase 3** | SQL准确性 | 4项 | 3周 | 内部试用 → 可用 |
| **Phase 4** | 性能稳定 | 4项 | 1周 | 可用性提升 |
| **Phase 5** | 安全防护 | 3项 | 2周 | 达到安全底线 |
| **Phase 6** | 可观测性 | 4项 | 2周 | 运维友好 |
| **Phase 7** | 功能完善 | 3项 | 2周 | 体验优化 |
| **Phase 8** | 部署运维 | 3项 | 1周 | 生产就绪 |

**总计**：约11周（2人团队）

**最关键3项（如果只能选）：**
1. ✅ 动态关联关系推断 - 解决表选择根本问题
2. ✅ SQL超时 + LIMIT保护 - 防止生产事故
3. ✅ 列级权限集成 - 数据安全底线

---

## 🎯 改造效果

### 改造前 vs 改造后

| 维度 | 改造前 | 改造后 |
|------|--------|--------|
| **决策方式** | 固定7步流程 | ReAct循环，自主决策 |
| **工具选择** | 按顺序调用 | 根据需求自主选择 |
| **灵活性** | 低（硬编码） | 高（动态调整） |
| **可观测性** | 一般 | 强（详细日志+耗时统计） |
| **Skills支持** | 无 | 完整支持（2个高级Skills） |
| **扩展性** | 困难 | 容易（插件化Tools） |

### Agent能力提升

#### 1. 自主决策能力
```
用户问："帮我查一下上月的订单情况"

改造前Agent：
1. 强制调用 clarify_datasource
2. 强制调用 retrieve_schema
3. 强制调用 generate_sql
...（即使某些步骤不需要）

改造后Agent：
Thought: "用户提到'订单'，可能需要订单相关的数据源。让我先确认数据源。"
Action: clarify_datasource("帮我查一下上月的订单情况")
Observation: "推荐使用订单数据源"
Thought: "数据源已确认，现在需要获取表结构"
Action: retrieve_schema("上月订单", datasourceId=1)
Observation: "返回orders表结构"
Thought: "已有足够信息，可以直接使用标准查询技能"
Action: execute_standard_query("上月订单", datasourceId=1, userId=123, username="user")
Observation: "查询成功，返回100行数据"
Thought: "数据量较大，应该生成总结"
Action: summarize_data(...)
Final Answer: 返回完整结果
```

#### 2. 灵活的工具选择
```
场景1：简单查询
用户："查最近10条订单"
Agent → execute_standard_query() （一站式解决）

场景2：复杂分析
用户："分析上月销售趋势并给出建议"
Agent → generate_report_with_insights() （完整报告）

场景3：精细控制
用户："我想先看有哪些表，再生成SQL"
Agent → retrieve_schema() → generate_sql() → execute_sql() （分步执行）
```

#### 3. 错误处理能力
```
改造前：固定流程，错误处理僵化
改造后：Agent可以根据错误自主决定
- SQL执行失败 → 自动调用 auto_fix_sql
- 权限拒绝 → 告知用户原因
- 成本过高 → 建议优化方案
```

---

## 📈 下一步计划

### Phase 3: 增强推理能力 (50% 完成)
- [x] 实现SQL风险评估机制（StandardQuerySkill内部集成）
- [ ] 实现 Plan-and-Solve 机制
- [ ] 添加自我反思（Self-Reflection）
- [ ] 实现假设验证循环
- [ ] 添加多路径探索

### Phase 4: 记忆与上下文管理 (待执行)
- [ ] 实现分层记忆（短期/长期/工作记忆）
- [ ] 添加上下文压缩和摘要
- [ ] 实现关键信息提取和存储
- [ ] 添加记忆检索增强

### Phase 5: 高级功能 (待执行)
- [ ] 实现工具选择策略学习
- [ ] 添加错误恢复机制
- [ ] 实现并行工具调用
- [ ] 添加成本控制和限流

---

## 📈 下一步计划

### Phase 3: 增强推理能力 (待执行)
- [ ] 实现 Plan-and-Solve 机制
- [ ] 添加自我反思（Self-Reflection）
- [ ] 实现假设验证循环
- [ ] 添加多路径探索

### Phase 4: 记忆与上下文管理 (待执行)
- [ ] 实现分层记忆（短期/长期/工作记忆）
- [ ] 添加上下文压缩和摘要
- [ ] 实现关键信息提取和存储
- [ ] 添加记忆检索增强

### Phase 5: 高级功能 (待执行)
- [ ] 实现工具选择策略学习
- [ ] 添加错误恢复机制
- [ ] 实现并行工具调用
- [ ] 添加成本控制和限流

---

## 🔧 技术要点

### ReAct 模式核心
```
Loop:
  1. Thought: 分析问题，决定下一步
  2. Action: 调用一个Tool
  3. Observation: 查看Tool返回结果
  4. 判断：
     - 还需要更多信息？→ 继续循环
     - 已有足够信息？→ 返回 Final Answer
```

### Skills vs Tools
```
Tools（原子操作）:
- retrieve_schema
- generate_sql
- execute_sql
- ...

Skills（业务流程）:
- StandardQuerySkill = retrieve_schema + generate_sql + execute_sql + ...
- ReportWithInsightsSkill = StandardQuerySkill + summarize + recommend_chart

Agent可以自主选择：
- 简单任务 → 直接用Skill
- 复杂任务 → 手动组合Tools
```

---

## 📝 使用说明

### 编译项目
```powershell
.\build.ps1
```

### 启动应用
```powershell
.\start.ps1
```

### 测试 ReAct Agent
访问：http://localhost:8080/agent-chat.html

**测试用例1：简单查询**
```
用户：查询最近10条订单
预期：Agent调用 execute_standard_query，快速返回结果
```

**测试用例2：复杂分析**
```
用户：分析上月销售趋势并给出建议
预期：Agent调用 generate_report_with_insights，返回完整报告
```

**测试用例3：分步执行**
```
用户：先看看有哪些表
预期：Agent调用 retrieve_schema，返回表结构
用户：好的，帮我生成查询订单的SQL
预期：Agent调用 generate_sql，返回SQL语句
```

---

## 🎉 总结

### 已实现的核心能力
1. ✅ **真正的ReAct循环**：Thought-Action-Observation
2. ✅ **自主决策**：Agent根据情况选择Tools
3. ✅ **Skills集成**：2个高级Skills可用
4. ✅ **可观测性**：详细日志+耗时统计
5. ✅ **可扩展性**：插件化Tools架构
6. ✅ **SQL风险评估**：StandardQuerySkill内部集成LLM自主评估 + EXPLAIN辅助

### 关键突破
- 从“固定流程”到“自主决策”
- 从“单一粒度”到“多层抽象”（Tools + Skills）
- 从“黑盒执行”到“透明可观测”
- 从“无风险控制”到“智能风险评估与阻断”

### 下一步
详见上方**企业级生产环境路线图**（Phase 3-8），按优先级逐步实施。
