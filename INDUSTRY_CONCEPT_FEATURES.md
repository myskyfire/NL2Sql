# 行业概念管理功能实现说明

## 📋 功能概述

本次更新为NL2SQL系统添加了完整的**行业概念管理**功能，包括导航菜单入口、批量导入和审核流程三大核心功能。

---

## ✅ 已实现功能

### 1. 导航菜单入口

**文件**: `nl2sql-web/src/main/resources/static/index.html`

#### 修改内容：
- **侧边栏菜单**（第449-452行）：添加"行业概念管理"菜单项，带📚图标
- **首页卡片**（第554-558行）：添加欢迎卡片，点击可快速进入
- **页面容器**（第643-646行）：添加iframe容器加载子页面

#### 特性：
- ✅ 仅管理员可见（admin-only类）
- ✅ 响应式设计，支持移动端
- ✅ 通过iframe嵌入独立管理页面

---

### 2. 批量导入功能

**前端文件**: `nl2sql-web/src/main/resources/static/admin-industry-concepts.html`  
**后端文件**: `nl2sql-web/src/main/java/com/nl2sql/web/controller/IndustryConceptAdminController.java`

#### 前端实现：

**工具栏按钮**（第330行）：
```html
<button class="secondary" onclick="showImportModal()">📥 批量导入</button>
```

**批量导入模态框**（第432-489行）包含：
- 行业选择下拉框
- 文件上传控件（支持.csv/.xlsx/.xls）
- 导入选项：
  - ⏭️ 跳过重复概念（根据concept_key判断）
  - ✅ 自动审核通过（否则为待审核状态）
- 数据预览表格（显示前5条）

**JavaScript核心函数**：
- `showImportModal()` - 显示导入模态框
- `previewImport()` - 读取并预览CSV文件
- `parseCSV(content)` - 解析CSV格式
- `executeImport()` - 逐条调用API导入数据

#### CSV文件格式：
```csv
concept_type,concept_key,aliases,description
metric,revenue,"销售额,收入,GMV",销售总金额
entity,customer,"客户,顾客,用户",购买商品或服务的个人或企业
dimension,region,"地区,区域,省份",地理区域维度
```

**字段说明**：
- `concept_type`: 概念类型（entity/metric/dimension）
- `concept_key`: 概念键（英文标识，唯一）
- `aliases`: 别名（用逗号分隔的中文同义词）
- `description`: 描述信息（可选）

#### 后端API：
复用现有的创建概念接口：
```
POST /api/admin/industry-concepts/{industryCode}/concepts
```

请求体示例：
```json
{
  "conceptType": "metric",
  "conceptKey": "revenue",
  "aliases": ["销售额", "收入", "GMV"],
  "description": "销售总金额",
  "status": "approved"  // 或 "pending"
}
```

---

### 3. 审核流程

**前端文件**: `nl2sql-web/src/main/resources/static/admin-industry-concepts.html`  
**后端文件**: `nl2sql-web/src/main/java/com/nl2sql/web/controller/IndustryConceptAdminController.java`

#### 前端UI改进：

**表格增强**（第382-397行）：
- 添加复选框列（仅对"待审核"状态的概念启用）
- 全选/取消全选功能
- 操作列显示审核按钮（仅对待审核概念）

**批量操作栏**（第353-360行）：
```html
<div class="batch-actions" id="batchActions">
    <span id="selectedCount">已选择 0 项</span>
    <button onclick="batchApprove()" style="background: #52c41a;">✅ 批量通过</button>
    <button onclick="batchReject()" style="background: #ff4d4f;">❌ 批量拒绝</button>
</div>
```

**样式**（第54-73行）：
- 默认隐藏，选中项目后显示
- 蓝色背景高亮提示
- 实时显示已选择数量

#### JavaScript审核函数：

**单个审核**：
- `approveConcept(id)` - 通过单个概念
- `rejectConcept(id)` - 拒绝单个概念

**批量审核**：
- `toggleSelectAll()` - 全选/取消全选
- `updateBatchActions()` - 更新批量操作栏状态
- `batchApprove()` - 批量通过选中概念
- `batchReject()` - 批量拒绝选中概念

#### 后端API：

**批量审核接口**（第158-185行）：
```java
@PostMapping("/concepts/batch-approve")
public Map<String, Object> batchApprove(@RequestBody Map<String, Object> request)
```

**请求参数**：
```json
{
  "ids": [1, 2, 3],
  "action": "approve"  // 或 "reject"
}
```

**响应示例**：
```json
{
  "success": true,
  "message": "已通过 3 条概念"
}
```

**数据库操作**：
```sql
UPDATE industry_concept 
SET status = 'approved'  -- 或 'rejected'
WHERE id IN (1, 2, 3)
```

---

## 🎯 使用场景

### 场景1：批量导入行业术语

1. 准备CSV文件（参考`test_import_concepts.csv`）
2. 登录系统 → 点击"行业概念管理"
3. 选择目标行业
4. 点击"📥 批量导入"
5. 上传CSV文件
6. 点击"👁️ 预览"查看数据
7. 勾选"自动审核通过"（可选）
8. 点击"✅ 开始导入"

### 场景2：审核用户反馈的新术语

1. 筛选状态为"待审核"的概念
2. 查看概念详情（别名、描述等）
3. 勾选需要审核的概念
4. 点击"✅ 批量通过"或"❌ 批量拒绝"
5. 系统更新状态并刷新列表

### 场景3：从用户查询中学习

当用户查询新术语并获得满意结果时：
1. 系统提示："是否将'GMV'添加到行业词典？"
2. 用户点击"是"
3. 调用`/learn-from-feedback` API
4. 新概念以"待审核"状态入库
5. 管理员审核后生效

---

## 📊 数据流程

```
用户上传CSV
    ↓
前端解析并预览
    ↓
逐条调用POST API
    ↓
后端验证并插入数据库
    ↓
状态：approved（自动通过）或 pending（待审核）
    ↓
管理员批量审核（如需要）
    ↓
状态变更为 approved/rejected
    ↓
NL2SQL引擎使用已批准的概念
```

---

## 🔧 技术细节

### 前端技术栈
- HTML5 + CSS3（Flexbox布局）
- 原生JavaScript（ES6+ async/await）
- FileReader API（文件读取）
- Fetch API（HTTP请求）

### 后端技术栈
- Spring Boot 3.2.5
- JdbcTemplate
- RESTful API设计
- Stream API（批量处理）

### 数据库表结构
```sql
CREATE TABLE industry_concept (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    industry_code VARCHAR(50) NOT NULL,
    concept_type VARCHAR(20) NOT NULL,  -- entity/metric/dimension
    concept_key VARCHAR(100) NOT NULL,   -- 唯一标识
    concept_aliases TEXT,                -- JSON数组格式
    description TEXT,
    usage_count INT DEFAULT 0,
    source VARCHAR(50),                  -- manual/user_feedback/system
    status VARCHAR(20) DEFAULT 'approved', -- approved/pending/rejected
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_industry_key (industry_code, concept_key)
);
```

---

## 📝 测试文件

项目根目录提供测试CSV文件：
- `test_import_concepts.csv` - 包含10个电商行业常用概念

测试步骤：
1. 启动应用：`mvn spring-boot:run -pl nl2sql-web`
2. 访问：http://localhost:8080
3. 登录管理员账号
4. 进入"行业概念管理"
5. 点击"批量导入"测试上传

---

## ✨ 功能亮点

1. **用户体验优化**
   - 直观的拖拽上传界面
   - 实时数据预览
   - 批量操作减少重复劳动
   - 清晰的状态标识（颜色徽章）

2. **数据安全**
   - UTF-8编码支持中文
   - 重复检测避免数据冗余
   - 审核机制防止错误数据入库
   - 事务保证数据一致性

3. **灵活配置**
   - 支持手动添加和批量导入
   - 可选择自动审核或人工审核
   - 支持多种概念类型
   - 可扩展的别名系统

4. **性能优化**
   - 前端逐条导入，避免大数据量超时
   - 后端使用JDBC批量更新
   - 索引优化查询性能
   - 缓存机制减少数据库压力

---

## 🚀 后续优化建议

1. **Excel支持**：当前仅支持CSV，可集成Apache POI支持.xlsx格式
2. **异步导入**：大数据量时使用后台任务+进度条
3. **导入日志**：记录每次导入的详细结果
4. **版本控制**：跟踪概念的变更历史
5. **智能推荐**：基于用户查询自动推荐新概念
6. **导出功能**：支持将概念库导出为CSV/Excel

---

## 📞 技术支持

如有问题，请检查：
1. 浏览器控制台是否有JavaScript错误
2. 后端日志（logs/NL2SQL.log）
3. 数据库连接是否正常
4. 用户是否具有管理员权限

---

**更新日期**: 2026-04-20  
**版本**: v1.0.0  
**编译状态**: ✅ BUILD SUCCESS
