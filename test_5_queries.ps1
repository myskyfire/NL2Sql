$token = '7876a38a89ee424bb8239b8dca0de724'
$headers = @{
    'Content-Type'='application/json; charset=utf-8'
    'Authorization'="Bearer $token"
}
$baseUrl = 'http://localhost:8080/api/agent/chat'

Write-Host "`n=== Test 1: Simple Query ===" -ForegroundColor Cyan
$body1 = @{message='query recent 10 orders'; sessionId='test-simple'; datasourceId=1} | ConvertTo-Json
try {
    $resp1 = Invoke-RestMethod -Uri $baseUrl -Method POST -Headers $headers -Body ([System.Text.Encoding]::UTF8.GetBytes($body1)) -TimeoutSec 120
    Write-Host "SUCCESS: code=$($resp1.code), type=$($resp1.data.type), rows=$($resp1.data.rowCount)" -ForegroundColor Green
    Write-Host "SQL: $($resp1.data.sql)"
} catch {
    Write-Host "FAILED: $_" -ForegroundColor Red
}

Start-Sleep -Seconds 2

Write-Host "`n=== Test 2: Aggregate Query ===" -ForegroundColor Cyan
$body2 = @{message='count orders by status'; sessionId='test-aggregate'; datasourceId=1} | ConvertTo-Json
try {
    $resp2 = Invoke-RestMethod -Uri $baseUrl -Method POST -Headers $headers -Body ([System.Text.Encoding]::UTF8.GetBytes($body2)) -TimeoutSec 120
    Write-Host "SUCCESS: code=$($resp2.code), type=$($resp2.data.type), rows=$($resp2.data.rowCount)" -ForegroundColor Green
    Write-Host "SQL: $($resp2.data.sql)"
} catch {
    Write-Host "FAILED: $_" -ForegroundColor Red
}

Start-Sleep -Seconds 2

Write-Host "`n=== Test 3: JOIN Query ===" -ForegroundColor Cyan
$body3 = @{message='query orders with user name and phone'; sessionId='test-join'; datasourceId=1} | ConvertTo-Json
try {
    $resp3 = Invoke-RestMethod -Uri $baseUrl -Method POST -Headers $headers -Body ([System.Text.Encoding]::UTF8.GetBytes($body3)) -TimeoutSec 120
    Write-Host "SUCCESS: code=$($resp3.code), type=$($resp3.data.type), rows=$($resp3.data.rowCount)" -ForegroundColor Green
    Write-Host "SQL: $($resp3.data.sql)"
} catch {
    Write-Host "FAILED: $_" -ForegroundColor Red
}

Start-Sleep -Seconds 2

Write-Host "`n=== Test 4: Query + AI Summary ===" -ForegroundColor Cyan
$body4 = @{message='summarize order trend in last week'; sessionId='test-summary'; datasourceId=1} | ConvertTo-Json
try {
    $resp4 = Invoke-RestMethod -Uri $baseUrl -Method POST -Headers $headers -Body ([System.Text.Encoding]::UTF8.GetBytes($body4)) -TimeoutSec 180
    Write-Host "SUCCESS: code=$($resp4.code), type=$($resp4.data.type)" -ForegroundColor Green
    if ($resp4.data.data.raw) {
        Write-Host "Summary: $($resp4.data.data.raw)"
    }
} catch {
    Write-Host "FAILED: $_" -ForegroundColor Red
}

Start-Sleep -Seconds 2

Write-Host "`n=== Test 5: Query + Chart ===" -ForegroundColor Cyan
$body5 = @{message='generate line chart for daily order count in last 7 days'; sessionId='test-chart'; datasourceId=1} | ConvertTo-Json
try {
    $resp5 = Invoke-RestMethod -Uri $baseUrl -Method POST -Headers $headers -Body ([System.Text.Encoding]::UTF8.GetBytes($body5)) -TimeoutSec 180
    Write-Host "SUCCESS: code=$($resp5.code), type=$($resp5.data.type)" -ForegroundColor Green
    if ($resp5.data.chartConfig) {
        Write-Host "Chart: Generated"
    }
    Write-Host "SQL: $($resp5.data.sql)"
} catch {
    Write-Host "FAILED: $_" -ForegroundColor Red
}

Write-Host "`nAll tests completed!" -ForegroundColor Yellow
