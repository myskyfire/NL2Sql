---
name: execute_simple_query
displayName: 简单查询技能
description: 执行简单的单表查询，适用于快速数据检索场景。使用声明式Workflow编排。
category: query
priority: 2
version: "1.0"
workflow:
  steps:
    - id: retrieve_schema
      action: call_tool
      tool: retrieve_table_schema
      input:
        question: "{{question}}"
        datasourceId: "{{datasourceId}}"
      output_var: schema
    
    - id: generate_sql
      action: call_tool
      tool: generate_sql_from_schema
      input:
        question: "{{question}}"
        schema: "{{schema}}"
        datasourceId: "{{datasourceId}}"
      output_var: sql
    
    - id: validate_sql
      action: call_tool
      tool: validate_sql_syntax
      input:
        sql: "{{sql}}"
        datasourceId: "{{datasourceId}}"
      output_var: validation
    
    - id: check_validation
      condition: "{{validation.valid}} == false"
      action: respond
      output:
        status: "error"
        message: "SQL验证失败: {{validation.error}}"
    
    - id: execute_query
      action: call_tool
      tool: execute_safe_sql
      input:
        sql: "{{sql}}"
        datasourceId: "{{datasourceId}}"
        userId: "{{userId}}"
        username: "{{username}}"
      output_var: result
    
    - id: return_result
      action: respond
      output:
        status: "success"
        data: "{{result.data}}"
        rowCount: "{{result.rowCount}}"
        executionTime: "{{result.executionTime}}"
        sql: "{{sql}}"
        datasourceId: "{{datasourceId}}"
---

# 简单查询技能（Simple Query Skill）

## 功能说明

使用**声明式Workflow**执行的简单查询技能，适用于：
- 单表查询
- 无复杂JOIN
- 快速数据检索

## 与 StandardQuerySkill 的区别

| 特性 | SimpleQuerySkill | StandardQuerySkill |
|------|------------------|-------------------|
| 实现方式 | 声明式Workflow | Groovy脚本 |
| 风险评估 | 基础语法验证 | EXPLAIN + LLM深度评估 |
| 自动修正 | ❌ 不支持 | ✅ 支持（最多2次） |
| 适用场景 | 简单查询 | 复杂分析查询 |

## 参数说明

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| question | string | ✅ | 用户的自然语言查询问题 |
| datasourceId | long | ✅ | 数据源ID |
| userId | long | ✅ | 用户ID |
| username | string | ✅ | 用户名 |

## Workflow流程

```
用户问题
  ↓
Step 1: retrieve_table_schema (检索表结构)
  ↓
Step 2: generate_sql_from_schema (生成SQL)
  ↓
Step 3: validate_sql_syntax (语法验证)
  ↓
  ├─ 验证失败 → 返回错误
  └─ 验证通过 ↓
Step 4: execute_safe_sql (执行SQL)
  ↓
返回查询结果
```

## 示例

### 示例1：简单查询
**用户**：查询最近10条订单  
**调用**：`execute_simple_query("查询最近10条订单", 1, 123, "user")`

### 示例2：条件筛选
**用户**：查找北京地区的用户  
**调用**：`execute_simple_query("查找北京地区的用户", 1, 123, "user")`

## 注意事项

⚠️ **重要提示**：
- 这是**Workflow引擎试点项目**，用于验证声明式工作流的可行性
- 仅适用于简单查询，复杂场景请使用 `execute_standard_query`
- 不支持自动SQL修正，语法错误会直接返回

---

## 📋 Workflow迁移标准

### **何时使用YAML Workflow** ✅
1. **线性流程**: A → B → C 顺序执行
2. **条件分支**: if/else 简单判断 (≤3层嵌套)
3. **并行执行**: 同时调用多个Tool
4. **重试机制**: 失败自动重试N次
5. **数据转换**: 简单的字段映射/过滤

### **何时保留Groovy Script** ⚠️
1. **动态流程分支**: 根据运行时数据决定下一步 (>3层嵌套)
2. **复杂数据转换**: 需要循环/递归处理
3. **行业特定逻辑**: 金融/医疗等领域的合规校验
4. **异常处理**: 需要自定义降级策略
5. **性能敏感**: 高频调用的核心路径

### **迁移检查清单**
```yaml
# Step 1: 识别原子能力
- [ ] 是否可拆分为独立Tool?
- [ ] Tool是否有明确输入输出?
- [ ] Tool是否可单元测试?

# Step 2: 设计Workflow
- [ ] 流程是否为线性或简单分支?
- [ ] 变量传递是否清晰?
- [ ] 错误处理是否完备?

# Step 3: 验证
- [ ] 单元测试覆盖率 ≥80%?
- [ ] 端到端测试通过?
- [ ] 性能无明显下降?
```

### **示例对比**

#### ❌ Groovy实现 (不推荐)
```groovy
def schema = nl2sqlTool.retrieveSchema(question, datasourceId)
def sql = nl2sqlTool.generateSQL(question, datasourceId)
def result = sqlExecutionTool.executeSQL(sql, datasourceId, userId, username)
return result
```

#### ✅ YAML实现 (推荐)
```yaml
workflow:
  steps:
    - id: retrieve_schema
      action: call_tool
      tool: retrieve_table_schema
      input:
        question: "{{question}}"
        datasourceId: "{{datasourceId}}"
    
    - id: generate_sql
      action: call_tool
      tool: generate_sql_from_schema
      input:
        question: "{{question}}"
        schema: "{{retrieve_schema.schema}}"
    
    - id: execute_query
      action: call_tool
      tool: execute_safe_sql
      input:
        sql: "{{generate_sql.sql}}"
        datasourceId: "{{datasourceId}}"
```

**优势**:
- ✅ 声明式: 一眼看懂流程
- ✅ 可配置: 无需修改代码
- ✅ 可测试: 每个Tool独立单元测试
- ✅ 可复用: Tool可在多个Workflow中共享
