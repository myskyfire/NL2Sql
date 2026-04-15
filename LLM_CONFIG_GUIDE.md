# LLM双模型架构配置指南

## 📋 架构概述

系统采用**双LLM架构**，支持企业级私域部署：

- **推理模型（Reasoning Model）**：用于Agent决策、意图理解、数据总结
- **代码模型（Code Model）**：专门用于SQL生成

**支持模式：**
1. **双模型模式**：两个不同的模型，各司其职
2. **单模型降级**：两个配置指向同一模型（资源受限场景）

---

## 🔧 配置方式

### 1. Ollama本地部署（默认）

```yaml
# application.yml
llm:
  provider: ollama
  
  # 推理模型
  reasoning:
    model: qwen3:8b
    temperature: 0.7
  
  # 代码模型
  code:
    model: qwen2.5-coder:7b
    temperature: 0.0
  
  # Ollama配置
  ollama:
    base-url: http://localhost:11434
    timeout: 60
  
  # 降级策略
  fallback:
    single-model-mode: auto  # 自动检测
```

**单模型模式示例：**
```yaml
llm:
  provider: ollama
  reasoning:
    model: qwen2.5-coder:7b
  code:
    model: qwen2.5-coder:7b  # 相同模型
  fallback:
    single-model-mode: true
```

---

### 2. OpenAI兼容API（企业内部部署）

适用于企业内部部署的兼容OpenAI API的LLM服务（如vLLM、TGI等）：

```yaml
llm:
  provider: openai-compatible
  
  reasoning:
    model: qwen-plus
    temperature: 0.7
  
  code:
    model: qwen-coder
    temperature: 0.0
  
  openai-compatible:
    base-url: https://llm.internal.company.com/v1
    api-key: ${LLM_API_KEY}  # 从环境变量读取
    timeout: 30
  
  fallback:
    single-model-mode: false
```

---

## 🚀 启动验证

启动应用后查看日志：

```
[LLMService] 运行模式: 双模型模式
  - 推理模型: Ollama (qwen3:8b)
  - 代码模型: Ollama (qwen2.5-coder:7b)
[LLMService] 健康检查完成: reasoning=✅, code=✅
```

**单模型模式日志：**
```
[LLMService] 运行模式: 单模型模式（Ollama (qwen2.5-coder:7b)）
[LLMService] 健康检查完成: reasoning=✅, code=✅
```

---

## 📊 模型选择逻辑

系统根据任务类型自动路由：

| 任务类型 | 使用模型 | 示例 |
|---------|---------|------|
| Agent决策 | 推理模型 | ReAct循环中的Thought |
| SQL生成 | 代码模型 | NL2SQLTool.generateSQL |
| 数据总结 | 推理模型 | AISummaryTool |
| 意图分类 | 推理模型 | IntentClassifierTool |

---

## ⚠️ 注意事项

1. **温度设置建议**：
   - 推理模型：0.7（平衡创造性与准确性）
   - 代码模型：0.0（确定性输出，避免随机性）

2. **超时设置**：
   - Ollama本地：60秒
   - 企业API：30秒（网络延迟低）

3. **降级策略**：
   - `single-model-mode: auto` - 自动检测两个配置是否相同
   - `single-model-mode: true` - 强制单模型模式
   - `single-model-mode: false` - 强制双模型模式

4. **环境变量**：
   ```bash
   # Windows PowerShell
   $env:LLM_API_KEY="your-api-key"
   
   # Linux/Mac
   export LLM_API_KEY="your-api-key"
   ```

---

## 🔍 故障排查

### 问题1：模型不可用

**症状：**
```
⚠️ 推理模型不可用！
```

**解决：**
1. 检查Ollama服务是否启动：`ollama list`
2. 检查模型是否已下载：`ollama pull qwen3:8b`
3. 检查base-url配置是否正确

### 问题2：编译错误

**症状：**
```
程序包dev.langchain4j.model.openai不存在
```

**解决：**
确保父POM中已声明依赖版本：
```xml
<dependency>
    <groupId>dev.langchain4j</groupId>
    <artifactId>langchain4j-open-ai</artifactId>
    <version>${langchain4j.version}</version>
</dependency>
```

---

## 🎯 最佳实践

1. **开发环境**：使用Ollama + 单模型模式（节省资源）
2. **测试环境**：使用Ollama + 双模型模式（模拟生产）
3. **生产环境**：使用企业内部LLM服务 + 双模型模式（性能最优）

---

## 📝 扩展新的LLM提供者

如需支持其他LLM后端（如Azure OpenAI、阿里云百炼等）：

1. 实现`LLMProvider`接口
2. 创建配置类（参考`LLMProviderConfig`）
3. 在`application.yml`中添加配置项

示例结构：
```java
@Component
@ConditionalOnProperty(name = "llm.provider", havingValue = "azure")
public class AzureOpenAIProvider implements LLMProvider {
    // 实现接口方法
}
```
