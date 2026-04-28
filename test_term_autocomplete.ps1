# 术语自动补全功能测试脚本
# 使用方法：在浏览器Console中执行以下代码

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "术语自动补全功能测试" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""

$testCases = @(
    @{input="最近7天订单"; expected="最近7天订单量"; selectTerm="订单量"},
    @{input="最近7天的订单"; expected="最近7天的订单总额"; selectTerm="订单总额"},
    @{input="查看用户信息"; expected="查看用户信息详情"; selectTerm="信息详情"},
    @{input="北京地区销售额"; expected="北京地区销售额统计"; selectTerm="销售额统计"},
    @{input="订单"; expected="订单列表"; selectTerm="订单列表"},
    @{input="2024年Q1"; expected="2024年Q1报表"; selectTerm="Q1报表"}
)

Write-Host "请在浏览器Console中执行以下JavaScript代码：" -ForegroundColor Yellow
Write-Host ""

foreach ($testCase in $testCases) {
    Write-Host "// 测试用例: $($testCase.input)" -ForegroundColor Gray
    Write-Host "console.log('🧪 测试: $($testCase.input)');" -ForegroundColor White
    Write-Host "document.querySelector('#termInput').value = '$($testCase.input)';" -ForegroundColor White
    Write-Host "document.querySelector('#termInput').dispatchEvent(new Event('input'));" -ForegroundColor White
    Write-Host "setTimeout(() => {" -ForegroundColor White
    Write-Host "  const items = document.querySelectorAll('.autocomplete-item');" -ForegroundColor White
    Write-Host "  if (items.length > 0) {" -ForegroundColor White
    Write-Host "    items[0].click();" -ForegroundColor White
    Write-Host "    setTimeout(() => {" -ForegroundColor White
    Write-Host "      const result = document.querySelector('#termInput').value;" -ForegroundColor White
    Write-Host "      console.log('✅ 结果:', result);" -ForegroundColor White
    Write-Host "      console.log('🎯 预期:', '$($testCase.expected)');" -ForegroundColor White
    Write-Host "      console.log(result === '$($testCase.expected)' ? '✔️ PASS' : '❌ FAIL');" -ForegroundColor White
    Write-Host "    }, 100);" -ForegroundColor White
    Write-Host "  } else {" -ForegroundColor White
    Write-Host "    console.log('⚠️ 无匹配项');" -ForegroundColor White
    Write-Host "  }" -ForegroundColor White
    Write-Host "}, 500);" -ForegroundColor White
    Write-Host ""
}

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "测试步骤：" -ForegroundColor Yellow
Write-Host "1. 访问 http://localhost:8080/term-autocomplete-demo.html" -ForegroundColor White
Write-Host "2. 打开浏览器Console（F12）" -ForegroundColor White
Write-Host "3. 复制上面的代码逐条执行" -ForegroundColor White
Write-Host "4. 观察输出结果是否为 ✔️ PASS" -ForegroundColor White
Write-Host "========================================" -ForegroundColor Cyan
