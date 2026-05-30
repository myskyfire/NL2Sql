# A/B 测试功能使用指南

> 📌 **文档版本**: 1.0  
> 📅 **最后更新**: 2026-05-03  
> 👥 **适用角色**: 系统管理员、Prompt 工程师

---

## 📖 功能概述

### 什么是 A/B 测试？

A/B 测试是一种科学的 Prompt 优化方法，通过**流量分割**和**对比实验**，帮助你：

- ✅ **客观评估**不同 Prompt 版本的实际效果
- ✅ **数据驱动**决策，避免主观臆断
- ✅ **平滑切换**新版本，降低上线风险
- ✅ **持续优化**生成质量，提升用户满意度

### 核心原理

```
用户提问 → 系统根据 sessionId 哈希 → 分配版本 A 或版本 B
                ↓
        记录使用日志（问题、SQL、评分）
                ↓
        统计对比（样本数、平均评分、好评率）
                ↓
        停止测试 → 自动激活优胜版本
```

### 适用场景

| 场景 | 版本 A | 版本 B | 目标 |
|------|--------|--------|------|
| **优化效果** | 现有 Prompt（稳定） | 加入新规则的 Prompt | 提高准确率 |
| **调整风格** | 详细解释型 | 简洁直接型 | 找到用户偏好 |
| **测试示例** | 旧 Few-shot 示例 | 新 Few-shot 示例 | 验证示例质量 |
| **参数调优** | 默认参数 | 调整后的参数 | 优化输出质量 |

---

## 🚀 快速开始

### 步骤 1：访问管理后台

**访问地址**: `http://localhost:8080/admin/prompt-learning.html`

1. 使用管理员账号登录
2. 点击顶部导航栏的 **"🧪 A/B Tests"** 标签页

---

### 步骤 2：创建 Prompt 版本

在创建 A/B 测试之前，需要准备至少两个不同版本的 Prompt。

#### 2.1 点击"创建版本"

在 **"Prompt Versions"** 标签页点击 **"创建版本"** 按钮。

#### 2.2 填写表单

```
版本名称：v2.0-optimized
Prompt 类型：DataMind 生成
Prompt 内容：
    你是一个专业的 SQL 生成助手。请根据用户问题和数据库结构，
    生成准确的 SQL 查询语句。
    
    【新要求】
    1. 优先使用 INNER JOIN
    2. 日期字段使用 DATE() 函数处理
    3. 金额字段保留 2 位小数
    
描述：优化 JOIN 策略和日期处理逻辑
☑ 设为默认版本（可选）
```

#### 2.3 提交创建

点击 **"创建版本"** 按钮，系统会返回版本 ID（例如：`1`、`2`）。

#### 2.4 创建两个版本

- **版本 A**（保守版本）：当前使用的稳定版本，ID = 1
- **版本 B**（实验版本）：优化后的新版本，ID = 2

---

### 步骤 3：创建 A/B 测试

切换到 **"🧪 A/B Tests"** 标签页，点击 **"创建测试"** 按钮。

#### 填写测试配置

```
测试名称：DataMind v2.0 效果测试
Prompt 类型：DataMind 生成
版本 A ID: 1
版本 B ID: 2
流量分配（A:B）：50:50
最小样本数：100
```

#### 参数说明

| 参数 | 说明 | 推荐值 |
|------|------|--------|
| **测试名称** | 测试的描述性名称 | 包含版本号和测试目标 |
| **Prompt 类型** | 选择要测试的 Prompt 类别 | 目前仅支持 `nl2sql` |
| **版本 A ID** | 保守版本的 ID | 从版本列表获取 |
| **版本 B ID** | 实验版本的 ID | 从版本列表获取 |
| **流量分配** | A 版本占总流量的百分比 | 初期 `50:50`，保守 `80:20` |
| **最小样本数** | 每个版本至少收集的样本数 | 建议 `100+` |

#### 提交测试

点击 **"创建测试"**，系统会提示"✅ A/B 测试创建成功！"

---

### 步骤 4：运行测试

测试创建成功后会**自动开始运行**，状态为 `RUNNING`。

#### 流量分配机制

```java
// 根据 sessionId 哈希决定使用哪个版本
int hash = Math.abs(sessionId.hashCode() % 100);
if (hash < trafficSplit) {
    return versionAId;  // 使用版本 A
} else {
    return versionBId;  // 使用版本 B
}
```

**关键点**：
- ✅ **同一个 sessionId 始终使用同一个版本**（保证一致性）
- ✅ **不同 sessionId 随机分配**（避免偏差）
- ✅ **自动记录日志**（用于后续统计）

#### 监控测试进度

在测试列表中查看实时数据：

| ID | 测试名称 | Prompt 类型 | 状态 | 流量分配 | 样本数 | 创建时间 |
|----|----------|------------|------|----------|--------|----------|
| 1 | DataMind v2.0 效果测试 | nl2sql | 🟢 RUNNING | 50:50 | 45/200 | 2026-05-03 10:30 |

---

### 步骤 5：查看测试统计

点击测试列表中的 **"统计"** 按钮，查看详细数据：

```
测试名称：DataMind v2.0 效果测试
状态：RUNNING
已运行时间：2 天 15 小时

版本 A 统计：
  - 使用次数：52
  - 平均评分：4.2
  - 好评率（5 星）：65%

版本 B 统计：
  - 使用次数：48
  - 平均评分：4.5
  - 好评率（5 星）：75%

当前领先：版本 B ⬆️
```

---

### 步骤 6：停止测试并确定优胜者

当样本数达到最小样本数（例如 100）后，可以停止测试。

#### 6.1 点击"停止"按钮

在测试列表中找到对应测试，点击 **"停止"** 按钮。

#### 6.2 系统自动评估

系统会自动执行以下操作：

```java
// 比较两个版本的平均评分
Double ratingA = versionA_avg_rating;  // 例如：4.2
Double ratingB = versionB_avg_rating;  // 例如：4.5

// 自动激活评分高的版本
if (ratingB > ratingA) {
    activatePromptVersion(versionBId);
    log.info("自动激活优胜版本：B");
}
```

#### 6.3 测试结果

测试状态更新为 `COMPLETED`：

| ID | 测试名称 | 状态 | 获胜版本 | 结束时间 |
|----|----------|------|----------|----------|
| 1 | DataMind v2.0 效果测试 | ✅ COMPLETED | 版本 B | 2026-05-05 14:20 |

**优胜版本自动激活**，成为系统默认使用的 Prompt 版本。

---

## 📊 API 接口文档

### 1. 创建 A/B 测试

**接口**: `POST /api/admin/prompt-learning/ab-tests`

**请求**:
```http
POST /api/admin/prompt-learning/ab-tests
Content-Type: application/json
Authorization: Bearer <your_token>

{
    "testName": "DataMind v2.0 效果测试",
    "promptType": "nl2sql",
    "versionAId": 1,
    "versionBId": 2,
    "trafficSplit": 50.0,
    "minSamples": 100
}
```

**响应**:
```json
{
    "code": 200,
    "message": "success",
    "data": 1,  // 测试 ID
    "timestamp": 1714737000000
}
```

---

### 2. 列出所有测试

**接口**: `GET /api/admin/prompt-learning/ab-tests`

**请求**:
```http
GET /api/admin/prompt-learning/ab-tests?status=RUNNING
Authorization: Bearer <your_token>
```

**参数**:
- `status`（可选）: 筛选状态（`RUNNING`、`COMPLETED`）

**响应**:
```json
{
    "code": 200,
    "data": [
        {
            "id": 1,
            "testName": "DataMind v2.0 效果测试",
            "promptType": "nl2sql",
            "status": "RUNNING",
            "trafficSplit": 50.0,
            "minSamples": 100,
            "startedAt": "2026-05-03T10:30:00",
            "versionAId": 1,
            "versionBId": 2
        }
    ]
}
```

---

### 3. 获取测试统计

**接口**: `GET /api/admin/prompt-learning/ab-tests/{id}/stats`

**请求**:
```http
GET /api/admin/prompt-learning/ab-tests/1/stats
Authorization: Bearer <your_token>
```

**响应**:
```json
{
    "code": 200,
    "data": {
        "id": 1,
        "testName": "DataMind v2.0 效果测试",
        "status": "RUNNING",
        "versionAId": 1,
        "versionBId": 2,
        "versionAUsageCount": 52,
        "versionAAvgRating": 4.2,
        "versionBUsageCount": 48,
        "versionBAvgRating": 4.5,
        "versionA5StarRate": 0.65,
        "versionB5StarRate": 0.75
    }
}
```

---

### 4. 停止测试

**接口**: `POST /api/admin/prompt-learning/ab-tests/{id}/stop`

**请求**:
```http
POST /api/admin/prompt-learning/ab-tests/1/stop
Authorization: Bearer <your_token>
```

**响应**:
```json
{
    "code": 200,
    "message": "测试已停止",
    "data": {
        "winnerVersionId": 2
    }
}
```

---

## 💡 最佳实践

### 1. 流量分配策略

#### 初期快速验证（推荐）
```
流量分配：50:50
最小样本数：100
目的：快速收集数据，验证效果
```

#### 保守灰度发布
```
流量分配：80:20（80% 流量用稳定版）
最小样本数：200
目的：降低新版本风险，逐步验证
```

#### 大规模验证
```
流量分配：50:50
最小样本数：500+
目的：获得统计显著性结果
```

---

### 2. 测试时长建议

| 测试阶段 | 时长 | 样本数目标 |
|----------|------|------------|
| **快速验证** | 3-7 天 | 100+ |
| **标准测试** | 1-2 周 | 200-500 |
| **深度验证** | 2-4 周 | 500+ |

**注意**：
- ✅ 覆盖完整业务周期（工作日 + 周末）
- ✅ 避免节假日等异常时段
- ✅ 确保样本多样性

---

### 3. 评估指标解读

#### 主要指标：平均评分
```
平均评分 = 所有评分之和 / 评分次数
```
- **4.5+**: 优秀 ⭐⭐⭐⭐⭐
- **4.0-4.5**: 良好 ⭐⭐⭐⭐
- **3.5-4.0**: 一般 ⭐⭐⭐
- **< 3.5**: 需要优化 ⭐⭐

#### 辅助指标：好评率
```
好评率 = 5 星评分次数 / 总评分次数
```
- **> 70%**: 用户高度认可
- **50-70%**: 大多数用户满意
- **< 50%**: 需要改进

#### 统计显著性
当两个版本的评分差异 **> 0.3 分** 且样本数 **> 100** 时，结果具有统计显著性。

---

### 4. 常见问题

#### Q1: 测试运行多久合适？
**A**: 建议至少 1-2 周，或直到样本数达到最小样本数的 2 倍。

#### Q2: 可以中途修改流量分配吗？
**A**: 不支持。如需调整，请停止当前测试并创建新测试。

#### Q3: 如何判断测试结果可靠？
**A**: 
- 样本数 > 100
- 评分差异 > 0.3 分
- 覆盖不同时间段

#### Q4: 测试结束后必须激活优胜版本吗？
**A**: 系统会自动激活。如需手动干预，可在版本管理中操作。

#### Q5: 可以同时进行多个 A/B 测试吗？
**A**: 每个 Prompt 类型只能有一个运行中的测试。

---

## 🔧 技术实现

### 数据库表结构

#### prompt_ab_tests（A/B 测试表）
```sql
CREATE TABLE prompt_ab_tests (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    test_name VARCHAR(255) NOT NULL COMMENT '测试名称',
    prompt_type VARCHAR(50) NOT NULL COMMENT 'Prompt 类型',
    version_a_id BIGINT NOT NULL COMMENT '版本 A ID',
    version_b_id BIGINT NOT NULL COMMENT '版本 B ID',
    traffic_split DOUBLE DEFAULT 50.0 COMMENT '流量分配（A 占比%）',
    min_samples INT DEFAULT 100 COMMENT '最小样本数',
    status VARCHAR(20) DEFAULT 'RUNNING' COMMENT '状态',
    started_at DATETIME DEFAULT NOW() COMMENT '开始时间',
    ended_at DATETIME COMMENT '结束时间',
    winner_version_id BIGINT COMMENT '获胜版本 ID',
    created_by BIGINT COMMENT '创建人 ID',
    created_at DATETIME DEFAULT NOW()
);
```

#### prompt_version_usage（使用记录表）
```sql
CREATE TABLE prompt_version_usage (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    version_id BIGINT NOT NULL COMMENT '版本 ID',
    session_id VARCHAR(100) COMMENT '会话 ID',
    user_id BIGINT COMMENT '用户 ID',
    question TEXT COMMENT '用户问题',
    generated_sql TEXT COMMENT '生成的 SQL',
    actual_sql TEXT COMMENT '实际执行的 SQL',
    rating INT COMMENT '评分（1-5）',
    feedback TEXT COMMENT '反馈内容',
    created_at DATETIME DEFAULT NOW()
);
```

---

### 核心代码逻辑

#### 流量分配
```java
public Long getPromptVersionForABTest(String promptType, String sessionId) {
    // 查找正在运行的 A/B 测试
    String testSql = "SELECT * FROM prompt_ab_tests WHERE prompt_type = ? AND status = 'RUNNING' LIMIT 1";
    List<Map<String, Object>> tests = jdbcTemplate.queryForList(testSql, promptType);
    
    if (tests.isEmpty()) {
        return getDefaultVersionId(promptType);  // 没有测试，返回默认版本
    }
    
    Map<String, Object> test = tests.get(0);
    double trafficSplit = ((Number) test.get("traffic_split")).doubleValue();
    Long versionAId = ((Number) test.get("version_a_id")).longValue();
    Long versionBId = ((Number) test.get("version_b_id")).longValue();
    
    // 根据 sessionId 哈希决定使用哪个版本
    int hash = Math.abs(sessionId.hashCode() % 100);
    Long selectedVersionId = hash < trafficSplit ? versionAId : versionBId;
    
    // 记录使用日志
    recordUsage(selectedVersionId, sessionId, null, null, null, null, null);
    
    return selectedVersionId;
}
```

#### 停止测试并激活优胜者
```java
public void stopABTest(Long testId) {
    // 获取测试结果
    Map<String, Object> stats = getABTestStats(testId);
    
    Double ratingA = (Double) stats.get("versionAAvgRating");
    Double ratingB = (Double) stats.get("versionBAvgRating");
    
    Long winnerId = null;
    if (ratingA != null && ratingB != null) {
        winnerId = ratingA > ratingB ? 
            ((Number) stats.get("versionAId")).longValue() : 
            ((Number) stats.get("versionBId")).longValue();
    }
    
    // 更新测试状态
    String sql = "UPDATE prompt_ab_tests SET status = 'COMPLETED', ended_at = NOW(), winner_version_id = ? WHERE id = ?";
    jdbcTemplate.update(sql, winnerId, testId);
    
    // 自动激活优胜版本
    if (winnerId != null) {
        activatePromptVersion(winnerId);
        log.info("A/B 测试完成，自动激活优胜版本：testId={}, winnerId={}", testId, winnerId);
    }
}
```

---

## 📝 示例场景

### 场景 1：优化 JOIN 策略

**背景**: 用户反馈多表关联查询准确率低

**版本 A**（当前版本）:
```
你是一个 SQL 助手，根据用户问题生成 SQL。
```

**版本 B**（优化版本）:
```
你是一个 SQL 助手，根据用户问题生成 SQL。

【优化规则】
1. 多表关联时优先使用 INNER JOIN
2. 关联字段必须使用完全限定名（表名。字段名）
3. 避免使用隐式 JOIN（WHERE 中的等值条件）
```

**测试配置**:
```
测试名称：JOIN 策略优化测试
流量分配：50:50
最小样本数：150
```

**预期结果**: 版本 B 的多表查询准确率提升 15%+

---

### 场景 2：调整输出风格

**背景**: 不确定用户偏好详细解释还是简洁 SQL

**版本 A**（详细型）:
```
生成 SQL 时，请包含：
1. SQL 语句
2. 简要说明
3. 关键字段解释
```

**版本 B**（简洁型）:
```
生成 SQL 时，只返回 SQL 语句，不需要额外说明。
```

**测试配置**:
```
测试名称：输出风格偏好测试
流量分配：50:50
最小样本数：200
```

**预期结果**: 根据评分确定用户偏好

---

### 场景 3：测试 Few-shot 示例

**背景**: 验证新示例是否有助于提升效果

**版本 A**（旧示例）:
```
示例 1:
问题：查询订单总额
SQL: SELECT SUM(total_amount) FROM orders
```

**版本 B**（新示例）:
```
示例 1:
问题：查询订单总额
SQL: SELECT SUM(total_amount) FROM orders WHERE is_deleted = 0

示例 2:
问题：查询活跃用户数
SQL: SELECT COUNT(DISTINCT user_id) FROM orders WHERE status != 'CANCELLED'
```

**测试配置**:
```
测试名称：Few-shot 示例优化测试
流量分配：50:50
最小样本数：100
```

**预期结果**: 版本 B 的 SQL 质量更稳定

---

## ⚠️ 注意事项

### 1. 数据一致性

- ✅ 同一个 sessionId 始终使用同一个版本
- ✅ 避免在测试期间修改版本内容
- ✅ 确保两个版本同时可用

### 2. 样本质量

- ✅ 鼓励用户对 SQL 进行评分
- ✅ 收集低分反馈原因
- ✅ 过滤异常数据（如测试账号）

### 3. 测试环境

- ⚠️ **仅在生产环境进行 A/B 测试**（需要真实用户数据）
- ⚠️ 避免在开发/测试环境运行（数据不具代表性）

### 4. 伦理考虑

- ✅ 确保两个版本都是安全的
- ✅ 避免明显劣质的版本占用流量
- ✅ 及时停止表现差的版本

---

## 🔗 相关文档

- [Prompt 版本管理指南](./PROMPT_VERSION_GUIDE.md)
- [用户反馈系统说明](./FEEDBACK_SYSTEM.md)
- [系统架构介绍](./SYSTEM_INTRODUCTION.md)
- [功能概览](./FEATURES_OVERVIEW.md)

---

## 📞 技术支持

如有问题，请联系：
- 📧 Email: support@datamind.ai
- 💬 内部群：DataMind 技术交流群
- 📖 文档库：`/NL2Sql/docs/`

---

**最后更新**: 2026-05-03  
**维护团队**: DataMind AI Core Team
