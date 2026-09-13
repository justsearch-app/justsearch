# cleanup.ps1
# Kills all JustSearch-related Java processes
# Usage: .\scripts\dev\cleanup.ps1 [-Force]

param(
    [switch]$Force
)

Write-Host "Scanning for JustSearch Java processes..." -ForegroundColor Cyan
Write-Host ""

$found = @()

Get-Process java -ErrorAction SilentlyContinue | ForEach-Object {
    try {
        $proc = $_
        $wmiProc = Get-CimInstance Win32_Process -Filter "ProcessId=$($proc.Id)" -ErrorAction SilentlyContinue
        $cmd = $wmiProc.CommandLine
        
        # Lane F stage A item A13: the "indexer-worker" arm (labelled IndexerWorker) is gone with
        # the Worker child process and its start script: there is no such command line to match.
        if ($cmd -match "HeadlessApp|justsearch|io\.justsearch") {
            $found += [PSCustomObject]@{
                PID = $proc.Id
                Name = if ($cmd -match "HeadlessApp") { "HeadlessApp" }
                       else { "JustSearch" }
                Memory = [math]::Round($proc.WorkingSet64 / 1MB, 1)
                Command = if ($cmd.Length -gt 80) { $cmd.Substring(0, 80) + "..." } else { $cmd }
            }
        }
    } catch {
        # Ignore processes we can't inspect
    }
}

if ($found.Count -eq 0) {
    Write-Host "No JustSearch Java processes found." -ForegroundColor Green
    exit 0
}

Write-Host "Found $($found.Count) JustSearch process(es):" -ForegroundColor Yellow
Write-Host ""
$found | Format-Table -AutoSize

if ($Force) {
    Write-Host "Killing all processes..." -ForegroundColor Red
    $found | ForEach-Object {
        Write-Host "  Stopping PID $($_.PID) ($($_.Name))..." -ForegroundColor Yellow
        Stop-Process -Id $_.PID -Force -ErrorAction SilentlyContinue
    }
    Write-Host ""
    Write-Host "Done." -ForegroundColor Green
} else {
    Write-Host ""
    Write-Host "To kill these processes, run:" -ForegroundColor Gray
    Write-Host "  .\scripts\dev\cleanup.ps1 -Force" -ForegroundColor White
    Write-Host ""
    Write-Host "Or kill specific PIDs:" -ForegroundColor Gray
    $found | ForEach-Object {
        Write-Host "  Stop-Process -Id $($_.PID) -Force" -ForegroundColor White
    }
}

