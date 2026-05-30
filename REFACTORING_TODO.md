# NL2SQL 架构改造 TODO 清单

**创建时间**: 2026-05-06  
**状态**: 待执行  
**优先级**: P0（高优先级）

---

## 📋 改造背景

当前系统存在以下架构问题需要重构：

1. **ReAct Agent 名不副实**：实际是 Pipeline 模式，但命名为 ReAct Agent
2. **LLM 调用冗余**：Orchestrator 层调用 LLM，但 90% 场景走 DIRECT 路由跳过 LLM
3. **路由规则硬编码**：SkillRouter 中硬编码意图→Skill 映射，不支持热插拔
4. **Groovy 脚本职责过重**：StandardQuerySkill.groovy ~1390 行，违反单一职责原则

---

## 🎯 改造目标

### 核心原则
- ✅ **Orchestrator 纯路由**：移除 LLM 调用，只做意图识别和路由
- ✅ **LLM 下沉到 Tool**：只在 GenerateSQLTool 等需要的地方调用 LLM
- ✅ **Skills 真热插拔**：基于 SKILL.md 元数据自动路由，无需修改代码
- ✅ **意图类型动态化**：从枚举改为字符串，支持自定义意图

---

## 📝 详细任务清单

### P0-1：架构正名与清理（预计 2-3 小时）

#### 任务 1.1：重命名核心类
- [ ] `ReActAgent` → `NL2SQLPipelineOrchestrator`
- [ ] `ReActAgentConfig` → `PipelineOrchestratorConfig`（如果存在）
- [ ] 更新所有引用位置（IDE 重构功能）
- [ ] 更新日志输出：`[ReActAgent]` → `[PipelineOrchestrator]`

#### 任务 1.2：删除 ReAct 循环相关代码
- [ ] 删除 `executeReActLoop()` 方法
- [ ] 删除 `executeFullReAct()` 方法
- [ ] 删除 `executeWithFilteredTools()` 方法
- [ ] 删除 `MAX_ITERATIONS` 常量
- [ ] 删除 `llmService` 字段（Orchestrator 不再需要）
- [ ] 删除 `tools` Map 及相关注册逻辑

#### 任务 1.3：简化 execute() 方法
```java
// 改造后应该是这样：
public String execute(String userMessage, Long datasourceId, List<Map> history) {
    // 1. 意图分类
    String intent = intentClassifier.classify(userMessage, history);
    
    // 2. 路由到对应 Skill/Pipeline
    List<String> recommendedSkills = skillRouter.route(intent);
    
    // 3. 直接调用 Skill（无 LLM 参与）
    return executeSkill(recommendedSkills.get(0), userMessage, datasourceId, history);
}
```

#### 任务 1.4：更新 System Prompt
- [ ] 移除 "禁止输出思考过程" 等 ReAct 相关描述
- [ ] 明确说明这是 Pipeline Orchestrator
- [ ] 增加多轮对话规则说明
- [ ] **注意**：System Prompt 应该移到 GenerateSQLTool 内部，因为 Orchestrator 不再调用 LLM

---

### P0-2：Skills 热插拔路由重构（预计 3-4 小时）

#### 任务 2.1：扩展 SKILL.md 元数据
- [ ] 在 `standard-query/SKILL.md` 中添加 `applicableIntents` 字段
- [ ] 在其他 SKILL.md 中同步添加
- [ ] 示例：
  ```yaml
  applicableIntents:
    - query
    - follow_up
    - data_exploration
  ```

#### 任务 2.2：修改 SkillsMetadataLoader
- [ ] 在 `SkillMetadata` 类中新增 `applicableIntents` 字段
- [ ] 解析 YAML 时提取该字段
- [ ] 提供 `getSkillsByIntent(String intent)` 方法

#### 任务 2.3：重构 SkillRouter
- [ ] 删除硬编码的 `intentToSkillsMap` 初始化逻辑
- [ ] 改为从 `SkillsMetadataLoader` 自动构建映射
- [ ] 使用 `@PostConstruct` 在启动时加载
- [ ] 支持动态刷新（可选，监听文件变化）

#### 任务 2.4：意图类型动态化
- [ ] 将 `IntentType` 枚举改为字符串类型
- [ ] 修改 `IntentClassifier` 返回 `String` 而非枚举
- [ ] 支持自定义意图类型（如 "report", "export", "visualization"）

#### 任务 2.5：验证热插拔
- [ ] 新增一个测试 SKILL.md，验证无需修改 Java 代码即可被识别
- [ ] 验证路由规则自动生效

---

### P1：Groovy 脚本拆分（预计 1-2 天）

#### 任务 3.1：创建原子 Tool
- [ ] `ExecuteWithRetryTool` - 执行重试逻辑
- [ ] `AutoFixSQLTool` - SQL 自动修正
- [ ] `ChartGeneratorTool` - 图表生成
- [ ] 确保每个 Tool 有独立单元测试

#### 任务 3.2：简化 StandardQuerySkill.groovy
- [ ] 从 ~1390 行简化到 ~200 行
- [ ] 只保留编排逻辑，具体实现委托给原子 Tool
- [ ] 保持对外接口不变

#### 任务 3.3：回归测试
- [ ] 验证标准查询功能正常
- [ ] 验证异常场景处理（超时、失败、修正）
- [ ] 性能对比（延迟不应增加 >10%）

---

### P2：Workflow 引擎（预计 1-2 天）

#### 任务 4.1：实现 WorkflowEngine
- [ ] 解析 YAML workflow 定义
- [ ] 支持线性流程执行
- [ ] 支持条件分支（if-then-else）
- [ ] 支持变量替换（`${steps.xxx.output}`）

#### 任务 4.2：迁移 standard-query 到 YAML
- [ ] 创建 `standard-query/workflow.yaml`
- [ ] 声明式定义 5 步流程
- [ ] 保留 Groovy 作为 fallback

#### 任务 4.3：验证
- [ ] YAML 版本功能与 Groovy 版本一致
- [ ] 性能对比

---

### P3：动态 Skill 发现（预计 1 天）

#### 任务 5.1：修改 SkillsMetadataLoader
- [ ] 从硬编码目录扫描改为自动扫描 `classpath*:skills/**/SKILL.md`
- [ ] 支持热加载（可选）

#### 任务 5.2：实现 SkillContext
- [ ] 提供 `callSkill()` 方法支持 Skill 间调用
- [ ] 管理全局上下文（userId、sessionId 等）
- [ ] 检测循环调用（最大深度限制）

#### 任务 5.3：重构 report-with-insights
- [ ] 改用 `skillContext.callSkill("execute_standard_query", params)`
- [ ] 移除对 Bean 的直接引用

---

## 🔍 验收标准

### 功能验收
- [ ] 所有现有查询场景正常工作
- [ ] 多轮对话支持正常
- [ ] 新增 SKILL.md 后重启应用能自动识别
- [ ] 意图分类准确率不低于当前水平

### 性能验收
- [ ] 平均查询延迟 ≤2.2s（增加 ≤10%）
- [ ] P95 延迟 ≤3.3s
- [ ] 启动时间 ≤11s

### 代码质量验收
- [ ] StandardQuerySkill.groovy ≤250 行
- [ ] 单元测试覆盖率 ≥80%
- [ ] 编译警告为 0

---

## ⚠️ 注意事项

### 高风险操作
1. **Groovy 脚本拆分**：需确保所有异常场景都被覆盖
2. **Workflow Engine**：初期只支持线性流程，暂不实现复杂分支

### 中风险操作
1. **意图分类器改造**：需对比测试准确率
2. **Skill 动态发现**：需处理 SKILL.md 格式错误

### 低风险操作
1. **类重命名**：使用 IDE 重构功能可安全完成
2. **System Prompt 优化**：不影响代码逻辑

---

## 📅 实施计划

| 阶段 | 工作内容 | 预计工期 | 计划日期 |
|------|---------|---------|---------|
| P0-1 | 架构正名与清理 | 2-3 小时 | 今晚 |
| P0-2 | Skills 热插拔路由重构 | 3-4 小时 | 今晚 |
| P1 | Groovy 脚本拆分 | 1-2 天 | TBD |
| P2 | Workflow 引擎 | 1-2 天 | TBD |
| P3 | 动态 Skill 发现 | 1 天 | TBD |

**总计**：P0 阶段约 5-7 小时，可在今晚完成

---

## 📚 参考文档

- [ARCHITECTURE_REFACTOR_DESIGN.md](./ARCHITECTURE_REFACTOR_DESIGN.md) - 详细设计文档
- [P1_1_COMPLETION_REPORT.md](./P1_1_COMPLETION_REPORT.md) - P1-1 改造报告（历史参考）

---

**最后更新**: 2026-05-06  
**下次 review**: 完成 P0 阶段后
