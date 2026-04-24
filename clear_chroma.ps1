# ============================================
# Chroma 集合清理脚本
# 用途：删除所有 Chroma 集合并重启服务
# 使用场景：向量维度不匹配、数据损坏等
# ============================================

$ErrorActionPreference = "Stop"

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  Chroma 集合清理脚本" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""

# 1. 停止 Chroma 服务
Write-Host "[1/4] 停止 Chroma 服务..." -ForegroundColor Yellow
$chromaProcesses = Get-Process | Where-Object { $_.ProcessName -eq "chroma" -or $_.CommandLine -like "*chroma run*" }
if ($chromaProcesses) {
    $chromaProcesses | Stop-Process -Force
    Write-Host "✅ Chroma 服务已停止" -ForegroundColor Green
    Start-Sleep -Seconds 2
} else {
    Write-Host "⚠️  Chroma 服务未运行" -ForegroundColor Yellow
}

# 2. 删除 Chroma 数据目录
$chromaPath = "D:\WorkSpace\idea workspace\NL2Sql\chroma"
Write-Host ""
Write-Host "[2/4] 删除 Chroma 数据目录: $chromaPath" -ForegroundColor Yellow
if (Test-Path $chromaPath) {
    Remove-Item $chromaPath -Recurse -Force
    Write-Host "✅ 数据目录已删除" -ForegroundColor Green
} else {
    Write-Host "⚠️  数据目录不存在" -ForegroundColor Yellow
}

# 3. 重新创建空目录
Write-Host ""
Write-Host "[3/4] 重新创建空目录..." -ForegroundColor Yellow
New-Item -ItemType Directory -Path $chromaPath -Force | Out-Null
Write-Host "✅ 空目录已创建" -ForegroundColor Green

# 4. 启动 Chroma 服务
Write-Host ""
Write-Host "[4/4] 启动 Chroma 服务..." -ForegroundColor Yellow
$chromaEnv = "C:\Users\jinzh\chroma-env\Scripts\chroma.exe"
if (Test-Path $chromaEnv) {
    Start-Process -FilePath $chromaEnv -ArgumentList "run", "--host", "localhost", "--port", "8000" -WindowStyle Hidden
    Write-Host "✅ Chroma 服务已启动（后台运行）" -ForegroundColor Green
    Write-Host "   访问地址: http://localhost:8000" -ForegroundColor Gray
} else {
    Write-Host "❌ Chroma 可执行文件不存在: $chromaEnv" -ForegroundColor Red
    exit 1
}

Write-Host ""
Write-Host "========================================" -ForegroundColor Green
Write-Host "  ✅ 清理完成！" -ForegroundColor Green
Write-Host "========================================" -ForegroundColor Green
Write-Host ""
Write-Host "下一步操作：" -ForegroundColor Cyan
Write-Host "  1. 重启 NL2SQL 应用（自动重建 1024 维集合）" -ForegroundColor White
Write-Host "  2. 验证向量检索功能是否正常" -ForegroundColor White
Write-Host ""
