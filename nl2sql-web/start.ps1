# 设置控制台编码为UTF-8
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8

# 创建logs目录（如果不存在）
if (-not (Test-Path "logs")) {
    New-Item -ItemType Directory -Path "logs" | Out-Null
    Write-Host "✓ 创建logs目录" -ForegroundColor Green
}

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  NLP2SQL Enterprise Agent 启动脚本" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""
Write-Host "控制台编码: UTF-8" -ForegroundColor Green
Write-Host "日志目录: logs/" -ForegroundColor Green
Write-Host "  - app.log (JSON格式)" -ForegroundColor Gray
Write-Host "  - app-text.log (文本格式)" -ForegroundColor Gray
Write-Host ""

# 启动应用（配置HTTP代理）
# 修改下面的代理地址为你的实际代理服务器
$PROXY_HOST = "127.0.0.1"
$PROXY_PORT = "7890"
$JAVA_HOME = "D:\Program Files\Java\jdk-21.0.6"

& "$JAVA_HOME\bin\java.exe" "-Dhttp.proxyHost=$PROXY_HOST" "-Dhttp.proxyPort=$PROXY_PORT" "-Dhttps.proxyHost=$PROXY_HOST" "-Dhttps.proxyPort=$PROXY_PORT" "-Djava.net.useSystemProxies=true" -jar target\nl2sql-web-1.0.0.jar
