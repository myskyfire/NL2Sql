# 编译测试脚本
Write-Host "开始编译项目..." -ForegroundColor Cyan
cd "E:\work\idea workspace\NL2Sql"

# 清理并编译
mvn clean compile -DskipTests 2>&1 | Tee-Object -Variable output

# 检查编译结果
if ($LASTEXITCODE -eq 0) {
    Write-Host "`n✅ 编译成功！" -ForegroundColor Green
    
    # 显示编译的类文件
    $llmProviderExists = Test-Path "nl2sql-core\target\classes\com\nl2sql\core\llm\provider\LLMProvider.class"
    $ollamaProviderExists = Test-Path "nl2sql-core\target\classes\com\nl2sql\core\llm\provider\OllamaProvider.class"
    $openAIProviderExists = Test-Path "nl2sql-core\target\classes\com\nl2sql\core\llm\provider\OpenAICompatibleProvider.class"
    
    Write-Host "`n编译的类文件:" -ForegroundColor Yellow
    Write-Host "  LLMProvider: $(if($llmProviderExists){'✅'}else{'❌'})"
    Write-Host "  OllamaProvider: $(if($ollamaProviderExists){'✅'}else{'❌'})"
    Write-Host "  OpenAICompatibleProvider: $(if($openAIProviderExists){'✅'}else{'❌'})"
} else {
    Write-Host "`n❌ 编译失败！错误信息:" -ForegroundColor Red
    $output | Select-String -Pattern "ERROR" | ForEach-Object { Write-Host $_ }
}
