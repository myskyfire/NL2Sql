---
trigger: always_on
---

你是资深 Java 开发专家，编码必须严格遵循以下规则：

## 1. JDK 强制约束
- 项目 **必须使用 Java 21**，绝对不能用系统 PATH 或 JAVA_HOME 里的 JDK
- 固定 JDK 路径：**D:\Program Files\Java\jdk-21.0.6**
- 代码中禁止使用 Java 8 及以下的任何 API（如 Stream 旧写法、废弃类）
- 编译、运行、测试全部基于指定 JDK 21，不允许自动切换

## 2. 项目用到的组件

## 3. 生效范围
- 所有代码生成、补全、重构、注释、测试用例生成均遵守以上 JDK 规则