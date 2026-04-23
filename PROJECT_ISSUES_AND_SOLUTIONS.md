# NL2SQL 项目关键技术问题与解决方案

## 1. Agent架构设计问题

### 问题1.1：Controller硬编码调用Tool违背Agent原则
**时间**：2026-04-10  
**现象**：在AgentController中通过if-else判断消息内容，直接调用AISummaryTool  
**影响**：LLM失去自主决策能力，变成伪Agent  

**错误代码示例**：
```java
// ❌ 错误做法
if (request.getMessage().contains("请对以下查询结果进行AI分析总结")) {
    return handleAISummaryRequest(request, userInfo, startTime);
}
```

**根本原因**：
- 开发者习惯传统MVC路由思维
- 没有理解ReAct Agent的核心是LLM自主决策

**正确方案**：
1. 前端传递意图标识：`[INTENT:AI_SUMMARY]`
2. System Prompt定义规则：告诉LLM看到标记后调用哪个工具
3. LLM自主解析并调用工具
4. Controller只做透传，不干预业务逻辑

**经验教训**：
- ✅ Agent架构中，Controller职责仅限于：认证、参数验证、请求转发
- ✅ 所有业务决策必须由LLM完成
- ✅ 通过System Prompt引导LLM行为，而非代码硬编码

---

### 问题1.2：Context数据未传递给LLM
**时间**：2026-04-10  
**现象**：前端传递context字段，但LLM收不到，导致提取不到SQL  

**日志表现**：
```
User: [INTENT:AI_SUMMARY] 请对以下查询结果进行AI分析总结
Assistant: {"name": "summarize_result", "arguments": {"context": {"lastQuery": "...", "generatedSQL": ""}}}
```

**根本原因**：
- ReActAgent.execute()方法只接收userMessage字符串
- ChatRequest中的context字段没有被使用
- LLM只能从对话历史中提取信息，看不到额外参数

**解决方案**：
在Controller中将context序列化为JSON，拼接到message中：
```java
String fullMessage = request.getMessage();
if (request.getContext() != null && !request.getContext().isEmpty()) {
    String contextJson = mapper.writeValueAsString(request.getContext());
    fullMessage = request.getMessage() + "\n\nContext: " + contextJson;
}
reActAgent.execute(fullMessage, ...);
```

**LLM收到的完整消息**：
```
User: [INTENT:AI_SUMMARY] 请对以下查询结果进行AI分析总结

Context: {"lastQuery":"统计订单","generatedSQL":"SELECT..."}
```

**经验教训**：
- ✅ LLM只能处理文本，复杂结构需要序列化
- ✅ 在Prompt工程中，清晰的格式比隐藏的参数更有效
- ✅ 调试时要检查LLM实际收到的完整prompt

---

## 2. SQL生成与执行问题

### 问题2.1：LLM生成的SQL带"sql "前缀
**时间**：2026-04-10  
**现象**：LLM返回的SQL格式为 `sql SELECT * FROM orders`  

**影响**：
- 首次执行失败（语法错误）：`安全拦截：仅允许执行SELECT/SHOW/DESC/EXPLAIN查询语句`
- 触发重试机制，LLM修正后去掉前缀
- 但最终返回给前端的SQL仍带前缀（因为用的是result.getSql()）

**根本原因**：
- qwen2.5-coder模型在Tool Calling时，有时会添加语言标记
- **StandardQuerySkill执行SQL前没有清理前缀**
- StandardQuerySkillTool返回时也没有清理

**解决方案**：

**位置1：StandardQuerySkill执行前清理**
```java
// StandardQuerySkill.java - execute()方法
// Step 3: 执行SQL
String cleanSql = sql;
if (cleanSql != null && cleanSql.toLowerCase().startsWith("sql ")) {
    cleanSql = cleanSql.substring(4).trim();
    log.info("[StandardQuerySkill] 清理 SQL 前缀: {}", cleanSql);
}

SQLExecutionTool.ExecutionResult execResult = executeWithAutoFix(
    cleanSql, datasourceId, userId, username, 2
);

// 返回清理后的 SQL
return QueryResult.success(..., cleanSql);
```

**位置2：StandardQuerySkillTool返回前清理**
```java
// StandardQuerySkillTool.java
String cleanSql = result.getSql();
if (cleanSql != null && cleanSql.toLowerCase().startsWith("sql ")) {
    cleanSql = cleanSql.substring(4).trim();
    log.info("[StandardQuerySkillTool] 清理 SQL 前缀: {}", cleanSql);
}
response.put("sql", cleanSql);
```

**位置3：summarize_result和generate_chart工具执行前清理**
```java
// AgentConfig.java
if (generatedSQL.toLowerCase().startsWith("sql ")) {
    generatedSQL = generatedSQL.substring(4).trim();
    log.info("[summarize_result] 清理 SQL 前缀: {}", generatedSQL);
}
```

**经验教训**：
- ✅ LLM输出需要清洗，不能直接使用
- ✅ **在数据边界处（输入/输出）做校验和清理**
- ✅ **SQL执行前必须清理，而不是只在返回时清理**
- ✅ 添加详细日志便于排查问题
- ✅ 多处使用同一数据时，每处都要清理

---

### 问题2.2：大数据传输优化
**时间**：2026-04-10  
**场景**：AI总结和图表生成需要查询数据  

**方案对比**：

| 方案 | 优点 | 缺点 |
|------|------|------|
| 前端传完整数据 | 减少后端查询 | JSON过大，网络开销大 |
| 前端压缩数据 | 减小体积 | 增加复杂度，仍需解压 |
| **后端重新查询（采用）** | **前端只传SQL，数据最新** | **多一次数据库查询** |

**选择理由**：
1. 前端传参小（SQL通常<1KB）
2. 避免大JSON序列化/反序列化
3. 数据始终最新
4. 符合Agent架构（工具自主获取数据）

**实现**：
```java
// 工具从context提取SQL，重新执行查询
String generatedSQL = (String) context.get("generatedSQL");
SQLExecutionTool.ExecutionResult execResult = sqlExecutionTool.executeSQL(
    generatedSQL, dsId, userId, username
);
```

---

## 3. 前后端交互问题

### 问题3.1：追问按钮发送文本到Agent
**时间**：2026-04-10  
**现象**：点击"需要AI分析总结吗？"按钮，会将按钮文本发送给Agent  

**影响**：
- 用户看到多余的对话气泡
- Agent可能误解用户意图

**解决方案**：
不显示追问文本，直接触发操作：
```javascript
case 'ai_summary':
    // 不显示用户消息，直接执行
    showTypingIndicator();
    generateAISummary();
    break;
```

**经验教训**：
- ✅ 系统触发的操作不应显示为用户消息
- ✅ 区分"用户主动输入"和"系统建议操作"

---

### 问题3.2：图表生成提示"没有可生成图表的数据"
**时间**：2026-04-10  
**现象**：点击图表按钮后提示无数据  

**根本原因**：
- currentContext.data可能为空（用户未执行查询就点图表）
- 或者之前的查询返回了空结果

**解决方案**：
1. 添加更友好的错误提示
2. 传递SQL让后端重新查询（而非依赖前端缓存）
3. 添加调试日志排查问题

```javascript
console.log('[生成图表] currentContext:', currentContext);
if (!currentContext || !currentContext.data || currentContext.data.length === 0) {
    addMessage('❌ 没有可生成图表的数据，请先执行查询', 'agent');
    return;
}
```

---

## 4. 依赖注入与实例化问题

### 问题4.1：Spring Bean中直接new服务类
**时间**：2026-04-10  
**现象**：编译错误，SQLExecutor构造函数需要5个参数  

**错误代码**：
```java
// ❌ 错误做法
SQLExecutor sqlExecutor = new SQLExecutor();
```

**根本原因**：
- Spring管理的Bean需要通过依赖注入获取
- 直接new无法获得JdbcTemplate等依赖

**正确方案**：
使用已注入的工具实例：
```java
// ✅ 正确做法
@Autowired
private SQLExecutionTool sqlExecutionTool;

SQLExecutionTool.ExecutionResult result = sqlExecutionTool.executeSQL(...);
```

**经验教训**：
- ✅ Spring环境中，优先使用依赖注入
- ✅ 不要直接new需要依赖的服务类
- ✅ 查看类的构造函数签名，确认是否需要特殊处理

---

## 5. 日志与调试问题

### 问题5.1：Windows下日志中文乱码
**时间**：2026-04-06  
**现象**：日志文件中的中文显示为乱码  

**解决方案**：
1. application.yml配置UTF-8编码
2. JVM启动参数添加：`-Dfile.encoding=UTF-8`
3. Logback配置charset为UTF-8

```xml
<encoder>
    <charset>UTF-8</charset>
    <pattern>...</pattern>
</encoder>
```

---

### 问题5.2：缺少详细日志导致排查困难
**时间**：2026-04-10  
**现象**：AI总结返回"好的，请提供需要分析的查询结果"，但不知道原因  

**解决方案**：
添加关键节点的详细日志：
```java
log.info("[summarize_result] datasourceId={}, userId={}, username={}", dsId, userId, username);
log.info("[summarize_result] 重新执行 SQL: {}", generatedSQL);
log.info("[summarize_result] 执行结果: success={}, rowCount={}", 
    execResult.isSuccess(), 
    execResult.getData() != null ? execResult.getData().size() : 0);
```

**经验教训**：
- ✅ 在关键业务流程添加结构化日志
- ✅ 记录输入参数、中间状态、最终结果
- ✅ 错误日志要包含具体错误信息

---

## 6. Maven构建问题

### 问题6.1：Jar文件被占用导致编译失败
**时间**：2026-04-07  
**现象**：`mvn clean package`报错，无法删除target目录  

**原因**：应用仍在运行，锁定了jar文件  

**解决方案**：
1. 先停止运行的应用
2. 或使用PowerShell强制删除：
```powershell
Stop-Process -Name "java" -Force
mvn clean package
```

---

### 问题6.2：JDK版本不一致
**时间**：2026-04-07  
**现象**：`UnsupportedClassVersionError`  

**原因**：
- 编译用JDK 21
- 运行用JDK 11

**解决方案**：
统一使用JDK 21：
```powershell
$env:JAVA_HOME="D:\Program Files\Java\jdk-21.0.6"
mvn clean package
```

---

## 7. LangChain4j与Ollama集成问题

### 问题7.1：qwen2.5-coder模型支持Tool Calling
**时间**：2026-04-08  
**发现**：qwen2.5-coder:7b-instruct-q4_0支持函数调用  

**背景**：
最初怀疑模型不支持Tool Calling，导致LLM无法正确调用工具

**验证方法**：
1. 检查Ollama模型文档
2. 测试简单的tool calling场景
3. 观察LLM输出的JSON格式

**结论**：
- ✅ qwen2.5-coder:7b-instruct-q4_0 **支持** Tool Calling
- 但需要在System Prompt中明确指导
- LLM输出可能不标准，需要清洗和解析

**注意事项**：
- 温度设置为0.0，确保输出稳定
- 不要在tools数组中传递工具定义（手动管理）
- LLM输出可能包含Markdown代码块，需要清洗
- 有时LLM会忽略意图标记，需要强化Prompt

---

### 问题7.2：LLM返回JSON的Markdown清洗
**时间**：2026-04-08  
**现象**：LLM返回 ```json {...} ``` 格式  

**解决方案**：
```java
Pattern markdownPattern = Pattern.compile("```(?:json)?\\s*([\\s\\S]*?)\\s*```");
Matcher matcher = markdownPattern.matcher(cleanedText);
if (matcher.find()) {
    cleanedText = matcher.group(1).trim();
}
```

**经验教训**：
- ✅ LLM输出需要多层清洗策略
- ✅ 先去除Markdown，再解析JSON
- ✅ 兼容多种格式（纯JSON、带代码块、带Thought标记）

---

### 问题7.3：LLM意图识别与Context传递
**时间**：2026-04-10  
**现象**：
- 前端发送`[INTENT:AI_SUMMARY]`标记和context数据
- 但LLM没有识别，返回"好的，请提供需要分析的查询结果"
- LLM提取的generatedSQL为空字符串

**根本原因**：
1. **Context数据未传递给LLM**：ReActAgent.execute()只接收userMessage字符串，ChatRequest中的context字段被忽略
2. **System Prompt不够明确**：LLM不知道如何从消息中提取context信息
3. **LLM只能处理文本**：复杂的JSON结构需要序列化后嵌入到prompt中

**排查过程**：
1. 查看日志发现LLM收到的消息只有：`User: [INTENT:AI_SUMMARY] 请对以下查询结果进行AI分析总结`
2. Context数据完全丢失，LLM无法提取SQL
3. 尝试让前端直接把SQL放在message中（可行但不优雅）
4. 最终方案：Controller将context序列化为JSON，拼接到message中

**解决方案**：

**步骤1：Controller拼接Context到Message**
```java
String fullMessage = request.getMessage();
if (request.getContext() != null && !request.getContext().isEmpty()) {
    String contextJson = mapper.writeValueAsString(request.getContext());
    fullMessage = request.getMessage() + "\n\nContext: " + contextJson;
    log.info("[Agent对话] 附加 Context: {}", contextJson);
}
reActAgent.execute(fullMessage, ...);
```

**步骤2：强化System Prompt**
```
## 工作流程
对于每个问题，你需要：
1. **检查消息是否包含 [INTENT:XXX] 标记**
   - 如果包含 [INTENT:AI_SUMMARY] → 从消息中提取用户问题和 SQL
     格式：{"name": "summarize_result", "arguments": {"context": {"lastQuery": "...", "generatedSQL": "..."}}}
   - 如果包含 [INTENT:GENERATE_CHART] → 从消息中提取图表类型和 SQL
   - 不要询问数据源，不要调用其他工具
```

**步骤3：提供清晰示例**
```
✅ 好的（总结意图）：{"name": "summarize_result", "arguments": {"context": {"lastQuery": "查询销售额", "generatedSQL": "SELECT ..."}}}
✅ 好的（普通查询）：{"name": "execute_standard_query", "arguments": {"question": "统计销售额", "datasourceId": 1}}
```

**LLM最终收到的完整消息**：
```
User: [INTENT:AI_SUMMARY] 请对以下查询结果进行AI分析总结

Context: {"lastQuery":"统计订单","generatedSQL":"SELECT DATE(created_at)..."}
```

**经验教训**：
- ✅ LLM只能处理文本，复杂结构需要序列化
- ✅ 在Prompt工程中，清晰的格式比隐藏的参数更有效
- ✅ 调试时要检查LLM实际收到的完整prompt
- ✅ 意图识别失败时，首先检查数据是否真的传递到了LLM
- ✅ 示例比描述更有效，给LLM看具体格式
- ✅ System Prompt要分步骤、有重点、有示例

---

## 8. 数据库相关问题

### 问题8.1：HikariCP连接池关闭状态检查
**时间**：2026-04-07  
**现象**：复用连接时报错  

**解决方案**：
```java
if (connection != null && !connection.isClosed()) {
    // 复用连接
} else {
    // 创建新连接
}
```

---

### 问题8.2：数据库表注释乱码
**时间**：2026-04-06  
**现象**：MySQL表注释显示为乱码  

**解决方案**：
1. 修改表字符集：
```sql
ALTER TABLE orders CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```
2. 更新注释为英文或正确的中文

---

## 9. 元数据管理问题

### 问题9.1：通过元数据推断表关联关系
**时间**：2026-04-07  
**场景**：没有外键约束，如何知道表之间的关系  

**解决方案**：
1. 分析column_metadata表
2. 查找命名相似的字段（如orders.user_id → users.id）
3. 存储到table_relationships表

**经验教训**：
- ✅ 元数据驱动的设计更灵活
- ✅ 可以动态发现和维护表关系
- ✅ 避免硬编码表结构

---

## 10. 架构演进经验

### 经验10.1：区分伪Agent与真正Agent
**伪Agent特征**：
- Controller中有大量if-else判断
- 工具调用由代码决定，而非LLM
- LLM只负责生成SQL或解释结果

**真正Agent特征**：
- Controller只做透传
- LLM根据System Prompt自主决策
- 工具注册机制，LLM可选择调用
- ReAct循环：思考→行动→观察→再思考

**迁移路径**：
1. 识别硬编码的业务逻辑
2. 将决策规则写入System Prompt
3. 注册工具供LLM调用
4. 移除Controller中的业务判断

---

### 经验10.2：代码修复需具备全局观
**问题**：修复一个问题引入新问题  

**案例**：
- 修复SQL前缀问题，但忘记在所有地方都清理
- 修改AgentConfig，但忘记添加import

**解决方案**：
1. 修改前先搜索所有相关代码
2. 使用IDE的全局搜索功能
3. 编译通过后进行全面测试
4. 记录修改点，便于回溯

---

## 14. 表关联关系管理问题

### 问题14.1：表关联描述字段乱码与标准化生成
**时间**：2026-04-15  
**现象**：
```sql
SELECT id, description FROM table_relationships;
-- 结果：库?存?通?过?product_id关?联?商?品?
```
`table_relationships`表的`description`字段出现大量问号乱码，导致LLM无法正确理解关联关系。

**根本原因**：
1. **历史数据插入时编码错误**：早期手动插入或程序插入时未使用UTF8MB4编码
2. **用户自由输入导致格式不统一**：前端允许用户手动输入任意格式的description
3. **LLM依赖description但质量不可控**：虽然核心是`source_table.source_column -> target_table.target_column`，但description提供业务语义辅助

**影响范围**：
- LLM生成的SQL可能因描述模糊而选择错误的JOIN方式
- 前端展示给用户的信息混乱
- 数据质量无法保证

**解决方案**：

**步骤1：修复现有乱码数据（本地库端口3306）**
```sql
UPDATE table_relationships SET description = '订单明细通过product_id关联商品' WHERE id = 2;
UPDATE table_relationships SET description = '商品通过category_id关联分类' WHERE id = 3;
UPDATE table_relationships SET description = '库存通过product_id关联商品' WHERE id = 4;
UPDATE table_relationships SET description = '退货通过order_id关联订单' WHERE id = 5;
UPDATE table_relationships SET description = '退货通过user_id关联用户' WHERE id = 6;
UPDATE table_relationships SET description = '用户地址通过user_id关联用户' WHERE id = 7;
INSERT INTO table_relationships (datasource_id, source_table, source_column, target_table, target_column, relationship_type, confidence, description, is_active) 
VALUES (1, 'order_items', 'order_id', 'orders', 'id', 'MANY_TO_ONE', 1.0, '订单明细通过order_id关联订单', 1);
```

**步骤2：前端禁止手动输入，改为自动生成（relationship-management.html）**
```html
<!-- 修改前 -->
<textarea id="description" rows="3" placeholder="例如：订单通过user_id关联用户"></textarea>

<!-- 修改后 -->
<textarea id="description" rows="3" readonly style="background: #f5f7fa; cursor: not-allowed;"></textarea>
<small style="color: #888;">💡 描述会根据选择的表和字段自动生成，无需手动输入</small>
```

**触发机制**：在所有选择框上添加`onchange="autoGenerateDescription()"`
```javascript
// 自动生成标准格式描述
function autoGenerateDescription() {
    const sourceTable = document.getElementById('sourceTable').value;
    const sourceColumn = document.getElementById('sourceColumn').value;
    const targetTable = document.getElementById('targetTable').value;
    const targetColumn = document.getElementById('targetColumn').value;
    
    if (sourceTable && sourceColumn && targetTable && targetColumn) {
        // 获取表注释（优先使用中文注释）
        const sourceTableObj = allTables.find(t => t.table_name === sourceTable);
        const targetTableObj = allTables.find(t => t.table_name === targetTable);
        
        const sourceName = sourceTableObj?.table_comment || sourceTable;
        const targetName = targetTableObj?.table_comment || targetTable;
        
        // 标准格式：{源表名}通过{源字段}关联{目标表名}
        const description = `${sourceName}通过${sourceColumn}关联${targetName}`;
        document.getElementById('description').value = description;
    }
}
```

**步骤3：后端二次保障，强制生成标准格式（TableRelationshipController.java）**
```java
@PostMapping
public Result<Void> createRelationship(@RequestBody RelationshipRequest request) {
    // ✅ 忽略前端传来的description，后端自动生成
    String autoDescription = generateStandardDescription(
        request.getSourceTable(),
        request.getSourceColumn(),
        request.getTargetTable(),
        request.getTargetColumn(),
        request.getDatasourceId()
    );
    
    jdbcTemplate.update(
        "INSERT INTO table_relationships (...) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1)",
        ...,
        autoDescription  // 使用自动生成的描述
    );
}

/**
 * 自动生成标准格式的 description
 * 格式：{源表注释}通过{源字段}关联{目标表注释}
 */
private String generateStandardDescription(String sourceTable, String sourceColumn, 
                                           String targetTable, String targetColumn, 
                                           Long datasourceId) {
    try {
        // 查询表注释
        String sourceComment = jdbcTemplate.queryForObject(
            "SELECT table_comment FROM table_metadata WHERE datasource_id = ? AND table_name = ?",
            String.class, datasourceId, sourceTable
        );
        String targetComment = jdbcTemplate.queryForObject(
            "SELECT table_comment FROM table_metadata WHERE datasource_id = ? AND table_name = ?",
            String.class, datasourceId, targetTable
        );
        
        // 使用表注释，如果没有则使用表名
        String sourceName = (sourceComment != null && !sourceComment.isEmpty()) ? sourceComment : sourceTable;
        String targetName = (targetComment != null && !targetComment.isEmpty()) ? targetComment : targetTable;
        
        return String.format("%s通过%s关联%s", sourceName, sourceColumn, targetName);
    } catch (Exception e) {
        log.warn("生成 description 失败，使用默认格式: {}", e.getMessage());
        return String.format("%s通过%s关联%s", sourceTable, sourceColumn, targetTable);
    }
}
```

**经验教训**：
- ✅ **LLM提示词中的辅助信息必须标准化**：description只是辅助，核心是表名和字段名
- ✅ **前后端双重保障**：前端自动生成提升用户体验，后端强制生成确保数据质量
- ✅ **优先使用元数据中的中文注释**：让LLM和业务人员更容易理解
- ✅ **降级策略必不可少**：如果查询表注释失败，直接使用表名
- ✅ **数据库字符集必须统一为UTF8MB4**：避免历史乱码问题
- ✅ **不要让用户自由输入结构化数据**：容易出错且难以维护

---

## 15. SQL关联关系智能提取问题

### 问题15.1：复杂SQL关联关系提取与渐进式引导策略
**时间**：2026-04-16  
**场景**：用户希望通过输入SQL自动提取表关联关系，但SQL写法千差万别，程序难以完美解析所有情况。

**支持的SQL关联方式**：

| 关联类型 | 示例 | 支持状态 |
|---------|------|----------|
| 显式JOIN | `FROM orders o JOIN users u ON o.user_id = u.id` | ✅ 完全支持 |
| 隐式JOIN | `FROM orders o, users u WHERE o.user_id = u.id` | ✅ 完全支持 |
| IN子查询 | `WHERE user_id IN (SELECT id FROM users)` | ✅ 完全支持 |
| EXISTS子查询 | `WHERE EXISTS (SELECT 1 FROM users u WHERE u.id = o.user_id)` | ✅ 完全支持 |
| NOT EXISTS | `WHERE NOT EXISTS (...)` | ✅ 完全支持 |
| 比较运算符子查询 | `WHERE id = ANY (SELECT ...)` | ✅ 完全支持 |
| 函数转换关联 | `WHERE DATE(a.time) = b.date` | ⚠️ 可提取但提示风险 |
| 多层嵌套(>3层) | 4层以上SELECT嵌套 | ⚠️ 高复杂度警告 |
| 多表JOIN(>5个) | 6个表以上的关联 | 💡 中等复杂度提示 |

**根本挑战**：
1. **SQL写法多样性**：同样的关联关系可以有多种SQL表达方式
2. **复杂度差异大**：简单JOIN vs 多层嵌套EXISTS，解析难度天壤之别
3. **用户体验平衡**：频繁提示会打扰用户，不提示又可能提取失败

**解决方案：渐进式引导策略**

#### 步骤1：引入复杂度评分系统（0-10分）

```java
// TableRelationshipService.java - analyzeSQLComplexity()
private void analyzeSQLComplexity(String sql, List<String> suggestions) {
    int complexityScore = 0;
    
    // 检测多层嵌套（SELECT数量）
    int selectCount = upperSQL.split("SELECT").length - 1;
    if (selectCount > 3) {
        complexityScore += 3;  // 严重复杂
        suggestions.add("⚠️ 检测到多层嵌套子查询...建议拆分");
    } else if (selectCount > 2) {
        complexityScore += 2;  // 较复杂
        suggestions.add("💡 SQL包含" + selectCount + "层嵌套...");
    }
    
    // 检测EXISTS子查询
    if (upperSQL.contains("EXISTS")) {
        complexityScore += 2;
        suggestions.add("💡 检测到EXISTS子查询...");
    }
    
    // 检测非等值关联（函数转换）
    if (upperSQL.matches(".*LIKE.*CONCAT.*") || 
        upperSQL.matches(".*DATE\\(.*\\).*=") || 
        upperSQL.matches(".*UPPER\\(.*\\).*=")) {
        complexityScore += 3;  // 难以准确提取
        suggestions.add("⚠️ 检测到函数转换或模糊匹配关联...");
    }
    
    // 检测多个JOIN（超过5个表）
    int joinCount = upperSQL.split("JOIN").length - 1;
    if (joinCount > 5) {
        complexityScore += 2;
        suggestions.add("💡 SQL涉及" + (joinCount + 1) + "个表的关联...");
    }
    
    // 分级显示策略
    if (complexityScore >= 3) {
        suggestions.add(0, "📊 SQL复杂度评估：较高（" + complexityScore + "分）");
    } else if (complexityScore >= 2) {
        suggestions.add(0, "📊 SQL复杂度评估：中等（" + complexityScore + "分）");
    } else {
        suggestions.clear();
        suggestions.add("✅ SQL格式规范，关联关系清晰");
    }
}
```

#### 步骤2：前端友好展示优化建议

```javascript
// relationship-management.html - showSuggestions()
function showSuggestions(suggestions) {
    const modal = document.createElement('div');
    modal.innerHTML = `
        <h3>💡 SQL优化建议</h3>
        <div>${suggestionHtml}</div>
        <button onclick="closeModal()">我了解了，继续提取</button>
    `;
    document.body.appendChild(modal);
}
```

**用户体验流程**：
```
用户输入SQL
    ↓
后端解析 + 复杂度评分
    ↓
┌─────────────────────────────┐
│ 评分 < 2: 不弹窗，直接提取   │ → 简单SQL，不打扰用户
│ 评分 2-3: 弹出友好提示       │ → 中等复杂，可选优化
│ 评分 ≥ 3: 弹出警告+建议      │ → 高复杂，强烈建议简化
└─────────────────────────────┘
    ↓
用户点击“我了解了，继续提取”
    ↓
返回提取结果（即使有警告也继续）
```

#### 步骤3：TODO - LLM Fallback机制（待实现）

**触发条件**：当程序化解析失败或提取结果为空时

```java
public Map<String, Object> extractRelationshipsWithSuggestions(String sql, Long datasourceId) {
    // 1. 先尝试程序化解析
    try {
        relationships = extractRelationshipsFromSQL(sql, datasourceId);
        if (!relationships.isEmpty()) {
            return buildSuccessResult(relationships, analyzeSQLComplexity(sql));
        }
    } catch (Exception e) {
        log.warn("程序化解析失败: {}", e.getMessage());
    }
    
    // 2. Fallback到LLM
    log.info("程序化解析未成功，尝试使用LLM分析");
    return extractWithLLM(sql, datasourceId);
}
```

**LLM提示词设计**：
```
你是一个SQL专家，请从以下SQL中提取表之间的关联关系。

SQL语句：{sql}
数据源ID：{datasourceId}

要求：
1. 识别所有表之间的关联字段（如：orders.user_id -> users.id）
2. 判断关联类型（ONE_TO_ONE / ONE_TO_MANY / MANY_TO_ONE / MANY_TO_MANY）
3. 只返回JSON格式，不要其他内容

返回格式：
{
  "relationships": [
    {
      "sourceTable": "表名",
      "sourceColumn": "字段名",
      "targetTable": "表名",
      "targetColumn": "字段名",
      "relationshipType": "关联类型",
      "confidence": 0.95
    }
  ]
}
```

**实施优先级**：
- 🟡 中优先级 - 当前程序化解析已覆盖80%常见场景
- 📊 建议先收集用户反馈，统计解析失败率
- 💰 如果失败率<5%，可以暂缓；如果>10%，应尽快实现

**经验教训**：
- ✅ **不要对所有不规范SQL都提示**：隐式JOIN虽不规范但很常见，频繁提示会影响体验
- ✅ **量化复杂度**：用数字让用户直观了解SQL复杂度，而非主观判断
- ✅ **分级提示策略**：不是所有问题都同等重要，聚焦真正影响提取准确性的问题
- ✅ **尊重用户选择**：即使用户坚持使用复杂SQL，也要允许继续操作
- ✅ **程序优先，LLM兜底**：80%场景用程序解决（零成本），20%复杂场景用LLM
- ✅ **前后端协同**：后端评分，前端友好展示，给用户选择权

---

## 总结

### 核心原则
1. **纯Agent架构**：LLM自主决策，Controller不干预
2. **数据最小化传输**：传SQL而非完整数据
3. **防御性编程**：输入校验、输出清洗、异常处理
4. **详细日志**：关键节点记录，便于排查
5. **依赖注入**：Spring环境中使用@Autowired
6. **元数据驱动**：表关联、字段注释等必须准确且标准化

### 技术栈要点
- LangChain4j：Java版的LangChain
- Ollama：本地大模型运行时
- qwen2.5-coder：支持Tool Calling的开源模型
- ReAct模式：Reasoning-Action循环
- HikariCP：高性能连接池
- MySQL UTF8MB4：统一字符集避免乱码

### 最佳实践
1. System Prompt要清晰、具体、有示例
2. LLM输出必须清洗和验证
3. 工具要有详细的描述，帮助LLM理解
4. 错误信息要友好且具体
5. 前后端职责明确，不越界
6. **结构化数据（如表关联描述）必须自动生成，禁止用户自由输入**
7. **元数据是LLM理解业务的唯一依据，必须保持准确和清晰**

---

**文档更新时间**：2026-04-16  
**项目阶段**：开发中  
**待补充**：性能优化、安全加固、生产部署经验

---

## 12. SQL风险评估问题

### 问题12.1：LLM自主评估SQL风险的设计原则
**时间**：2026-04-10  
**最后更新**：2026-04-18  
**现象**：最初设计将`analyze_sql_risk`作为独立Tool供LLM调用，但违背了Agent架构原则。

**错误设计**：
```java
// ❌ 错误做法：暴露底层工具
agent.registerTool("analyze_sql_risk", ...);
agent.registerTool("execute_direct_sql", ...); // 绕过StandardQuerySkill
```

**根本原因**：
- 开发者误以为“让LLM自主决定”=“提供所有底层工具”
- 忽略了Skills的封装价值
- `execute_direct_sql`破坏了NL2SQL生成流程

**正确方案**：
在StandardQuerySkill内部集成 **EXPLAIN优先 + LLM辅助优化** 机制：
```groovy
// ✅ 正确做法：Skill内部评估（Groovy脚本）
String sql = nl2sqlTool.generateSQL(question, datasourceId)
RiskAssessmentResult riskResult = assessSQLRisk(sql, question, datasourceId, llmService, riskAnalyzer, nl2sqlTool)

if ("HIGH".equals(riskResult.getRiskLevel())) {
    // 构建优化建议
    String optimizationSuggestion = buildOptimizationSuggestionForFrontend(riskResult)
    return createRiskBlockedResult(riskResult.getReason(), sqlToReturn, optimizationSuggestion)
}
// 继续执行...
```

**完整评估流程**（EXPLAIN优先 + LLM辅助优化）：
```
Step 0: 快速判断 → 简单查询直接放行（不调用 EXPLAIN）
   - 单表查询、主键等值查询、无 JOIN/子查询/聚合函数
   - 直接判定为 LOW 风险

Step 1: EXPLAIN 分析 → 获取客观风险等级
   - 执行 EXPLAIN SQL 获取执行计划
   - 分析表扫描类型、索引使用情况、行数估算等
   - 风险等级：LOW / MEDIUM / HIGH

Step 2: 根据风险等级处理

1. LOW 风险 → 直接执行
   - EXPLAIN 显示使用索引、扫描行数少
   - 无需额外处理

2. MEDIUM 风险 → 调用 LLM 获取优化建议（仅供参考，仍执行原 SQL）
   - 将 EXPLAIN 结果传给 LLM
   - LLM 分析性能瓶颈，给出优化建议
   - 前端展示：橙色渐变背景显示优化建议
   - SQL 继续执行

3. HIGH 风险 → 调用 LLM 重新生成 SQL → 重新 EXPLAIN
   - 告知 LLM 原始 SQL 和风险原因
   - LLM 生成优化后的新 SQL
   - 对新 SQL 再次执行 EXPLAIN
   - 如果优化后仍是 HIGH：
     ✅ 只返回优化后的 SQL 给前端
     ✅ 标注高风险，需要人工介入
     ✅ 绝对不执行 SQL
     前端展示：错误信息 + 优化建议 + 优化后的 SQL
   - 如果优化后风险降低：
     使用优化后的 SQL 继续执行
     前端展示：优化建议（如果有）
```

**新增字段：optimizationSuggestion**
- **中风险**：存储 LLM 给出的优化建议（性能瓶颈 + 优化建议 + 预期效果）
- **高风险**：存储风险原因 + LLM 优化后的 SQL（如果有）+ 人工审核提示
- **前端展示**：橙色渐变背景，清晰显示建议内容

**经验教训**：
- ✅ Agent架构中，辅助决策应在Skill内部完成
- ✅ 不要暴露底层工具给LLM，保持分层清晰
- ✅ **EXPLAIN 客观分析 + LLM 智能优化**是最佳组合
- ✅ **高风险必须阻断**，绝对不能执行，即使 LLM 优化后仍是 HIGH
- ✅ **前端友好展示**：清晰的优化建议和风险原因，帮助用户理解
- ✅ 必须控制循环次数（最多 2 次 EXPLAIN + 2 次 LLM 调用）
- ✅ 高风险阻断应返回结构化错误，并包含优化后的 SQL 供人工审核

---

## 11. 数据源传递问题

### 问题11.1：SQL执行时使用了错误的数据库
**时间**：2026-04-10  
**现象**：
```
Table 'nl2sql_meta_db.orders' doesn't exist
```

**根本原因**：
- SQLExecutor连接到了元数据数据库（nl2sql_meta_db）
- 而不是用户选择的业务数据库
- 因为AgentConfig中调用工具时，datasourceId为null

**排查过程**：
1. 检查日志发现：`DatasourceId: null`
2. 前端发送了datasourceId，但后端返回响应时没有包含它
3. 前端`currentContext.datasourceId`为空

**解决方案**：

**方案1：后端返回时携带datasourceId**
```java
// AgentController.processChat()
if (request.getDatasourceId() != null) {
    response.put("datasourceId", request.getDatasourceId());
}
```

**方案2：前端添加校验和调试日志**
```javascript
console.log('[AI总结] datasourceId:', currentContext.datasourceId);
if (!currentContext || !currentContext.datasourceId) {
    addMessage('❌ 缺少数据源信息，请重新执行查询', 'agent');
    return;
}
```

**经验教训**：
- ✅ 多轮对话中，关键上下文（如datasourceId）必须在响应中回传
- ✅ 前端要保存关键状态，并在后续请求中使用
- ✅ 添加详细日志便于排查上下文丢失问题
- ✅ 在关键操作前校验必要参数

---

### 问题11.2：结构化数据返回时datasourceId丢失
**时间**：2026-04-10  
**现象**：
- 点击“AI分析”按钮，前端报错：“缺少数据源信息，请重新执行查询”
- `currentContext.datasourceId`为undefined
- **第一次查询返回的结果中就没有datasourceId**

**根本原因**：
1. **StandardQuerySkillTool返回的JSON中没有包含datasourceId字段**
2. Controller检测到结构化数据后，直接使用它作为response
3. 即使Controller后面添加了datasourceId，但StandardQuerySkillTool返回的数据本身就没有

**代码流程**：
```java
// StandardQuerySkillTool.execute()
Map<String, Object> response = new HashMap<>();
response.put("status", "success");
response.put("sql", cleanSql);
response.put("data", result.getData());
// ❌ 没有添加 datasourceId！
return objectMapper.writeValueAsString(response);
```

**解决方案**：
在StandardQuerySkillTool返回时添加datasourceId：
```java
// StandardQuerySkillTool.java
Map<String, Object> response = new HashMap<>();
response.put("status", "success");
response.put("rowCount", result.getRowCount());
response.put("executionTime", String.format("%.2f", result.getExecutionTime()));
response.put("sql", cleanSql);
response.put("data", result.getData());
response.put("datasourceId", datasourceId);  // ⚠️ 重要：返回 datasourceId 供前端后续使用
```

**经验教训**：
- ✅ 工具返回的结构化数据要包含所有必要字段
- ✅ datasourceId是关键上下文，必须在第一次查询时就返回
- ✅ 前后端约定好响应格式，确保关键字段不缺失
- ✅ 多轮对话中，上下文数据的完整性从第一次交互开始就要保证

---

## 13. 元数据同步与语义歧义问题

### 问题13.1：源数据库注释更新后未同步到元数据库
**时间**：2026-04-15  
**现象**：
```sql
Unknown column 'u.province' in 'field list'
```
查询"统计每个地区的销售额"时，LLM选择了`users`表但SQL执行失败。

**根本原因**：
1. **源数据库（trade）注释已更新**：`users.province` → "用户注册省份"
2. **元数据库（nl2sql_meta_db）仍是旧注释**：`users.province` → "省份"
3. LLM看到模糊的"省份"注释，无法区分`users.province`（用户注册地）和`user_addresses.province`（收货地址）
4. 选择错误的表组合导致SQL生成错误

**解决方案**：

**步骤1：更新源数据库注释（明确语义）**
```sql
-- users表：用户注册地
ALTER TABLE users MODIFY COLUMN province VARCHAR(50) COMMENT '用户注册省份';
ALTER TABLE users MODIFY COLUMN city VARCHAR(50) COMMENT '用户注册城市';
ALTER TABLE users MODIFY COLUMN district VARCHAR(50) COMMENT '用户注册区县';

-- user_addresses表：收货地址
ALTER TABLE user_addresses MODIFY COLUMN province VARCHAR(50) COMMENT '收货地址省份';
ALTER TABLE user_addresses MODIFY COLUMN city VARCHAR(50) COMMENT '收货地址城市';
ALTER TABLE user_addresses MODIFY COLUMN district VARCHAR(50) COMMENT '收货地址区县';

-- orders表：收货地址文本
ALTER TABLE orders MODIFY COLUMN shipping_address VARCHAR(500) COMMENT '收货地址文本（非结构化，不可用于JOIN）';
```

**步骤2：手动更新元数据库（因为JDBC采集可能缓存旧注释）**
```sql
UPDATE column_metadata SET column_comment = '用户注册省份' 
WHERE datasource_id=1 AND table_name='users' AND column_name='province';
-- 同理更新 city, district, user_addresses.*, orders.shipping_address
```

**步骤3：添加一对多JOIN防笛卡尔积规则（NL2SQLService.java）**
```java
"   - **一对多关联时必须添加过滤条件避免笛卡尔积**\n" +
"   - 错误示例：JOIN user_addresses ua ON orders.user_id = ua.user_id （一个用户可能有多个地址，导致订单金额重复计算）\n" +
"   - 正确示例：JOIN user_addresses ua ON orders.user_id = ua.user_id AND ua.is_default = 1 （只取默认地址）\n" +
"   - 或者优先使用主表的字段：直接使用users表的地区字段，而非user_addresses\n" +
```

**步骤4：提高向量检索召回率（VectorRetriever.java）**
```java
// Top-5 → Top-10，避免关键表遗漏
int maxTables = Math.min(similarities.size(), 10);
```

**经验教训**：
- ✅ 元数据是LLM理解字段的唯一依据，必须保持准确和清晰
- ✅ 相似字段（如"用户注册地" vs "收货地址"）必须在注释中明确区分
- ✅ 源数据库更新后，必须同步到元数据库（重新采集或手动更新）
- ✅ JDBC的`DatabaseMetaData.getColumns()`可能缓存旧注释，需验证
- ✅ Prompt中添加通用规则（如一对多JOIN防笛卡尔积）比硬编码表名更灵活
- ✅ 向量检索Top-K值要根据实际场景调整，避免关键表被截断

---

### 问题13.2：前端选择数据源后原始问题丢失
**时间**：2026-04-15  
**现象**：
1. 用户输入："查询每个地区销售额"
2. 后端返回数据源选项
3. 用户点击选择"Docker Trade DB"
4. 前端发送消息：`{"name": "clarify_datasource", "arguments": {"userQuery": "我选择了: Docker Trade DB"}}`
5. LLM收到"我选择了: XXX"，无法理解意图，再次调用`clarify_datasource`

**根本原因**：
1. **前端逻辑错误**：选择数据源后，获取DOM中最后一条用户消息（即"我选择了: XXX"）
2. **原始问题丢失**："查询每个地区销售额"没有被保存和传递
3. LLM收到的消息不含原始查询意图，只能继续澄清

**解决方案**：

**步骤1：添加pendingQuery变量保存原始问题（agent-chat.html）**
```javascript
let pendingQuery = null;  // ✅ 待执行的原始查询（数据源澄清时保存）
```

**步骤2：在handleClarification中保存原始问题**
```javascript
function handleClarification(data) {
    // ✅ 关键修复：保存原始查询问题（用于数据源澄清后重新发送）
    const lastUserMsg = document.querySelector('.message.user:last-child .message-bubble');
    if (lastUserMsg && data.clarificationType && data.clarificationType.startsWith('datasource_')) {
        pendingQuery = lastUserMsg.textContent.trim();
        console.log('[handleClarification] 保存原始问题:', pendingQuery);
    }
    // ...
}
```

**步骤3：修改selectDatasource和confirmDatasource使用pendingQuery**
```javascript
function selectDatasource(datasourceId, datasourceName) {
    // ...
    setTimeout(() => {
        // ✅ 使用保存的原始问题，而非"我选择了: XXX"
        const queryToSend = pendingQuery || (currentContext.lastQuery);
        if (queryToSend) {
            sendMessagewithDatasource(queryToSend, datasourceId);
            pendingQuery = null;  // 清空待发送的问题
        }
    }, 500);
}
```

**经验教训**：
- ✅ 多轮对话中，原始用户意图必须在整个流程中保持完整
- ✅ 不要依赖DOM中的消息历史来获取关键上下文
- ✅ 使用专用变量（如pendingQuery）保存临时状态
- ✅ 澄清类交互（数据源、表关系等）完成后，应重新发送原始问题+澄清结果
- ✅ 前端状态管理要清晰，避免从UI元素反推业务数据

