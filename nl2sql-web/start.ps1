# NL2SQL Application Startup Script

[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8

if (-not (Test-Path "logs")) {
    New-Item -ItemType Directory -Path "logs" | Out-Null
}

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  NL2SQL Application Startup" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""

$PROXY_HOST = "127.0.0.1"
$PROXY_PORT = "7890"
$JAVA_HOME = "D:\Program Files\Java\jdk-21.0.6"

if (-not (Test-Path "$JAVA_HOME\bin\java.exe")) {
    Write-Host "Error: JDK not found: $JAVA_HOME" -ForegroundColor Red
    exit 1
}

Write-Host "[1/3] Stopping running processes..." -ForegroundColor Cyan
$javaProcesses = Get-Process -Name java -ErrorAction SilentlyContinue | Where-Object {
    (Get-WmiObject Win32_Process -Filter "ProcessId = $($_.Id)" -ErrorAction SilentlyContinue).CommandLine -like '*nl2sql-web*'
}

if ($javaProcesses) {
    Write-Host "  Found $($javaProcesses.Count) process(es)" -ForegroundColor Yellow
    foreach ($proc in $javaProcesses) {
        Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue
    }
    Start-Sleep -Seconds 2
    Write-Host "  Processes stopped" -ForegroundColor Green
} else {
    Write-Host "  No running processes" -ForegroundColor Green
}

Write-Host ""
Write-Host "[2/3] Checking port 8080..." -ForegroundColor Cyan
$portInUse = Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue
if ($portInUse) {
    Write-Host "  Port 8080 in use, waiting..." -ForegroundColor Yellow
    Start-Sleep -Seconds 2
}
Write-Host "  Port 8080 available" -ForegroundColor Green
Write-Host ""

Write-Host "[3/3] Starting application..." -ForegroundColor Cyan
Write-Host ""

$javaArgs = @(
    "-Dhttp.proxyHost=$PROXY_HOST",
    "-Dhttp.proxyPort=$PROXY_PORT",
    "-Dhttps.proxyHost=$PROXY_HOST",
    "-Dhttps.proxyPort=$PROXY_PORT",
    "-Djava.net.useSystemProxies=true",
    "-jar",
    "target\nl2sql-web-1.0.0.jar"
)

& "$JAVA_HOME\bin\java.exe" $javaArgs
