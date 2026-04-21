$token = "a14da0f22315452f9471e5dcd7f01206"
$results = @()

$testCases = @(
    @{id=1; question="total order count"; expected="SELECT COUNT(*) FROM orders"},
    @{id=2; question="order count by status"; expected="SELECT status, COUNT(*) FROM orders GROUP BY status"},
    @{id=3; question="total amount for Guangdong users"; expected="SELECT SUM(actual_amount) FROM orders JOIN users WHERE province = Guangdong"},
    @{id=4; question="top 5 products by sales"; expected="SELECT product_name, SUM(subtotal) FROM order_items JOIN products GROUP BY product_name ORDER BY SUM DESC LIMIT 5"},
    @{id=5; question="user count by province"; expected="SELECT province, COUNT(*) FROM users GROUP BY province"},
    @{id=6; question="10 products with lowest inventory"; expected="SELECT product_name, available_quantity FROM inventory JOIN products ORDER BY available_quantity ASC LIMIT 10"},
    @{id=7; question="all orders for user Zhang San"; expected="SELECT order_no, total_amount, status FROM orders JOIN users WHERE real_name = Zhang San"},
    @{id=8; question="products with return records"; expected="SELECT product_name, COUNT(returns.id) FROM returns JOIN orders JOIN order_items JOIN products GROUP BY product_name"},
    @{id=9; question="total sales by category"; expected="SELECT category_name, SUM(subtotal) FROM order_items JOIN products JOIN product_categories GROUP BY category_name"},
    @{id=10; question="daily order count last 7 days"; expected="SELECT DATE(created_at), COUNT(*) FROM orders WHERE created_at >= DATE_SUB CURDATE INTERVAL 7 DAY GROUP BY DATE"}
)

Write-Host "========== Start NL2SQL Test ==========" -ForegroundColor Green

foreach ($tc in $testCases) {
    Write-Host "[$($tc.id)/10] Testing: $($tc.question)" -ForegroundColor Cyan
    
    $body = @{message=$tc.question;datasourceId=1} | ConvertTo-Json
    
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
                $actualSql = $result.data.sql
                
                # Strict consistency check
                $expectedLower = $tc.expected.ToLower()
                $actualLower = $actualSql.ToLower()
                
                $isMatch = "No"
                $reasons = @()
                
                # Check key tables
                $hasKeyTables = $true
                if ($expectedLower -match "orders") { 
                    if ($actualLower -notmatch "orders") { $hasKeyTables = $false; $reasons += "Missing orders table" }
                }
                if ($expectedLower -match "users") { 
                    if ($actualLower -notmatch "users") { $hasKeyTables = $false; $reasons += "Missing users table" }
                }
                if ($expectedLower -match "products") { 
                    if ($actualLower -notmatch "products") { $hasKeyTables = $false; $reasons += "Missing products table" }
                }
                
                # Check unnecessary GROUP BY
                if ($expectedLower -match "count\(\*\)" -and $expectedLower -notmatch "group by") {
                    if ($actualLower -match "group by") {
                        $reasons += "Expected simple COUNT but has GROUP BY"
                    }
                }
                
                # Check unnecessary LIMIT
                if ($expectedLower -notmatch "limit") {
                    if ($actualLower -match "limit") {
                        $reasons += "Expected no LIMIT but has limit"
                    }
                }
                
                # Check key fields
                if ($expectedLower -match "subtotal" -and $actualLower -notmatch "subtotal") {
                    if ($actualLower -match "quantity") {
                        $reasons += "Expected subtotal but uses quantity"
                    }
                }
                
                if ($expectedLower -match "actual_amount" -and $actualLower -notmatch "actual_amount") {
                    $reasons += "Expected actual_amount not found"
                }
                
                # Check extra JOINs
                $expectedJoinCount = ([regex]::Matches($expectedLower, "join")).Count
                $actualJoinCount = ([regex]::Matches($actualLower, "join")).Count
                if ($expectedJoinCount -eq 0 -and $actualJoinCount -gt 2) {
                    $reasons += "Expected simple query but has multiple JOINs"
                }
                
                # Final judgment
                if ($hasKeyTables -and $reasons.Count -eq 0) {
                    $isMatch = "Yes"
                } elseif ($hasKeyTables -and $reasons.Count -le 1) {
                    $isMatch = "Partial: $($reasons -join '; ')"
                } else {
                    $isMatch = "No: $($reasons -join '; ')"
                }
                
                $results += [PSCustomObject]@{
                    ID = $tc.id
                    Question = $tc.question
                    ExpectedSQL = $tc.expected
                    ActualSQL = $actualSql
                    Match = $isMatch
                }
                
                Write-Host "  OK ($isMatch)" -ForegroundColor $(if ($isMatch -eq "Yes") { "Green" } else { "Yellow" })
                
                # Auto submit feedback for bad SQL (rating=1)
                if ($isMatch -like "No:*") {
                    try {
                        $feedbackBody = @{
                            question = $tc.question
                            generatedSql = $actualSql
                            rating = 1
                            feedbackText = "Auto-test feedback: $($reasons -join '; ')"
                        } | ConvertTo-Json
                        
                        $fbResponse = Invoke-WebRequest -Uri 'http://localhost:8080/api/feedback/submit' `
                            -Method POST `
                            -ContentType 'application/json' `
                            -Headers @{Authorization="Bearer $token"} `
                            -Body $feedbackBody
                        
                        Write-Host "    -> Feedback submitted (1 star)" -ForegroundColor Cyan
                    } catch {
                        Write-Host "    -> Feedback submit failed: $_" -ForegroundColor Red
                    }
                }
            } else {
                $results += [PSCustomObject]@{
                    ID = $tc.id
                    Question = $tc.question
                    ExpectedSQL = $tc.expected
                    ActualSQL = "Error: $($result.message)"
                    Match = "Failed"
                }
                Write-Host "  Error: $($result.message)" -ForegroundColor Red
            }
        } else {
            Stop-Job $job
            Remove-Job $job
            
            $results += [PSCustomObject]@{
                ID = $tc.id
                Question = $tc.question
                ExpectedSQL = $tc.expected
                ActualSQL = "Timeout (>180s)"
                Match = "Timeout"
            }
            Write-Host "  Timeout" -ForegroundColor Yellow
        }
        
    } catch {
        $results += [PSCustomObject]@{
            ID = $tc.id
            Question = $tc.question
            ExpectedSQL = $tc.expected
            ActualSQL = "Exception: $_"
            Match = "Exception"
        }
        Write-Host "  Exception: $_" -ForegroundColor Red
    }
    
    Write-Host ""
}

$outputFile = "D:\WorkSpace\idea workspace\NL2Sql\NL2SQL_TEST_RESULTS.md"
$content = "# NL2SQL Test Results`n`n"
$content += "**Time**: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')`n"
$content += "**Datasource**: Docker Trade DB (ID=1)`n"
$content += "**Timeout**: 180s`n`n"
$content += "| ID | Question | Expected SQL | Actual SQL | Match |`n"
$content += "|----|----------|--------------|------------|-------|`n"

foreach ($r in $results) {
    $content += "| $($r.ID) | $($r.Question) | ``````$($r.ExpectedSQL)`````` | ``````$($r.ActualSQL)`````` | $($r.Match) |`n"
}

$totalCount = $results.Count
$successCount = ($results | Where-Object { $_.Match -eq "Yes" }).Count
$partialCount = ($results | Where-Object { $_.Match -like "Partial*" }).Count
$timeoutCount = ($results | Where-Object { $_.Match -eq "Timeout" }).Count
$failCount = $totalCount - $successCount - $partialCount - $timeoutCount

$content += "`n## Summary`n`n"
$content += "- **Total**: $totalCount`n"
$content += "- **Fully Match**: $successCount`n"
$content += "- **Partial Match**: $partialCount`n"
$content += "- **Timeout**: $timeoutCount`n"
$content += "- **Failed**: $failCount`n"
$content += "- **Success Rate**: $([math]::Round($successCount/$totalCount*100, 2))%`n"

$content | Out-File -FilePath $outputFile -Encoding utf8

Write-Host "`n========== Test Complete ==========" -ForegroundColor Green
Write-Host "Results saved to: $outputFile" -ForegroundColor Green
Write-Host ""

$results | Format-Table -AutoSize -Wrap
