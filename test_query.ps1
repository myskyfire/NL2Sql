$headers = @{
    "Content-Type" = "application/json"
    "Authorization" = "Bearer test-token"
}

$body = @{
    message = "查询最近10天每天的订单金额"
    sessionId = "test_001"
    datasourceId = 1
} | ConvertTo-Json -Compress

Write-Host "发送请求..."
Write-Host "Body: $body"

try {
    $response = Invoke-RestMethod -Uri "http://localhost:8080/api/agent/chat" -Method POST -Headers $headers -Body $body
    Write-Host "响应:" -ForegroundColor Green
    $response | ConvertTo-Json -Depth 10
} catch {
    Write-Host "错误: $($_.Exception.Message)" -ForegroundColor Red
    if ($_.ErrorDetails) {
        Write-Host "详情: $($_.ErrorDetails.Message)" -ForegroundColor Yellow
    }
}
