# Login and get Token
$loginBody = @{
    username = "admin"
    password = "admin123"
} | ConvertTo-Json

Write-Host "Logging in..." -ForegroundColor Cyan
$loginResult = Invoke-RestMethod -Uri "http://localhost:8080/api/auth/login" -Method Post -ContentType "application/json; charset=utf-8" -Body $loginBody
$token = $loginResult.data.token
Write-Host "Login successful! Token: $($token.Substring(0, 20))..." -ForegroundColor Green

# Test summarize-result Skill
$headers = @{
    "Content-Type" = "application/json; charset=utf-8"
    "Authorization" = "Bearer $token"
}

$body = @{
    sessionId = "test-summarize-$(Get-Date -Format 'yyyyMMddHHmmss')"
    message = "总结一下最近半个月的订单趋势"
    datasourceId = 1
} | ConvertTo-Json -Depth 10

Write-Host "Sending test request" -ForegroundColor Cyan
Write-Host "Waiting for response" -ForegroundColor Yellow

try {
    $result = Invoke-RestMethod -Uri "http://localhost:8080/api/agent/chat" -Method Post -Headers $headers -Body ([System.Text.Encoding]::UTF8.GetBytes($body)) -TimeoutSec 300
    Write-Host "Request successful" -ForegroundColor Green
    Write-Host "Response:" -ForegroundColor Cyan
    $result | ConvertTo-Json -Depth 10
} catch {
    Write-Host "Request failed" -ForegroundColor Red
}
