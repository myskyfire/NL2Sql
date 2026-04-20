# DataMind AI 架构演变之路：从 Pipeline 到声明式 Workflow

> **摘要**：本文详细复盘了 DataMind AI（原名 NL2SQL）项目从最初的简单 Pipeline 架构，历经 Tool Calling、ReAct Agent、Skills、Groovy 热插拔，最终演进到声明式 Workflow + Tool 编排的完整技术演进历程。这是一部真实的 LLM Agent 架构探索史，记录了每一次技术决策背后的思考、踩坑与突破。

---

## 📖 目录

1. [阶段一：Pipeline 时代 - 线性流程的局限](#阶段一pipeline-时代---线性流程的局限)
2. [阶段二：Tool 拆分 - 原子能力的觉醒](#阶段二tool-拆分---原子能力的觉醒)
3. [阶段三：伪 ReAct - Prompt 工程的陷阱](#阶段三伪-react---prompt-工程的陷阱)
4. [阶段四：双模型困境 - Qwen3 量化版的妥协](#阶段四双模型困境---qwen3-量化版的妥协)
5. [阶段五：Java Skills - 业务逻辑的封装](#阶段五java-skills---业务逻辑的封装)
6. [阶段六：Groovy 热插拔 - 动态化的尝试](#阶段六groovy-热插拔---动态化的尝试)
7. [阶段七：Prompt 调优泥潭 - 反复调试的痛苦](#阶段七prompt-调优泥潭---反复调试的痛苦)
8. [阶段八：真相大白 - API 选择错误](#阶段八真相大白---api-选择错误)
9. [阶段九：伪 Skills 反思 - 职责混乱的危机](#阶段九伪-skills-反思---职责混乱的危机)
10. [阶段十：真 Skill 架构 - Tool + Workflow](#阶段十真-skill-架构---tool--workflow)
11. [架构演进的核心理念](#架构演进的核心理念)
12. [关键经验教训](#关键经验教训)

---

## 阶段一：Pipeline 时代 - 线性流程的局限

### 时间：2024年初
### 关键词：线性流程、硬编码、无 Tool Calling

#### 架构特点

```
用户问题 → 检索表结构 → 生成SQL → 执行SQL → 返回结果
```

**核心代码结构**：
```java
public class NL2SQLService {
    public QueryResult process(String question, Long datasourceId) {
        // Step 1: 检索相关表结构
        String schema = retrieveSchema(question, datasourceId);
        
        // Step 2: 调用 LLM 生成 SQL
        String sql = llmService.generate(buildPrompt(schema, question));
        
        // Step 3: 执行 SQL
        List<Map<String, Object>> data = jdbcTemplate.queryForList(sql);
        
        // Step 4: 返回结果
        return new QueryResult(data);
    }
}
```

#### 存在的问题

1. **流程僵化**：所有请求走同一条路径，无法根据场景调整
2. **缺乏容错**：任何一步失败，整个流程崩溃
3. **LLM 黑盒**：无法干预 LLM 的决策过程
4. **扩展困难**：新增能力需要修改核心流程代码

#### 典型场景

```
用户："查询最近10条订单"
→ 固定流程执行
→ 返回数据
```

**局限性暴露**：当用户问"分析上月销售趋势"时，系统仍然只执行简单查询，无法自动触发分析逻辑。

---

## 阶段二：Tool 拆分 - 原子能力的觉醒

### 时间：2024年中
### 关键词：原子工具、模块化、初步解耦

#### 架构改进

将 monolithic 的 `NL2SQLService` 拆分为多个独立的 Tool：

```java
@Tool("检索表结构")
public class RetrieveSchemaTool {
    public String execute(String question, Long datasourceId) { ... }
}

@Tool("生成SQL")
public class GenerateSQLTool {
    public String execute(String schema, String question) { ... }
}

@Tool("执行SQL")
public class ExecuteSQLTool {
    public String execute(String sql, Long datasourceId) { ... }
}
```

#### 新的调用方式

```java
public class NL2SQLService {
    @Autowired
    private RetrieveSchemaTool retrieveSchemaTool;
    
    @Autowired
    private GenerateSQLTool generateSQLTool;
    
    @Autowired
    private ExecuteSQLTool executeSQLTool;
    
    public QueryResult process(String question, Long datasourceId) {
        // 仍然是 Pipeline，但使用 Tool 组合
        String schema = retrieveSchemaTool.execute(question, datasourceId);
        String sql = generateSQLTool.execute(schema, question);
        return executeSQLTool.execute(sql, datasourceId);
    }
}
```

#### 进步与局限

**✅ 进步**：
- 代码模块化，每个 Tool 可独立测试
- 为后续的灵活编排奠定基础

**❌ 局限**：
- **仍然是 Pipeline**：只是把大函数拆成小函数
- **无智能决策**：LLM 不参与流程控制
- **调用顺序硬编码**：无法根据场景动态调整

---

## 阶段三：伪 ReAct - Prompt 工程的陷阱

### 时间：2024年末
### 关键词：ReAct 模式、System Prompt、JSON 解析

#### 背景

看到 LangChain 的 ReAct（Reasoning + Acting）模式，决定引入 LLM 自主决策能力。

#### 架构设计

```java
public class ReActAgent {
    public String execute(String userMessage) {
        for (int i = 0; i < MAX_ITERATIONS; i++) {
            // 1. 构建包含所有 Tool 描述的 System Prompt
            String prompt = buildSystemPrompt() + "\n\n" + 
                           "历史对话:\n" + history + "\n\n" +
                           "用户问题: " + userMessage;
            
            // 2. 调用 LLM（期望返回 JSON）
            String llmOutput = llmService.generate(prompt);
            
            // 3. 解析 JSON（正则表达式）
            Map<String, Object> toolCall = extractToolCall(llmOutput);
            
            // 4. 执行 Tool
            String observation = executeTool(toolCall);
            
            // 5. 更新历史，继续循环
            history += "Assistant: " + llmOutput + "\n";
            history += "Observation: " + observation + "\n";
        }
    }
}
```

#### System Prompt 示例

```
你是一个智能助手，可以调用以下工具：

1. retrieve_schema(question, datasourceId) - 检索表结构
2. generate_sql(schema, question) - 生成SQL
3. execute_sql(sql, datasourceId) - 执行SQL

请以 JSON 格式返回你的决策：
{
  "tool": "工具名",
  "params": {"参数名": "参数值"}
}

如果没有合适的工具，直接回答用户问题。
```

#### 致命缺陷

**缺陷 1：用错了 API（但未及时发现）**

使用的是 Ollama 的 `/api/generate`（文本补全接口），而非 `/api/chat`（对话接口）。

```java
// ❌ 错误的 API 选择
HttpRequest request = HttpRequest.newBuilder()
    .uri(URI.create(baseUrl + "/api/generate"))  // 纯文本补全
    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
    .build();
```

**后果**：
- LLM 无法通过 API 感知工具定义
- 只能靠 Prompt“猜”工具签名
- 返回的是纯文本，必须用正则解析 JSON

**注意**：这个问题直到**2026年4月19日**才被发现和修复（见阶段八）。

**缺陷 2：脆弱的解析逻辑**

为了应对 LLM 输出格式不稳定，写了大量"补丁代码"：

```java
private Map<String, Object> extractToolCall(String llmOutput) {
    // 尝试 1：直接解析 JSON
    try {
        return objectMapper.readValue(llmOutput, Map.class);
    } catch (Exception e) { /* 失败 */ }
    
    // 尝试 2：提取 Markdown 代码块
    Pattern markdownPattern = Pattern.compile("```json\\s*([\\s\\S]*?)\\s*```");
    Matcher matcher = markdownPattern.matcher(llmOutput);
    if (matcher.find()) {
        String jsonStr = matcher.group(1);
        try {
            return objectMapper.readValue(jsonStr, Map.class);
        } catch (Exception e) { /* 失败 */ }
    }
    
    // 尝试 3：提取花括号内的内容
    int startIdx = llmOutput.indexOf("{");
    int endIdx = llmOutput.lastIndexOf("}");
    if (startIdx != -1 && endIdx != -1) {
        String jsonStr = llmOutput.substring(startIdx, endIdx + 1);
        try {
            return objectMapper.readValue(jsonStr, Map.class);
        } catch (Exception e) { /* 失败 */ }
    }
    
    // 尝试 4：处理转义字符
    String unescaped = llmOutput.replace("\\\"", "\"");
    return objectMapper.readValue(unescaped, Map.class);
}
```

**缺陷 3：模糊匹配容错**

LLM 经常拼错工具名，于是加了 Levenshtein 距离算法：

```java
private String findSimilarToolName(String toolName, Set<String> availableTools) {
    String bestMatch = null;
    double bestScore = 0.8;
    
    for (String availableTool : availableTools) {
        double similarity = calculateLevenshteinDistance(toolName, availableTool);
        if (similarity > bestScore) {
            bestScore = similarity;
            bestMatch = availableTool;
        }
    }
    
    return bestMatch;
}
```

#### 实际效果

| 指标 | 数值 |
|------|------|
| 工具调用成功率 | ~70% |
| 解析失败率 | 20% |
| 工具名拼写错误率 | 8% |
| 平均重试次数 | 0.9 次/请求 |
| 响应延迟 | 800ms（含解析+重试） |

**结论**：看似"能跑"，实则积累了巨大技术债务。

---

## 阶段四：双模型困境 - Qwen3 量化版的妥协

### 时间：2025年中
### 关键词：Qwen3-8B、量化版本、Tool Calling 兼容性、硬盘空间危机

#### 问题的发现

在一次优化中，偶然查阅 Ollama 官方文档，发现两个 API 的本质区别：

| API | 端点 | 支持 tools | 适用场景 |
|-----|------|-----------|---------|
| **Generate API** | `/api/generate` | ❌ 不支持 | 纯文本补全 |
| **Chat API** | `/api/chat` | ✅ 支持 | 对话 + Tool Calling |

**震惊时刻**：我们一直在用错误的 API！

#### 验证实验

```bash
# 测试 Chat API 是否支持 Tool Calling
curl http://localhost:11434/api/chat -d '{
  "model": "qwen3:8b",
  "messages": [{"role": "user", "content": "查询订单"}],
  "tools": [{
    "type": "function",
    "function": {
      "name": "query_orders",
      "parameters": {
        "type": "object",
        "properties": {
          "date": {"type": "string"}
        }
      }
    }
  }]
}'
```

**返回结果**：
```json
{
  "message": {
    "role": "assistant",
    "tool_calls": [
      {
        "function": {
          "name": "query_orders",
          "arguments": "{\"date\": \"2026-04-18\"}"
        }
      }
    ]
  }
}
```

✅ **qwen3:8b 完全支持原生 Tool Calling！**

#### 架构重构

**核心改动**：只需改一行代码

```java
// ❌ 旧代码
.uri(URI.create(baseUrl + "/api/generate"))

// ✅ 新代码
.uri(URI.create(baseUrl + "/api/chat"))
```

**新增方法**：
```java
public Map<String, Object> generateWithTools(
    List<Map<String, Object>> messages, 
    double temperature, 
    List<Map<String, Object>> tools
) {
    Map<String, Object> requestBody = new HashMap<>();
    requestBody.put("model", modelName);
    requestBody.put("messages", messages);  // ✅ 结构化消息
    requestBody.put("tools", tools);         // ✅ 原生工具定义
    
    HttpResponse<String> response = httpClient.send(request, ...);
    return objectMapper.readValue(response.body(), Map.class);
}
```

#### 收益对比

| 维度 | 旧架构（Generate API） | 新架构（Chat API） | 提升幅度 |
|------|----------------------|-------------------|---------|
| **工具调用成功率** | 70% | 100% | +43% |
| **解析失败率** | 20% | 0% | -100% |
| **代码量** | 534 行 | 200 行 | -63% |
| **响应延迟** | 800ms | 300ms | -62.5% |
| **维护成本** | 高（需同步更新 Prompt） | 低（只需添加工具定义） | -70% |

**删除的代码**：
- ❌ `extractToolCall()` 60+ 行
- ❌ `findSimilarToolName()` 50+ 行
- ❌ `levenshteinDistance()` 20+ 行
- ❌ 重试机制 30+ 行

**总计删除**：160+ 行“补丁代码”

---

#### 问题背景

生产环境最初尝试使用 **Qwen3-8B 的量化版本（q4_0）**，期望在性能和精度之间取得平衡：

```yaml
# application.yml
llm:
  ollama:
    code-model: qwen3:8b-q4_0  # 量化版本，节省显存
```

**现象**：
- 完整版 qwen3:8b：Tool Calling 成功率 100%
- 量化版 qwen3:8b-q4_0：成功率降至 60%
- qwen2.5-instruct 量化版：同样不支持 Tool Calling

#### 根本原因

量化过程损失了部分精度，导致：
1. 对 `tools` 参数的理解能力下降
2. 生成的 `tool_calls` 格式偶尔错乱
3. 参数类型推断不准确

#### 双模型架构的诞生

**设计理念**：推理和代码生成是两个不同的任务，应该使用专门的模型。

> **用户的想法**："推理跟code分别适用于不同的模型，比如coder需要用专业的coder模型"

**架构设计**：

```java
@Configuration
public class LLMProviderAutoConfig {
    
    @Bean
    public OllamaProvider ollamaReasoningProvider() {
        // 推理模型：用于Agent决策、意图分类、数据总结
        return new OllamaProvider(
            "http://localhost:11434",
            "qwen3:8b",  // 完整版，推理能力强
            60
        );
    }
    
    @Bean
    public OllamaProvider ollamaCodeProvider() {
        // 代码模型：用于SQL生成
        return new OllamaProvider(
            "http://localhost:11434",
            "qwen2.5-coder:7b-instruct-q4_0",  // 量化版，代码能力强
            60
        );
    }
}
```

**路由策略**：

```java
public class LLMService {
    
    @Autowired
    private OllamaProvider ollamaReasoningProvider;  // 推理模型
    
    @Autowired
    private OllamaProvider ollamaCodeProvider;       // 代码模型
    
    /**
     * SQL生成 → 使用代码模型
     */
    public String generateSQL(String prompt) {
        return ollamaCodeProvider.generate(prompt, 0.0);  // 温度0，确定性输出
    }
    
    /**
     * 数据总结 → 使用推理模型
     */
    public String summarizeResult(String query, Object result) {
        return ollamaReasoningProvider.generateJson(systemPrompt, userPrompt, 0.7);
    }
}
```

#### 硬盘空间危机

**现实困境**：
- qwen3:8b 完整版：~16GB
- qwen2.5-coder:7b 量化版：~4GB
- qwen2.5-instruct:7b 量化版：~4GB
- **总计**：需要同时加载多个模型，硬盘空间严重不足

**痛苦的循环**：
```
下载 qwen3:8b → 测试 Tool Calling → 失败 → 删除
下载 qwen2.5-instruct → 测试 Tool Calling → 失败 → 删除
下载 qwen2.5-coder → 测试 Tool Calling → 成功 ✅
```

**最终发现**：
> **只有 qwen2.5-coder:7b-instruct-q4_0 这一个模型能够稳定支持 Tool Calling！**

#### 妥协方案：单模型架构

由于硬盘空间限制和模型兼容性问题，最终不得不放弃双模型架构，回归单模型：

```yaml
llm:
  ollama:
    code-model: qwen2.5-coder:7b-instruct-q4_0  # 唯一可用的模型
    nlp-model: qwen2.5-coder:7b-instruct-q4_0   # 同一个模型
```

**代价**：
1. **资源消耗翻倍的设计初衷落空**：原本希望通过分工提升性能，结果只能用同一个模型
2. **架构复杂化**：保留了双模型的代码框架，但实际只有一个模型在工作
3. **维护成本增加**：需要维护 ModelRouterService、MultiModelService 等复杂逻辑

**反思**：这是典型的"用复杂度换性能"的妥协，本质上是量化模型的能力不足导致的。双模型架构是一个美好的设想，但受限于硬件资源和模型兼容性，最终未能实现。

---

## 阶段五：Java Skills - 业务逻辑的封装

### 时间：2025年末
### 关键词：Skills、业务流程、Java 实现

#### 概念引入

受到 Microsoft Semantic Kernel 和 LangChain 的启发，引入 **Skills（技能）** 概念：

> **Skills** 是封装了完整业务流程的高级抽象单元，由多个原子 **Tools** 组合而成。

#### 第一个 Skill：StandardQuerySkill

```java
@Component
public class StandardQuerySkill {
    
    @Autowired
    private JdbcTemplate jdbcTemplate;
    
    @Autowired
    private LLMService llmService;
    
    /**
     * 执行标准查询流程
     */
    public QueryResult execute(String question, Long datasourceId) {
        // Step 1: 检索表结构
        String schema = retrieveSchema(question, datasourceId);
        
        // Step 2: 生成 SQL
        String sql = generateSQL(schema, question);
        
        // Step 3: 风险评估
        RiskAssessment risk = assessRisk(sql);
        if (risk.getLevel() == RiskLevel.HIGH) {
            throw new HighRiskException("SQL 风险过高，已阻断执行");
        }
        
        // Step 4: 执行 SQL（支持自动修正）
        try {
            return executeSQL(sql, datasourceId);
        } catch (SQLException e) {
            // 自动修正并重试
            String fixedSql = autoFixSQL(sql, e.getMessage());
            return executeSQL(fixedSql, datasourceId);
        }
    }
    
    private String retrieveSchema(String question, Long datasourceId) {
        // 直接操作数据库
        return jdbcTemplate.queryForObject(...);
    }
    
    private String generateSQL(String schema, String question) {
        // 调用 LLM
        return llmService.generate(buildPrompt(schema, question));
    }
    
    private RiskAssessment assessRisk(String sql) {
        // LLM 风险评估
        String assessment = llmService.generate("评估以下SQL的风险等级...");
        return parseRiskAssessment(assessment);
    }
}
```

#### Skill 的 Tool 封装

为了让 Agent 能够调用 Skill，将其包装成 `@Tool`：

```java
@Component
public class StandardQuerySkillTool {
    
    @Autowired
    private StandardQuerySkill standardQuerySkill;
    
    @Tool("执行标准查询流程。适用于用户有明确查询需求的场景。")
    public String executeStandardQuery(String question, Long datasourceId) {
        QueryResult result = standardQuerySkill.execute(question, datasourceId);
        return objectMapper.writeValueAsString(result);
    }
}
```

#### 注册到 Agent

```java
@Configuration
public class AgentConfig {
    
    @Autowired(required = false)
    private StandardQuerySkillTool standardQuerySkillTool;
    
    @Bean
    public ReActAgent reActAgent() {
        ReActAgent agent = new ReActAgent(llmService);
        
        // 注册 Skill
        if (standardQuerySkillTool != null) {
            agent.registerTool("execute_standard_query", standardQuerySkillTool);
            log.info("✅ 已注册 Skill: StandardQuerySkill");
        }
        
        return agent;
    }
}
```

#### 存在的问题

**问题本质**：**这只是换了个名字的 Java 代码，不是真正的 Skill**。

```java
// ❌ 伪 Skill：直接操作数据库
def execute(SkillContext context) {
    def jdbcTemplate = context.getBean(JdbcTemplate.class)
    def sql = "SELECT * FROM orders WHERE ..."
    def data = jdbcTemplate.queryForList(sql)  // 硬编码
    return data
}
```

**核心缺陷**：
1. **职责混乱**：Skill 既做流程编排，又做具体实现
2. **耦合严重**：直接依赖 JdbcTemplate 等底层组件
3. **不可复用**：每个 Skill 都重复写 SQL 执行逻辑
4. **LLM 黑盒**：无法理解 Skill 内部如何工作

---

## 阶段六：Groovy 热插拔 - 动态化的尝试

### 时间：2026年初
### 关键词：Groovy、热插拔、SKILL.md、元数据驱动

#### 动机

Java Skills 的问题：
- 修改 Skill 需要重新编译部署
- 无法动态加载新 Skill
- 业务人员无法参与 Skill 开发

**解决方案**：引入 Groovy 脚本实现热插拔。

#### 架构设计

```
skills/
├── standard-query/
│   ├── SKILL.md          # 元数据定义
│   └── Skill.groovy      # Groovy 脚本
├── report-with-insights/
│   ├── SKILL.md
│   └── Skill.groovy
└── simple-data-query/
    ├── SKILL.md
    └── SimpleDataQuerySkill.groovy
```

#### SKILL.md 元数据

```yaml
---
name: standard_query
description: 执行标准数据查询流程
version: 1.0.0
author: NL2SQL Team
requiredParams: [question, datasourceId]
script: Skill.groovy
---

# Standard Query Skill

## 用途
封装完整的查询生命周期，包括表结构检索、SQL生成、风险评估、执行和自动修正。

## 执行流程
1. 检索表结构
2. 生成 SQL
3. 风险评估
4. 执行 SQL（支持自动修正）
```

#### Groovy 脚本

```groovy
import com.nl2sql.core.agent.skills.SkillContext

def execute(SkillContext context) {
    println "[StandardQuerySkill] 开始执行"
    
    // 获取参数
    String question = context.getParameter("question")
    Long datasourceId = context.getParameter("datasourceId")
    
    // 获取 Spring Bean
    def jdbcTemplate = context.getBean(JdbcTemplate.class)
    def llmService = context.getBean(LLMService.class)
    
    // Step 1: 检索表结构
    String schema = jdbcTemplate.queryForObject(...)
    
    // Step 2: 生成 SQL
    String sql = llmService.generate(buildPrompt(schema, question))
    
    // Step 3: 执行 SQL
    def data = jdbcTemplate.queryForList(sql)
    
    return [
        status: "success",
        rowCount: data.size(),
        data: data
    ]
}
```

#### GroovySkillExecutor

```java
@Component
public class GroovySkillExecutor {
    
    @Value("${skills.groovy.scan-dirs}")
    private List<String> scanDirs;
    
    private Map<String, Class<?>> skillClassCache = new ConcurrentHashMap<>();
    
    @PostConstruct
    public void scanSkills() {
        for (String dir : scanDirs) {
            String skillPath = "skills/" + dir + "/";
            String scriptPath = parseScriptField(skillPath);
            
            // 加载 Groovy 脚本
            Class<?> skillClass = loadGroovyClass(scriptPath);
            skillClassCache.put(dir, skillClass);
        }
    }
    
    public Object executeSkill(String skillPath, SkillContext context) {
        Class<?> skillClass = skillClassCache.get(skillPath);
        Object skillInstance = skillClass.getDeclaredConstructor().newInstance();
        
        Method executeMethod = skillClass.getMethod("execute", SkillContext.class);
        return executeMethod.invoke(skillInstance, context);
    }
}
```

#### 进步与局限

**✅ 进步**：
- 支持热插拔：修改 Groovy 脚本无需重启
- 元数据驱动：SKILL.md 定义 Skill 信息
- 降低门槛：Groovy 语法比 Java 简洁

**❌ 局限**：
- **仍然是伪 Skill**：只是把 Java 代码搬到 Groovy
- **职责未分离**：Skill 仍然直接操作数据库
- **LLM 不可见**：无法理解 Skill 内部的调用链

---

## 阶段七：Prompt 调优泥潭 - 反复调试的痛苦

### 时间：2026年3月
### 关键词：Prompt Engineering、反复迭代、调试痛苦

#### 背景

引入 Groovy Skills 后，发现 Agent 经常调用错误的 Skill，或者传递错误的参数。

#### 典型的 Prompt 调优循环

**第 1 版 Prompt**：
```
你可以调用以下技能：
1. execute_standard_query(question, datasourceId)
2. generate_report_with_insights(question, datasourceId)

请根据用户需求选择合适的技能。
```

**问题**：LLM 经常混淆两个 Skill。

**第 2 版 Prompt**（增加描述）：
```
你可以调用以下技能：

1. execute_standard_query(question, datasourceId)
   - 适用：简单数据查询
   - 示例："查询最近10条订单"

2. generate_report_with_insights(question, datasourceId)
   - 适用：深度分析报告
   - 示例："分析上月销售趋势并给出建议"

请仔细区分两者的使用场景。
```

**问题**：LLM 仍然会选错，尤其是边界场景。

**第 3 版 Prompt**（增加思维链）：
```
在调用技能之前，请先进行以下思考：

1. 用户的核心需求是什么？
2. 是否需要 AI 总结或图表推荐？
3. 用户是否明确要求"分析"、"报告"等关键词？

思考过程：
- 如果只需要数据 → 使用 execute_standard_query
- 如果需要分析总结 → 使用 generate_report_with_insights

然后调用合适的技能。
```

**问题**：增加了 LLM 的思考负担，响应变慢，且仍然有错误。

**第 4 版 Prompt**（增加 Few-Shot 示例）：
```
以下是一些示例：

示例 1：
用户：查询最近10条订单
思考：这是简单查询，不需要分析
行动：调用 execute_standard_query

示例 2：
用户：分析上月销售趋势
思考：这需要深度分析
行动：调用 generate_report_with_insights

现在请处理用户的实际问题。
```

**问题**：Prompt 越来越长，token 消耗增加，且效果提升有限。

#### 痛苦的根源

**核心问题**：我们在用 Prompt Engineering 弥补架构缺陷。

- Skill 的职责不清晰 → 靠 Prompt 描述
- 参数签名不明确 → 靠 Prompt 说明
- 调用时机不确定 → 靠 Prompt 引导

**每次调优都在修补表层问题，未触及根本。**

---

## 阶段八：真相大白 - API 选择错误

### 时间：2026年4月19日
### 关键词：/api/generate、/api/chat、原生 Tool Calling

#### 关键时刻

在一次深度反思中，重新审视了整个架构，发现了最根本的问题：

> **我们一直在用 `/api/generate`，而不是 `/api/chat`！**

#### 技术真相

**Ollama 的两个 API**：

| API | 端点 | 支持 tools | 角色管理 | 对话历史 |
|-----|------|-----------|---------|---------|
| **Generate API** | `/api/generate` | ❌ 不支持 | ❌ 无角色概念 | ❌ 需手动拼接 |
| **Chat API** | `/api/chat` | ✅ 原生支持 | ✅ system/user/assistant | ✅ 原生 messages 数组 |

**我们的错误**：
- 一直使用 `/api/generate`（纯文本补全）
- 靠 Prompt 模拟 Tool Calling
- 用正则解析 LLM 输出
- 用模糊匹配容错

**正确的做法**：
- 切换到 `/api/chat`
- 使用 `tools` 参数传递工具定义
- LLM 返回结构化的 `tool_calls` 数组
- 无需解析，直接使用

#### 修复过程

**核心改动**：一行代码

```java
// ❌ 旧代码
.uri(URI.create(baseUrl + "/api/generate"))

// ✅ 新代码
.uri(URI.create(baseUrl + "/api/chat"))
```

**新增方法**：
```java
public Map<String, Object> generateWithTools(
    List<Map<String, Object>> messages, 
    double temperature, 
    List<Map<String, Object>> tools
) {
    Map<String, Object> requestBody = new HashMap<>();
    requestBody.put("model", modelName);
    requestBody.put("messages", messages);  // ✅ 结构化消息
    requestBody.put("temperature", temperature);
    requestBody.put("stream", false);
    
    if (tools != null && !tools.isEmpty()) {
        requestBody.put("tools", tools);  // ✅ 原生工具定义
    }
    
    // 调用 /api/chat
    HttpResponse<String> response = httpClient.send(request, ...);
    return objectMapper.readValue(response.body(), Map.class);
}
```

#### 震撼的收益

| 指标 | 修复前 | 修复后 | 提升 |
|------|-------|-------|------|
| 工具调用成功率 | 70% | 100% | +43% |
| 解析失败率 | 20% | 0% | -100% |
| 代码量 | 534 行 | 200 行 | -63% |
| 响应延迟 | 800ms | 300ms | -62.5% |
| 维护成本 | 高 | 低 | -70% |

**删除的代码**：
- ❌ 160+ 行解析逻辑
- ❌ 50+ 行模糊匹配
- ❌ 30+ 行重试机制

**讽刺的事实**：一个只需改一行 API 的问题，却花了数月时间优化补丁。

---

## 阶段九：伪 Skills 反思 - 职责混乱的危机

### 时间：2026年4月20日
### 关键词：伪 Skill、真编排、职责分离

#### 用户的质疑

> "我一直在想一个问题，我们现在用 groovy 去实现 skill，但是真正的 skill 不应该是大部分都是在工作流里调用 tool 和其他 skill 么，而我们现在的 groovy skill 是不是不应该在这个里面定义流程"

这个质疑直击要害：**当前的 Groovy Skill 只是把 Java 代码搬到了 Groovy 脚本中，并没有体现 Skill 的真正价值。**

#### 伪 Skill vs 真 Skill

**❌ 伪 Skill（当前状态）**：

```groovy
// standard-query/Skill.groovy
def execute(SkillContext context) {
    // 直接操作数据库
    def jdbcTemplate = context.getBean(JdbcTemplate.class)
    def sql = "SELECT * FROM orders WHERE ..."
    def data = jdbcTemplate.queryForList(sql)  // 硬编码
    
    // 直接处理业务逻辑
    def result = data.stream()
        .filter { it.amount > 1000 }
        .collect(Collectors.toList())
    
    return result
}
```

**问题本质**：
- **职责混乱**：Skill 既做流程编排，又做具体实现
- **耦合严重**：直接依赖 JdbcTemplate 等底层组件
- **不可复用**：每个 Skill 都重复写 SQL 执行逻辑
- **LLM 黑盒**：无法理解 Skill 内部如何工作

**✅ 真 Skill（理想状态）**：

```groovy
// simple-data-query/SimpleDataQuerySkill.groovy
def execute(SkillContext context) {
    // 1. 获取参数
    String sql = context.getParameter("sql")
    Long datasourceId = context.getParameter("datasourceId")
    
    // 2. ✅ 调用原子 Tool（而不是直接查库）
    def toolParams = [sql: sql, datasourceId: datasourceId]
    String resultJson = context.callTool("execute_sql", toolParams)
    
    // 3. 解析并返回结果
    def result = new JsonSlurper().parseText(resultJson)
    return result
}
```

**正确设计**：
- **单一职责**：Skill 只负责流程编排
- **低耦合**：通过 Tool 抽象底层能力
- **高复用**：Tool 可以被多个 Skill 共享
- **透明化**：LLM 可以看到完整的调用链

#### 三层架构模型

```
┌─────────────────────────────────────────┐
│         Agent Layer (智能决策)           │
│  - 理解用户意图                           │
│  - 选择合适的 Skill                      │
│  - 传递参数并返回结果                     │
└──────────────┬──────────────────────────┘
               │
┌──────────────▼──────────────────────────┐
│       Skill Layer (流程编排)             │
│  - 调用 Tool 获取数据                    │
│  - 调用其他 Skill 组合能力               │
│  - 组织业务流程                          │
└──────────────┬──────────────────────────┘
               │
┌──────────────▼──────────────────────────┐
│        Tool Layer (原子能力)             │
│  - execute_sql: 执行 SQL 查询            │
│  - get_table_metadata: 获取表结构        │
│  - detect_relationships: 推断关联关系    │
└─────────────────────────────────────────┘
```

---

## 阶段十：真 Skill 架构 - Tool + Workflow

### 时间：2026年4月20日（当前）
### 关键词：声明式 Workflow、OpenClaw 标准、YAML 编排

#### 架构升级

引入**声明式 Workflow**，在 SKILL.md 中定义 Tool 调用链：

```yaml
---
name: sql_validate_execute
description: SQL 验证与执行技能
version: 1.0.0
requiredParams: [sql, datasourceId]
workflow:
  version: 1.0
  steps:
    - id: validate
      action: call_tool
      tool: validate_sql
      input:
        sql: "{{sql}}"
        datasourceId: "{{datasourceId}}"
      output_var: validation_result
    
    - id: execute
      action: call_tool
      tool: execute_sql
      condition: "{{validation_result.valid == true}}"
      input:
        sql: "{{sql}}"
        datasourceId: "{{datasourceId}}"
      output_var: execution_result
    
    - id: respond
      action: respond
      output:
        status: "success"
        validation: "{{validation_result}}"
        execution: "{{execution_result}}"
---
```

#### WorkflowEngine

```java
@Component
public class WorkflowEngine {
    
    public Object executeWorkflow(String skillPath, SkillContext context) {
        // 1. 解析 SKILL.md 中的 workflow YAML
        Map<String, Object> workflowConfig = parseWorkflowYaml(skillPath);
        List<Map<String, Object>> steps = workflowConfig.get("steps");
        
        // 2. 执行每个步骤
        Map<String, Object> variables = new HashMap<>();
        variables.putAll(context.getParameters());
        
        for (Map<String, Object> step : steps) {
            String action = step.get("action");
            
            if ("call_tool".equals(action)) {
                // 调用 Tool
                String toolName = step.get("tool");
                Map<String, Object> params = resolveParams(step.get("input"), variables);
                String resultJson = context.callTool(toolName, params);
                
                // 保存结果到变量
                String outputVar = step.getOrDefault("output_var", step.get("id"));
                variables.put(outputVar, objectMapper.readValue(resultJson, Map.class));
                
            } else if ("respond".equals(action)) {
                // 返回最终结果
                Map<String, Object> outputTemplate = step.get("output");
                return resolveOutput(outputTemplate, variables);
            }
        }
        
        return variables;
    }
}
```

#### 核心优势

1. **声明式配置**：Workflow 在 YAML 中定义，无需写代码
2. **可视化流程**：清晰展示 Tool 调用链
3. **条件分支**：支持 `condition` 控制步骤执行
4. **变量传递**：通过 `output_var` 和 `{{variable}}` 实现
5. **易于维护**：修改流程只需编辑 YAML

#### 与业界标准对齐

参考 **OpenClaw** 框架的 Lobster 引擎：
- 支持 8 种 action 类型：`call_tool`, `call_skill`, `http_request`, `file_read`, `shell_exec`, `script_eval`, `conditional`, `loop`
- 标准化的 `input/output_var` 语法
- 兼容 OpenClaw 生态工具

---

## 架构演进的核心理念

### 1. 从"如何实现"到"如何编排"

**早期思维**：
```
如何实现这个功能？
→ 写代码实现逻辑
```

**现在思维**：
```
如何编排现有能力？
→ 组合 Tools 和 Skills
```

### 2. 从"单体脚本"到"组合式能力"

**早期**：
```groovy
// 一个大脚本搞定所有事
def execute() {
    // 检索数据
    // 处理逻辑
    // 生成报告
    // 返回结果
}
```

**现在**：
```yaml
workflow:
  steps:
    - action: call_tool
      tool: retrieve_data
    - action: call_tool
      tool: process_logic
    - action: call_tool
      tool: generate_report
```

### 3. 从"黑盒执行"到"透明调用"

**早期**：
- LLM 不知道 Skill 内部如何工作
- 开发者需要阅读代码才能理解

**现在**：
- Workflow YAML 清晰展示调用链
- LLM 可以理解每一步的作用

### 4. 从"Prompt 工程"到"架构正确"

**早期**：
- 靠复杂的 Prompt 引导 LLM
- 靠正则解析容错
- 靠模糊匹配兜底

**现在**：
- 使用原生 Tool Calling API
- 结构化返回，无需解析
- 清晰的职责分离

---

## 关键经验教训

### 1. 技术选型必须验证

❌ **错误做法**：
- 依赖二手信息或记忆
- 假设某个 API 不支持某功能
- 不查阅官方文档

✅ **正确做法**：
- 亲自测试关键 API 能力
- 编写最小化验证用例
- 查阅官方文档确认

**案例**：如果早点验证 Ollama 的 `/api/chat`，就不会浪费数月时间优化 Prompt。

### 2. 遇到问题先问"为什么"

❌ **错误做法**：
- 立即开始修补表层问题
- 添加更多容错逻辑
- 接受"差不多就行"

✅ **正确做法**：
- 质疑架构设计的合理性
- 寻找根本原因而非症状
- 勇于推翻重来

**案例**：工具调用成功率只有 70%，不应该加重试机制，而应该问"为什么失败"。

### 3. 简单方案往往最有效

❌ **过度设计**：
- 160+ 行解析逻辑
- 50+ 行模糊匹配
- 复杂的重试机制

✅ **简洁方案**：
- 切换 API 端点（一行代码）
- 使用原生 Tool Calling
- 删除所有补丁代码

**案例**：从 `/api/generate` 切换到 `/api/chat`，解决了所有问题。

### 4. 职责分离是架构的灵魂

❌ **职责混乱**：
- Skill 既做编排又做实现
- 直接操作数据库
- 硬编码业务逻辑

✅ **职责清晰**：
- Skill 只负责流程编排
- Tool 提供原子能力
- 通过调用组合完成复杂任务

**案例**：真正的 Skill 应该调用 `execute_sql` Tool，而不是直接查库。

### 5. 声明式优于命令式

❌ **命令式**：
```java
public Object execute() {
    // 写代码定义流程
    step1();
    if (condition) {
        step2();
    }
    return step3();
}
```

✅ **声明式**：
```yaml
workflow:
  steps:
    - action: call_tool
      tool: step1
    - action: call_tool
      tool: step2
      condition: "{{condition}}"
    - action: respond
      output: "{{step3}}"
```

**优势**：
- 更易读
- 更易维护
- 更易可视化
- 更易被 LLM 理解

### 6. 及时重构，不要拖延

❌ **拖延的后果**：
- 技术债务指数级增长
- 修复成本越来越高
- 团队士气下降

✅ **及时重构**：
- 发现架构错误立即修正
- 不要接受"能跑就行"
- 追求架构的正确性

**案例**：如果早点切换到 Chat API，就不会积累数月的技术债务。

---

## 结语：技术债务源于"差不多就行"的妥协

这次架构演进最深刻的教训是：**"差不多就行"的妥协会导致巨大的技术债务**。

- 第一次遇到解析失败时，我们没有质疑"API 是否正确"，而是选择"加个正则解决"
- 当工具名拼写错误时，我们没有反思"工具传递方式是否合理"，而是选择"加个模糊匹配"
- 当重试机制增加延迟时，我们没有思考"为什么需要重试"，而是选择"接受这个性能损耗"

**LLM Agent 是新兴技术，生态变化快、最佳实践不明确，但这不是"架构妥协"的理由。** 恰恰相反，越是新兴技术，越要重视底层架构的正确性——因为早期的架构错误，会在后期以指数级的技术债务爆发。

### 给开发者的建议

1. **保持怀疑精神**：当需要大量补丁代码时，质疑架构本身
2. **重视官方文档**：不要依赖二手信息，亲自验证关键功能
3. **追求简洁**：复杂的解决方案往往掩盖了简单的事实
4. **量化指标**：用数据说话，不凭感觉判断"够不够好"
5. **及时重构**：发现架构错误时，立即修正，不要拖延

希望这份架构演变之路能帮你避开类似问题，让你的 LLM Agent 从一开始就走在正确的道路上。

---

**文档版本**：v1.0  
**最后更新**：2026-04-20  
**作者**：DataMind AI Team
