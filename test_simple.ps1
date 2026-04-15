Write-Host "=== 开始自测 ===" -ForegroundColor Cyan

$loginBody = @{username="admin";password="admin123"} | ConvertTo-Json -Compress
$loginResult = Invoke-RestMethod -Uri "http://localhost:8080/api/auth/login" -Method POST -Headers @{"Content-Type"="application/json"} -Body $loginBody
$token = $loginResult.data.token
Write-Host "Token: $token" -ForegroundColor Green

$headers = @{"Content-Type"="application/json"; "Authorization"="Bearer $token"}
$body = @{message="查询最近10天每天的订单金额";sessionId="test_self_001";datasourceId=1} | ConvertTo-Json -Compress

Write-Host "发送请求..." -ForegroundColor Yellow
$result = Invoke-RestMethod -Uri "http://localhost:8080/api/agent/chat" -Method POST -Headers $headers -Body $body

Write-Host "响应 Code: $($result.code)" -ForegroundColor Cyan
if ($result.code -eq 200) {
    $data = $result.data
    if ($data.success -eq $true) {
        Write-Host "SUCCESS! RowCount: $($data.rowCount)" -ForegroundColor Green
        Write-Host "SQL: $($data.sql)" -ForegroundColor Gray
    } else {
        Write-Host "FAILED: $($data.error)" -ForegroundColor Red
    }
} else {
    Write-Host "ERROR: $($result.message)" -ForegroundColor Red
}
