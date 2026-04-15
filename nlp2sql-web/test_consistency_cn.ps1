# 测试LLM输出一致性 - 使用正确的中文消息
$token = "41f57aa3633d4cdf8e1b537a0a987a47"  # 新获取的token
$headers = @{
    "Authorization" = "Bearer $token"
    "Content-Type" = "application/json; charset=utf-8"
}

Write-Host "`n========== 开始5次一致性测试 ==========" -ForegroundColor Cyan
Write-Host "测试问题: 统计每个地区的销售额`n" -ForegroundColor Yellow

$results = @()

for ($i = 1; $i -le 5; $i++) {
    Write-Host "`n[第 $i 次测试]" -ForegroundColor Green
    
    try {
        $startTime = Get-Date
        
        # 直接发送字符串，PowerShell会自动处理UTF-8
        $body = '{"message":"统计每个地区的销售额","userId":1,"sessionId":"consistency_test_cn"}'
        
        $result = Invoke-RestMethod -Uri "http://localhost:8080/api/agent/chat" -Method Post -Headers $headers -Body $body
        $endTime = Get-Date
        $duration = ($endTime - $startTime).TotalSeconds
        
        # 提取关键信息
        $status = if ($result.data.status) { $result.data.status } else { "unknown" }
        $datasourceId = if ($result.data.datasourceId) { $result.data.datasourceId } else { "null" }
        $hasData = if ($result.data.data) { "YES" } else { "NO" }
        $dataRows = if ($result.data.data -and $result.data.data.rows) { $result.data.data.rows.Count } else { 0 }
        $clarificationType = if ($result.data.clarificationType) { $result.data.clarificationType } else { "N/A" }
        $recommendedDsId = if ($result.data.recommendedDatasourceId) { $result.data.recommendedDatasourceId } else { "null" }
        
        Write-Host "  Status: $status" -ForegroundColor White
        Write-Host "  ClarificationType: $clarificationType" -ForegroundColor White
        Write-Host "  RecommendedDatasourceId: $recommendedDsId" -ForegroundColor White
        Write-Host "  DatasourceId: $datasourceId" -ForegroundColor White
        Write-Host "  Has Data: $hasData (Rows: $dataRows)" -ForegroundColor White
        Write-Host "  Duration: ${duration}s" -ForegroundColor White
        
        # 保存结果用于对比
        $results += [PSCustomObject]@{
            TestNumber = $i
            Status = $status
            ClarificationType = $clarificationType
            RecommendedDsId = $recommendedDsId
            FinalDatasourceId = $datasourceId
            HasData = $hasData
            DataRows = $dataRows
            Duration = [math]::Round($duration, 2)
        }
        
    } catch {
        Write-Host "  ERROR: $_" -ForegroundColor Red
        $results += [PSCustomObject]@{
            TestNumber = $i
            Status = "ERROR"
            ClarificationType = "ERROR"
            RecommendedDsId = "ERROR"
            FinalDatasourceId = "ERROR"
            HasData = "ERROR"
            DataRows = 0
            Duration = 0
        }
    }
    
    Start-Sleep -Seconds 1
}

# 总结对比
Write-Host "`n========== 测试结果对比 ==========" -ForegroundColor Cyan
$results | Format-Table -AutoSize

# 检查一致性
$allStatus = $results.Status | Select-Object -Unique
$allRecDsIds = $results.RecommendedDsId | Select-Object -Unique
$allFinalDsIds = $results.FinalDatasourceId | Select-Object -Unique
$allHasData = $results.HasData | Select-Object -Unique

Write-Host "`n========== 一致性分析 ==========" -ForegroundColor Cyan
if ($allStatus.Count -eq 1) {
    Write-Host "✓ Status一致: $($allStatus[0])" -ForegroundColor Green
} else {
    Write-Host "✗ Status不一致: $($allStatus -join ', ')" -ForegroundColor Red
}

if ($allRecDsIds.Count -eq 1) {
    Write-Host "✓ RecommendedDatasourceId一致: $($allRecDsIds[0])" -ForegroundColor Green
} else {
    Write-Host "✗ RecommendedDatasourceId不一致: $($allRecDsIds -join ', ')" -ForegroundColor Red
}

if ($allFinalDsIds.Count -eq 1) {
    Write-Host "✓ Final DatasourceId一致: $($allFinalDsIds[0])" -ForegroundColor Green
} else {
    Write-Host "✗ Final DatasourceId不一致: $($allFinalDsIds -join ', ')" -ForegroundColor Red
}

if ($allHasData.Count -eq 1) {
    Write-Host "✓ HasData一致: $($allHasData[0])" -ForegroundColor Green
} else {
    Write-Host "✗ HasData不一致: $($allHasData -join ', ')" -ForegroundColor Red
}
