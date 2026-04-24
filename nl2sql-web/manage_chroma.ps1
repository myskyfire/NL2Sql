param(
    [Parameter(Position=0)]
    [ValidateSet("start", "stop", "restart", "clear", "status")]
    [string]$Action = "status"
)

$CHROMA_VENV = "C:\Users\jinzh\chroma-env"
$CHROMA_PYTHON = "$CHROMA_VENV\Scripts\python.exe"
$CHROMA_HOST = "localhost"
$CHROMA_PORT = "8000"

function Test-ChromaRunning {
    $processes = Get-Process -Name python -ErrorAction SilentlyContinue | Where-Object {
        (Get-WmiObject Win32_Process -Filter "ProcessId = $($_.Id)" -ErrorAction SilentlyContinue).CommandLine -like '*chromadb*'
    }
    return $processes
}

function Start-Chroma {
    Write-Host ""
    Write-Host "========================================" -ForegroundColor Cyan
    Write-Host "  Starting Chroma Database" -ForegroundColor Cyan
    Write-Host "========================================" -ForegroundColor Cyan
    
    $running = Test-ChromaRunning
    if ($running) {
        Write-Host "Warning: Chroma is already running (PID: $($running.Id -join ', '))" -ForegroundColor Yellow
        Write-Host "To restart, run: .\manage_chroma.ps1 stop" -ForegroundColor Gray
        return
    }
    
    if (-not (Test-Path $CHROMA_PYTHON)) {
        Write-Host "Error: Chroma virtual environment not found: $CHROMA_VENV" -ForegroundColor Red
        exit 1
    }
    
    Write-Host "Using venv: $CHROMA_VENV" -ForegroundColor Green
    Write-Host "Listening on: http://$CHROMA_HOST`:$CHROMA_PORT" -ForegroundColor Green
    Write-Host ""
    Write-Host "Starting Chroma..." -ForegroundColor Cyan
    
    Start-Process -FilePath $CHROMA_PYTHON `
                  -ArgumentList "-m", "chromadb.cli.cli", "run", "--host", $CHROMA_HOST, "--port", $CHROMA_PORT `
                  -WindowStyle Normal `
                  -WorkingDirectory (Get-Location)
    
    Write-Host ""
    Write-Host "Chroma starting..." -ForegroundColor Green
    Write-Host "Wait 5-10 seconds for full startup" -ForegroundColor Gray
    Write-Host "URL: http://$CHROMA_HOST`:$CHROMA_PORT" -ForegroundColor Cyan
    Write-Host ""
}

function Stop-Chroma {
    Write-Host ""
    Write-Host "========================================" -ForegroundColor Cyan
    Write-Host "  Stopping Chroma Database" -ForegroundColor Cyan
    Write-Host "========================================" -ForegroundColor Cyan
    
    $processes = Test-ChromaRunning
    
    if (-not $processes) {
        Write-Host "Info: Chroma is not running" -ForegroundColor Gray
        return
    }
    
    Write-Host "Found $($processes.Count) Chroma process(es):" -ForegroundColor Yellow
    foreach ($proc in $processes) {
        Write-Host "  - PID: $($proc.Id), Started: $($proc.StartTime)" -ForegroundColor Gray
    }
    
    Write-Host ""
    Write-Host "Stopping processes..." -ForegroundColor Cyan
    
    foreach ($proc in $processes) {
        Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue
        Write-Host "  Stopped PID: $($proc.Id)" -ForegroundColor Green
    }
    
    Start-Sleep -Seconds 2
    
    $remaining = Test-ChromaRunning
    if ($remaining) {
        Write-Host "Warning: Some processes still running, force killing..." -ForegroundColor Yellow
        $remaining | Stop-Process -Force
        Start-Sleep -Seconds 1
    }
    
    Write-Host ""
    Write-Host "Chroma stopped" -ForegroundColor Green
    Write-Host ""
}

function Clear-ChromaData {
    Write-Host ""
    Write-Host "========================================" -ForegroundColor Red
    Write-Host "  WARNING: Clear Chroma Data" -ForegroundColor Red
    Write-Host "========================================" -ForegroundColor Red
    Write-Host ""
    Write-Host "This will delete:" -ForegroundColor Yellow
    Write-Host "  - chroma.sqlite3 (metadata)" -ForegroundColor Gray
    Write-Host "  - All collection vector data" -ForegroundColor Gray
    Write-Host ""
    
    $confirm = Read-Host "Confirm? (type YES to continue)"
    if ($confirm -ne "YES") {
        Write-Host "Cancelled" -ForegroundColor Gray
        return
    }
    
    Write-Host ""
    Write-Host "[1/2] Stopping Chroma..." -ForegroundColor Cyan
    Stop-Chroma
    
    Write-Host ""
    Write-Host "[2/2] Deleting data files..." -ForegroundColor Cyan
    
    $dataDirs = @("chroma", "chroma_data", "./chroma")
    $deletedCount = 0
    
    foreach ($dir in $dataDirs) {
        if (Test-Path $dir) {
            Write-Host "  Deleting: $dir" -ForegroundColor Gray
            Remove-Item -Path $dir -Recurse -Force -ErrorAction SilentlyContinue
            $deletedCount++
        }
    }
    
    if ($deletedCount -eq 0) {
        Write-Host "  No Chroma data directory found" -ForegroundColor Gray
    } else {
        Write-Host "  Deleted $deletedCount data directorie(s)" -ForegroundColor Green
    }
    
    Write-Host ""
    Write-Host "Chroma data cleared" -ForegroundColor Green
    Write-Host "Next start will create new empty database" -ForegroundColor Gray
    Write-Host ""
}

function Show-ChromaStatus {
    Write-Host ""
    Write-Host "========================================" -ForegroundColor Cyan
    Write-Host "  Chroma Status" -ForegroundColor Cyan
    Write-Host "========================================" -ForegroundColor Cyan
    
    $processes = Test-ChromaRunning
    
    if ($processes) {
        Write-Host "Status: RUNNING" -ForegroundColor Green
        Write-Host "Processes: $($processes.Count)" -ForegroundColor Gray
        foreach ($proc in $processes) {
            Write-Host "  - PID: $($proc.Id), Memory: $([math]::Round($proc.WorkingSet64/1MB, 2)) MB" -ForegroundColor Gray
        }
        Write-Host "URL: http://$CHROMA_HOST`:$CHROMA_PORT" -ForegroundColor Cyan
    } else {
        Write-Host "Status: NOT RUNNING" -ForegroundColor Red
        Write-Host "Start command: .\manage_chroma.ps1 start" -ForegroundColor Gray
    }
    
    Write-Host ""
}

switch ($Action) {
    "start" { Start-Chroma }
    "stop" { Stop-Chroma }
    "restart" {
        Write-Host "Restarting Chroma..." -ForegroundColor Cyan
        Stop-Chroma
        Start-Sleep -Seconds 1
        Start-Chroma
    }
    "clear" { Clear-ChromaData }
    "status" { Show-ChromaStatus }
    default {
        Write-Host ""
        Write-Host "Usage: .\manage_chroma.ps1 [start|stop|restart|clear|status]" -ForegroundColor Cyan
        Write-Host ""
        Write-Host "Commands:" -ForegroundColor Yellow
        Write-Host "  start   - Start Chroma service" -ForegroundColor Gray
        Write-Host "  stop    - Stop Chroma service" -ForegroundColor Gray
        Write-Host "  restart - Restart Chroma service" -ForegroundColor Gray
        Write-Host "  clear   - Clear all Chroma data (DANGEROUS)" -ForegroundColor Gray
        Write-Host "  status  - Show Chroma status (default)" -ForegroundColor Gray
        Write-Host ""
    }
}
