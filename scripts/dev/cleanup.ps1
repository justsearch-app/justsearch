# cleanup.ps1
# Kills all JustSearch-related Java processes
# Usage: .\scripts\dev\cleanup.ps1 [-Force]

param(
    [switch]$Force
)

Write-Host "Scanning for JustSearch Java processes..." -ForegroundColor Cyan
Write-Host ""

$found = @()

Get-CimInstance Win32_Process -Filter "Name='java.exe'" -ErrorAction SilentlyContinue | ForEach-Object {
    try {
        $proc = $_
        $cmd = $proc.CommandLine
        
        # Lane F stage A item A13: the "indexer-worker" arm (labelled IndexerWorker) is gone with
        # the Worker child process and its start script: there is no such command line to match.
        if ($proc.CreationDate -and $cmd -match "HeadlessApp|justsearch|io\.justsearch") {
            $found += [PSCustomObject]@{
                PID = $proc.ProcessId
                CreationFileTimeUtc = [string]$proc.CreationDate.ToFileTimeUtc()
                Name = if ($cmd -match "HeadlessApp") { "HeadlessApp" }
                       else { "JustSearch" }
                Memory = [math]::Round($proc.WorkingSetSize / 1MB, 1)
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
    $incomplete = $false
    Write-Host "Killing all processes..." -ForegroundColor Red
    $found | ForEach-Object {
        Write-Host "  Stopping PID $($_.PID) ($($_.Name))..." -ForegroundColor Yellow
        & powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File "$PSScriptRoot/lib/terminate-process.ps1" -Pid $_.PID -CreationFileTimeUtc $_.CreationFileTimeUtc -Code 1
        if ($LASTEXITCODE -ne 0) { $incomplete = $true }
    }
    Write-Host ""
    if ($incomplete) { Write-Host "Incomplete: at least one recorded process could not be ended." -ForegroundColor Yellow; exit 1 }
    Write-Host "Done." -ForegroundColor Green
} else {
    Write-Host ""
    Write-Host "To kill these processes, run:" -ForegroundColor Gray
    Write-Host "  .\scripts\dev\cleanup.ps1 -Force" -ForegroundColor White
    Write-Host ""
    Write-Host "Or stop one recorded identity:" -ForegroundColor Gray
    $found | ForEach-Object {
        Write-Host "  powershell.exe -NoProfile -File scripts/dev/lib/terminate-process.ps1 -Pid $($_.PID) -CreationFileTimeUtc $($_.CreationFileTimeUtc) -Code 1" -ForegroundColor White
    }
}

