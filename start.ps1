chcp 65001 | Out-Null

$env:JAVA_HOME = "D:\Program Files\Java\jdk-21.0.6"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
$env:M2_HOME = $null

cd "D:\WorkSpace\idea workspace\NL2Sql"
Write-Host "Compiling with JDK 21..." -ForegroundColor Green
& "$env:JAVA_HOME\bin\java" -version
& mvn package -DskipTests

if ($LASTEXITCODE -ne 0) {
    Write-Host "Build failed!" -ForegroundColor Red
    exit 1
}

Write-Host "Build success, starting application..." -ForegroundColor Green

cd "D:\WorkSpace\idea workspace\NL2Sql\nl2sql-web"
$javaPath = Join-Path $env:JAVA_HOME "bin\java.exe"
$jarPath = "target\nl2sql-web-1.0.0.jar"

& $javaPath '-Dfile.encoding=UTF-8' '-Dhttp.proxyHost=127.0.0.1' '-Dhttp.proxyPort=7890' '-Dhttps.proxyHost=127.0.0.1' '-Dhttps.proxyPort=7890' -jar $jarPath
