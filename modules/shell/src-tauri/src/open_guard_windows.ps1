# Test-only observer. Never changes associations or kills Explorer/the baseline user's apps.
param([ValidateSet('snapshot', 'observe', 'cleanup')][string]$Mode)
$ErrorActionPreference = 'Stop'
$shell = New-Object -ComObject Shell.Application
function Windows {
    @($shell.Windows() | ForEach-Object {
        try {
            [pscustomobject]@{ Hwnd = [long]$_.HWND; Path = [string]$_.Document.Folder.Self.Path; Window = $_ }
        } catch { } # A closing window can disappear during enumeration.
    })
}
function Processes { @(Get-CimInstance Win32_Process | Select-Object ProcessId, CreationDate, Name, CommandLine) }
if ($Mode -eq 'snapshot') {
    $processes = Processes
    # A reused Notepad tab cannot be owned/closed safely by PID. Fail before opening any fixture.
    if (@($processes | Where-Object Name -eq 'notepad.exe').Count) {
        throw 'The isolated GUI test lane must start without Notepad; refusing to reuse someone else''s tabs.'
    }
    @{ Windows = @(Windows | Select-Object Hwnd, Path); Processes = $processes } |
        ConvertTo-Json -Depth 5 | Set-Content -Encoding UTF8 -LiteralPath $env:OPEN_GUARD_SNAPSHOT
    exit 0
}
$before = Get-Content -Raw -LiteralPath $env:OPEN_GUARD_SNAPSHOT | ConvertFrom-Json
if ($Mode -eq 'observe') {
    $last = @()
    $deadline = [DateTime]::UtcNow.AddSeconds(15)
    do {
        $last = @(Windows)
        foreach ($entry in $last) {
            if ($entry.Path -ieq $env:OPEN_GUARD_EXPECTED) {
                $selected = @($entry.Window.Document.SelectedItems() | ForEach-Object { [string]$_.Path })
                if (!$env:OPEN_GUARD_SELECTED -or $selected -icontains $env:OPEN_GUARD_SELECTED) {
                    @{ Hwnd = $entry.Hwnd; Path = $entry.Path; Selected = $selected } | ConvertTo-Json -Compress
                    exit 0
                }
            }
        }
        Start-Sleep -Milliseconds 200
    } while ([DateTime]::UtcNow -lt $deadline)
    $last | Select-Object Hwnd, Path | ConvertTo-Json -Compress | Write-Output
    [Console]::Error.WriteLine("No actual Explorer window at '$env:OPEN_GUARD_EXPECTED' with selection '$env:OPEN_GUARD_SELECTED'")
    exit 1
}
# Every test holds the same Rust mutex. Record new window HWND/location pairs, including wrong
# Desktop/Documents windows from the historical dispatch, then close just those COM windows.
# New document viewer PIDs are owned only when their command line or new window title names a
# fixture. PID creation times are checked again before stopping them; never stop baseline PIDs.
$owned = @{}
for ($attempt = 0; $attempt -lt 12; $attempt++) {
    foreach ($entry in @(Windows)) {
        if (@($before.Windows.Hwnd) -notcontains $entry.Hwnd) {
            Write-Output "Closing test Explorer HWND=$($entry.Hwnd) location=$($entry.Path)"
            try { $entry.Window.Quit() } catch {
                # A window can vanish between enumeration and Quit; final enumeration checks leaks.
                Write-Output "Window close raced HWND=$($entry.Hwnd): $_"
            }
        }
    }
    foreach ($process in @(Processes)) {
        if ($process.ProcessId -eq $PID -or @($before.Processes.ProcessId) -contains $process.ProcessId -or $process.Name -ieq 'explorer.exe') { continue }
        $native = Get-Process -Id $process.ProcessId -ErrorAction SilentlyContinue
        $title = if ($native) { $native.MainWindowTitle } else { '' }
        $fixture = $process.CommandLine -and $process.CommandLine.IndexOf($env:OPEN_GUARD_ROOT, [StringComparison]::OrdinalIgnoreCase) -ge 0
        foreach ($document in @($env:OPEN_GUARD_DOCUMENTS -split '\|')) {
            if (!$document) { continue }
            $name = [IO.Path]::GetFileName($document)
            $stem = [IO.Path]::GetFileNameWithoutExtension($document)
            if ($title -match ('^(?:' + [regex]::Escape($name) + '|' + [regex]::Escape($stem) + ')(?: - |$)')) { $fixture = $true }
        }
        if ($fixture) { $owned[[string]$process.ProcessId] = $process.CreationDate }
    }
    foreach ($id in @($owned.Keys)) {
        $process = Get-CimInstance Win32_Process -Filter "ProcessId=$id"
        if ($process -and $process.CreationDate -eq $owned[$id]) {
            Write-Output "Closing owned viewer PID=$id created=$($process.CreationDate)"
            $native = Get-Process -Id $id -ErrorAction SilentlyContinue
            if ($native) { $native.CloseMainWindow() | Out-Null }
            Start-Sleep -Milliseconds 100
            $process = Get-CimInstance Win32_Process -Filter "ProcessId=$id"
            if ($process -and $process.CreationDate -eq $owned[$id]) { Stop-Process -Id $id -Force }
        }
    }
    Start-Sleep -Milliseconds 250
}
$remaining = @(Windows | Where-Object { @($before.Windows.Hwnd) -notcontains $_.Hwnd })
if ($remaining.Count) { throw "Test Explorer windows remained: $($remaining | Select-Object Hwnd, Path | ConvertTo-Json -Compress)" }
