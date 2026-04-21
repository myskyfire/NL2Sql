$token = "a14da0f22315452f9471e5dcd7f01206"
$roundResults = @()

# 50个测试用例
$testCases = @(
    # L1: 简单单表 (10)
    @{q="total order count"; t=@("orders"); f=@("COUNT")},
    @{q="list all users"; t=@("users"); f=@()},
    @{q="products with price over 100"; t=@("products"); f=@("WHERE")},
    @{q="order count by status"; t=@("orders"); f=@("COUNT","GROUP BY")},
    @{q="average order amount"; t=@("orders"); f=@("AVG")},
    @{q="max product price"; t=@("products"); f=@("MAX")},
    @{q="min inventory quantity"; t=@("inventory"); f=@("MIN")},
    @{q="categories list"; t=@("product_categories"); f=@()},
    @{q="returns count"; t=@("returns"); f=@("COUNT")},
    @{q="users from Beijing"; t=@("users"); f=@("WHERE")},
    
    # L2: 聚合函数 (10)
    @{q="total sales amount"; t=@("orders"); f=@("SUM")},
    @{q="daily order count"; t=@("orders"); f=@("COUNT","GROUP BY","DATE")},
    @{q="monthly sales total"; t=@("orders"); f=@("SUM","GROUP BY","MONTH")},
    @{q="user count by province"; t=@("users"); f=@("COUNT","GROUP BY")},
    @{q="products per category"; t=@("products","product_categories"); f=@("COUNT","GROUP BY")},
    @{q="average inventory by warehouse"; t=@("inventory"); f=@("AVG","GROUP BY")},
    @{q="orders with more than 5 items"; t=@("order_items"); f=@("COUNT","GROUP BY","HAVING")},
    @{q="top 5 expensive products"; t=@("products"); f=@("ORDER BY","LIMIT")},
    @{q="distinct provinces"; t=@("users"); f=@("DISTINCT")},
    @{q="return rate by product"; t=@("returns","order_items"); f=@("COUNT","GROUP BY")},
    
    # L3: 双表JOIN (10)
    @{q="orders with user names"; t=@("orders","users"); f=@("JOIN")},
    @{q="order items with product names"; t=@("order_items","products"); f=@("JOIN")},
    @{q="users from Guangdong with orders"; t=@("users","orders"); f=@("JOIN","WHERE")},
    @{q="products with category names"; t=@("products","product_categories"); f=@("JOIN")},
    @{q="inventory with product details"; t=@("inventory","products"); f=@("JOIN")},
    @{q="total sales by user"; t=@("orders","users"); f=@("SUM","GROUP BY","JOIN")},
    @{q="products never ordered"; t=@("products","order_items"); f=@("LEFT JOIN","IS NULL")},
    @{q="users without orders"; t=@("users","orders"); f=@("LEFT JOIN","IS NULL")},
    @{q="order details with products"; t=@("orders","order_items","products"); f=@("JOIN")},
    @{q="returns with order info"; t=@("returns","orders"); f=@("JOIN")},
    
    # L4: 多表复杂 (10)
    @{q="sales by category and month"; t=@("orders","order_items","products","product_categories"); f=@("SUM","GROUP BY","JOIN")},
    @{q="top 10 customers by spending"; t=@("users","orders"); f=@("SUM","GROUP BY","ORDER BY","LIMIT","JOIN")},
    @{q="products with low inventory and high sales"; t=@("products","inventory","order_items"); f=@("JOIN","WHERE","ORDER BY")},
    @{q="user order history with products"; t=@("users","orders","order_items","products"); f=@("JOIN")},
    @{q="category sales trend by month"; t=@("product_categories","products","order_items","orders"); f=@("SUM","GROUP BY","JOIN","DATE_FORMAT")},
    @{q="warehouse inventory summary"; t=@("inventory","products","product_categories"); f=@("SUM","GROUP BY","JOIN")},
    @{q="returned products with user info"; t=@("returns","orders","users","order_items","products"); f=@("JOIN")},
    @{q="province sales distribution"; t=@("users","orders","order_items"); f=@("SUM","GROUP BY","JOIN")},
    @{q="product performance by category"; t=@("product_categories","products","order_items"); f=@("SUM","COUNT","GROUP BY","JOIN")},
    @{q="customer retention analysis"; t=@("users","orders"); f=@("COUNT","GROUP BY","HAVING","JOIN")}
)

Write-Host "========== 10-Round NL2SQL Training ==========" -ForegroundColor Green
Write-Host "Total test cases: $($testCases.Count)" -ForegroundColor Cyan
Write-Host ""

for ($round = 1; $round -le 10; $round++) {
    Write-Host "`n========== Round $round/10 ==========" -ForegroundColor Yellow
    
    # 随机选择10个测试用例
    $selected = $testCases | Get-Random -Count 10
    $roundSuccess = 0
    $roundFeedback = 0
    
    for ($i = 0; $i -lt $selected.Count; $i++) {
        $tc = $selected[$i]
        Write-Host "[$($i+1)/10] $($tc.q)" -NoNewline
        
        $body = @{message=$tc.q;datasourceId=1} | ConvertTo-Json
        
        try {
            $job = Start-Job -ScriptBlock {
                param($url, $headers, $body)
                Invoke-WebRequest -Uri $url -Method POST -ContentType 'application/json' -Headers $headers -Body $body
            } -ArgumentList @('http://localhost:8080/api/agent/chat/test', @{Authorization="Bearer $token"}, $body)
            
            $completed = Wait-Job $job -Timeout 180
            
            if ($completed) {
                $response = Receive-Job $job
                Remove-Job $job
                $result = $response.Content | ConvertFrom-Json
                
                if ($result.code -eq 200 -and $result.data.sql) {
                    $actualSql = $result.data.sql.ToLower()
                    
                    # 检查关键表
                    $missingTables = @()
                    foreach ($t in $tc.t) {
                        if ($actualSql -notmatch $t) {
                            $missingTables += $t
                        }
                    }
                    
                    # 检查关键函数
                    $missingFuncs = @()
                    foreach ($f in $tc.f) {
                        if ($f -eq "JOIN" -and $actualSql -notmatch "join") {
                            $missingFuncs += $f
                        } elseif ($f -eq "COUNT" -and $actualSql -notmatch "count\(") {
                            $missingFuncs += $f
                        } elseif ($f -eq "SUM" -and $actualSql -notmatch "sum\(") {
                            $missingFuncs += $f
                        } elseif ($f -eq "GROUP BY" -and $actualSql -notmatch "group by") {
                            $missingFuncs += $f
                        } elseif ($f -eq "WHERE" -and $actualSql -notmatch "where") {
                            $missingFuncs += $f
                        }
                    }
                    
                    $errorCount = $missingTables.Count + $missingFuncs.Count
                    
                    if ($errorCount -eq 0) {
                        Write-Host " ✓" -ForegroundColor Green
                        $roundSuccess++
                    } elseif ($errorCount -ge 2) {
                        Write-Host " ✗ (Auto feedback)" -ForegroundColor Red
                        
                        # 提交1星反馈
                        $reasons = @()
                        if ($missingTables.Count -gt 0) { $reasons += "Missing tables: $($missingTables -join ',')" }
                        if ($missingFuncs.Count -gt 0) { $reasons += "Missing funcs: $($missingFuncs -join ',')" }
                        
                        $feedbackBody = @{
                            question = $tc.q
                            generatedSql = $actualSql
                            rating = 1
                            feedbackText = "Round $round auto-test: $($reasons -join '; ')"
                        } | ConvertTo-Json
                        
                        try {
                            Invoke-WebRequest -Uri 'http://localhost:8080/api/feedback/submit' `
                                -Method POST -ContentType 'application/json' `
                                -Headers @{Authorization="Bearer $token"} `
                                -Body $feedbackBody | Out-Null
                            $roundFeedback++
                        } catch {}
                    } else {
                        Write-Host " ~ (Partial)" -ForegroundColor Yellow
                    }
                } else {
                    Write-Host " ✗ (API error)" -ForegroundColor Red
                }
            } else {
                Stop-Job $job; Remove-Job $job
                Write-Host " ⏱ (Timeout)" -ForegroundColor Yellow
            }
        } catch {
            Write-Host " ✗ (Exception)" -ForegroundColor Red
        }
    }
    
    $accuracy = [math]::Round($roundSuccess / 10 * 100, 2)
    Write-Host "`nRound $round Summary: Success=$roundSuccess, Feedback=$roundFeedback, Accuracy=${accuracy}%" -ForegroundColor Cyan
    
    $roundResults += [PSCustomObject]@{
        Round = $round
        Success = $roundSuccess
        Feedback = $roundFeedback
        Accuracy = $accuracy
    }
    
    Start-Sleep -Seconds 2
}

# 最终报告
Write-Host ""
Write-Host "========== Final Training Report ==========" -ForegroundColor Green
Write-Host ""

foreach ($r in $roundResults) {
    Write-Host "Round $($r.Round): Success=$($r.Success), Feedback=$($r.Feedback), Accuracy=$($r.Accuracy)%" -ForegroundColor Cyan
}

$avgAccuracy = [math]::Round(($roundResults | Measure-Object -Property Accuracy -Average).Average, 2)
$totalFeedback = ($roundResults | Measure-Object -Property Feedback -Sum).Sum
Write-Host ""
Write-Host "Average Accuracy: ${avgAccuracy}%" -ForegroundColor Green
Write-Host "Total Feedback Submitted: $totalFeedback" -ForegroundColor Green
Write-Host "=========================================" -ForegroundColor Green
