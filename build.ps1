# ========================================
# NLP2SQL Maven 编译脚本
# ========================================

# 设置控制台代码页为UTF-8
chcp 65001 | Out-Null

# 配置 JDK 21 路径
$JDK21_PATH = "D:\Program Files\Java\jdk-21.0.6"

# 检查 JDK 21 是否存在
if (-Not (Test-Path $JDK21_PATH)) {
    Write-Host "❌ 错误: 未找到 JDK 21 在路径: $JDK21_PATH" -ForegroundColor Red
    exit 1
}

# 设置 JAVA_HOME 为 JDK 21
$env:JAVA_HOME = $JDK21_PATH
$env:PATH = "$JDK21_PATH\bin;$env:PATH"

# 验证 Java 版本
Write-Host "✅ 使用 JDK 21: $JDK21_PATH" -ForegroundColor Green
java -version
Write-Host ""

# 进入项目根目录
cd "E:\work\idea workspace\NLP2Sql"

# 清理并编译
Write-Host "🔨 开始 Maven 编译..." -ForegroundColor Green
mvn clean install -DskipTests

if ($LASTEXITCODE -eq 0) {
    Write-Host "✅ 编译成功！" -ForegroundColor Green
} else {
    Write-Host "❌ 编译失败！" -ForegroundColor Red
    exit 1
}
