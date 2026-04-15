# 测试LLM输出一致性
$token = "3f5df2457376447ba216c89ceccf3b9e"
$headers = @{
    "Authorization" = "Bearer $token"
    "Content-Type" = "application/json"
}

Write-Host "`n========== 开始5次一致性测试 ==========" -ForegroundColor Cyan
Write-Host "测试问题: 统计每个地区的销售额`n" -ForegroundColor Yellow

$results = @()

for ($i = 1; $i -le 5; $i++) {
    Write-Host "`n[第 $i 次测试]" -ForegroundColor Green
    
    try {
        $startTime = Get-Date
        $result = Invoke-RestMethod -Uri "http://localhost:8080/api/agent/chat" -Method Post -Headers $headers -Body '{"message":"统计每个地区的销售额","userId":1,"sessionId":"consistency_test"}'
        $endTime = Get-Date
        $duration = ($endTime - $startTime).TotalSeconds
        
        # 提取关键信息
        $status = if ($result.data.status) { $result.data.status } else { "unknown" }
        $datasourceId = if ($result.data.datasourceId) { $result.data.datasourceId } else { "null" }
        $hasData = if ($result.data.data) { "YES" } else { "NO" }
        $dataRows = if ($result.data.data -and $result.data.data.rows) { $result.data.data.rows.Count } else { 0 }
        
        Write-Host "  Status: $status" -ForegroundColor White
        Write-Host "  DatasourceId: $datasourceId" -ForegroundColor White
        Write-Host "  Has Data: $hasData" -ForegroundColor White
        Write-Host "  Data Rows: $dataRows" -ForegroundColor White
        Write-Host "  Duration: ${duration}s" -ForegroundColor White
        
        # 保存结果用于对比
        $results += [PSCustomObject]@{
            TestNumber = $i
            Status = $status
            DatasourceId = $datasourceId
            HasData = $hasData
            DataRows = $dataRows
            Duration = $duration
        }
        
    } catch {
        Write-Host "  ERROR: $_" -ForegroundColor Red
        $results += [PSCustomObject]@{
            TestNumber = $i
            Status = "ERROR"
            DatasourceId = "ERROR"
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
$allDatasourceIds = $results.DatasourceId | Select-Object -Unique
$allHasData = $results.HasData | Select-Object -Unique

Write-Host "`n========== 一致性分析 ==========" -ForegroundColor Cyan
if ($allStatus.Count -eq 1) {
    Write-Host "✓ Status一致: $($allStatus[0])" -ForegroundColor Green
} else {
    Write-Host "✗ Status不一致: $($allStatus -join ', ')" -ForegroundColor Red
}

if ($allDatasourceIds.Count -eq 1) {
    Write-Host "✓ DatasourceId一致: $($allDatasourceIds[0])" -ForegroundColor Green
} else {
    Write-Host "✗ DatasourceId不一致: $($allDatasourceIds -join ', ')" -ForegroundColor Red
}

if ($allHasData.Count -eq 1) {
    Write-Host "✓ HasData一致: $($allHasData[0])" -ForegroundColor Green
} else {
    Write-Host "✗ HasData不一致: $($allHasData -join ', ')" -ForegroundColor Red
}
