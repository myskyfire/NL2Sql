# 🔐 API Key 配置指南

## 快速开始

### 方式1：环境变量（推荐）

```bash
# Windows PowerShell
$env:ALIYUN_API_KEY="sk-your-actual-key"

# Linux/Mac
export ALIYUN_API_KEY="sk-your-actual-key"
```

### 方式2：本地配置文件

1. 复制模板文件：
```bash
cp nl2sql-web/src/main/resources/application-local.yml.template \
   nl2sql-web/src/main/resources/application-local.yml
```

2. 编辑 `application-local.yml`，填入真实 API Key

3. 激活 local profile（在 `application.yml` 中）：
```yaml
spring:
  profiles:
    active: local
```

## 获取 API Key

1. 登录 [阿里云通义千问控制台](https://dashscope.console.aliyun.com/)
2. 进入 **API-KEY管理**
3. 创建或复制现有 API Key

## 安全提示

- ✅ **不要**将 API Key 硬编码到代码中
- ✅ **不要**提交包含 API Key 的文件到 Git
- ✅ 使用 `.gitignore` 忽略本地配置文件
- ✅ 定期轮换 API Key

## 已忽略的文件

以下文件已被 `.gitignore` 忽略，不会提交到 Git：
- `application-local.yml`
- `.env`
- `*.local.yml`
