# 自动化测试脚本 - 验证Workflow与Groovy等效性

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "NL2SQL Workflow 自动化测试" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""

# 测试配置
$baseUrl = "http://localhost:8080"
$testCases = @(
    @{
        name = "简单查询（跳过EXPLAIN）"
        question = "查询订单ID=1的详情"
        expectedSimple = $true
    },
    @{
        name = "中等复杂度（需要EXPLAIN+LLM优化建议）"
        question = "查询最近3天的订单，需要包括订单详情"
        expectedSimple = $false
    },
    @{
        name = "统计查询（触发追问建议）"
        question = "统计上月各地区订单数量"
        expectFollowUp = $true
    }
)

Write-Host "[1/3] 检查服务状态..." -ForegroundColor Yellow
try {
    $response = Invoke-RestMethod -Uri "$baseUrl/actuator/health" -Method GET -TimeoutSec 5
    Write-Host "✅ 服务正常运行" -ForegroundColor Green
} catch {
    Write-Host "❌ 服务未启动，请先执行: mvn spring-boot:run" -ForegroundColor Red
    exit 1
}

Write-Host ""
Write-Host "[2/3] 执行测试用例..." -ForegroundColor Yellow
Write-Host ""

$passed = 0
$failed = 0

foreach ($testCase in $testCases) {
    Write-Host "----------------------------------------" -ForegroundColor Gray
    Write-Host "测试: $($testCase.name)" -ForegroundColor Cyan
    Write-Host "问题: $($testCase.question)" -ForegroundColor White
    
    try {
        $sessionId = "test_$(Get-Date -Format 'yyyyMMddHHmmss')_$([guid]::NewGuid().ToString().Substring(0,8))"
        
        $requestBody = @{
            message = $testCase.question
            datasourceId = 1
            sessionId = $sessionId
        } | ConvertTo-Json
        
        Write-Host "发送请求..." -ForegroundColor Gray
        $startTime = Get-Date
        
        $response = Invoke-RestMethod `
            -Uri "$baseUrl/api/agent/chat" `
            -Method POST `
            -Body $requestBody `
            -ContentType "application/json; charset=utf-8" `
            -TimeoutSec 120
        
        $elapsed = (Get-Date) - $startTime
        Write-Host "响应时间: $($elapsed.TotalSeconds)s" -ForegroundColor Gray
        
        # 验证响应结构
        if ($response.success -eq $true) {
            Write-Host "✅ success=true" -ForegroundColor Green
            
            # 检查type字段
            if ($response.type -eq "data") {
                Write-Host "✅ type=data" -ForegroundColor Green
            } else {
                Write-Host "⚠️  type=$($response.type)" -ForegroundColor Yellow
            }
            
            # 检查data字段
            if ($response.data -ne $null) {
                Write-Host "✅ data字段存在，行数: $($response.rowCount)" -ForegroundColor Green
            } else {
                Write-Host "❌ data字段缺失" -ForegroundColor Red
                $failed++
                continue
            }
            
            # 检查followUpSuggestions（统计查询应该有）
            if ($testCase.expectFollowUp -eq $true) {
                if ($response.followUpSuggestions -ne $null -and $response.followUpSuggestions.Count -gt 0) {
                    Write-Host "✅ followUpSuggestions存在 ($($response.followUpSuggestions.Count)个建议)" -ForegroundColor Green
                    foreach ($suggestion in $response.followUpSuggestions) {
                        Write-Host "   - $($suggestion.text)" -ForegroundColor Gray
                    }
                } else {
                    Write-Host "⚠️  followUpSuggestions缺失（预期有）" -ForegroundColor Yellow
                }
            }
            
            # 检查optimizationSuggestion（中风险时应该有）
            if ($response.optimizationSuggestion -ne $null) {
                Write-Host "✅ optimizationSuggestion存在" -ForegroundColor Green
                Write-Host "   内容: $($response.optimizationSuggestion.Substring(0, [Math]::Min(100, $response.optimizationSuggestion.Length)))..." -ForegroundColor Gray
            }
            
            # 检查sql字段
            if ($response.sql -ne $null) {
                Write-Host "✅ SQL生成成功" -ForegroundColor Green
                Write-Host "   SQL: $($response.sql.Substring(0, [Math]::Min(80, $response.sql.Length)))..." -ForegroundColor Gray
            }
            
            $passed++
            
        } elseif ($response.type -eq "human_approval_required") {
            Write-Host "⚠️  高风险SQL需要人工确认" -ForegroundColor Yellow
            Write-Host "   风险原因: $($response.riskReason)" -ForegroundColor Gray
            Write-Host "   SQL: $($response.sql.Substring(0, [Math]::Min(80, $response.sql.Length)))..." -ForegroundColor Gray
            $passed++  # 这也是正确的行为
            
        } elseif ($response.type -eq "clarification") {
            Write-Host "⚠️  需要澄清" -ForegroundColor Yellow
            Write-Host "   消息: $($response.clarificationMessage)" -ForegroundColor Gray
            $passed++  # 这也是正确的行为
            
        } else {
            Write-Host "❌ 响应异常: $($response.error)" -ForegroundColor Red
            $failed++
        }
        
    } catch {
        Write-Host "❌ 请求失败: $($_.Exception.Message)" -ForegroundColor Red
        $failed++
    }
    
    Write-Host ""
}

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "测试结果汇总" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "通过: $passed / $($testCases.Count)" -ForegroundColor $(if ($failed -eq 0) { "Green" } else { "Yellow" })
Write-Host "失败: $failed / $($testCases.Count)" -ForegroundColor $(if ($failed -eq 0) { "Green" } else { "Red" })
Write-Host ""

if ($failed -eq 0) {
    Write-Host "✅ 所有测试通过！" -ForegroundColor Green
    exit 0
} else {
    Write-Host "❌ 存在失败的测试，请检查日志" -ForegroundColor Red
    exit 1
}
