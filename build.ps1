# NL2SQL 项目构建脚本
# 自动配置 JDK 21 环境并执行 Maven 命令

$JDK_PATH = "D:\Program Files\Java\jdk-21.0.6"

# 检查 JDK 是否存在
if (-not (Test-Path $JDK_PATH)) {
    Write-Host "错误: JDK 21 路径不存在: $JDK_PATH" -ForegroundColor Red
    exit 1
}

# 设置 JAVA_HOME
$env:JAVA_HOME = $JDK_PATH
$env:PATH = "$JDK_PATH\bin;$env:PATH"

Write-Host "✓ 已设置 JAVA_HOME: $env:JAVA_HOME" -ForegroundColor Green
Write-Host "✓ Java 版本: $(java -version 2>&1 | Select-String 'version' | ForEach-Object { $_.Line })" -ForegroundColor Green
Write-Host ""

# 执行传入的 Maven 命令
if ($args.Count -eq 0) {
    Write-Host "用法: .\build.ps1 <maven-command>" -ForegroundColor Yellow
    Write-Host "示例: .\build.ps1 clean package -DskipTests" -ForegroundColor Yellow
    exit 0
}

Write-Host "执行命令: mvn $($args -join ' ')" -ForegroundColor Cyan
Write-Host ""

mvn @args
