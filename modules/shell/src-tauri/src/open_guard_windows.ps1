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
function Observation($entries) {
    $json = $entries | Select-Object Hwnd, Path,
        @{ Name = 'ExpectedPath'; Expression = { $env:OPEN_GUARD_EXPECTED } },
        @{ Name = 'ExpectedSelection'; Expression = { $env:OPEN_GUARD_SELECTED } },
        SelectionCount, Selected, Focused, PipelineSelected |
        ConvertTo-Json -Depth 4 -Compress
    if ($env:OPEN_GUARD_OBSERVATIONS) {
        Add-Content -LiteralPath $env:OPEN_GUARD_OBSERVATIONS -Value $json -Encoding UTF8
    }
    Write-Output $json
}
if ($Mode -eq 'observe') {
    # TEMP can contain an 8.3 ancestor (RUNNER~1) while Explorer reports the long name.
    # Expand BOTH complete paths with Win32; an expansion failure is never a match.
    Add-Type -TypeDefinition @'
using System.Runtime.InteropServices;
using System.Text;
public static class OpenGuardPaths {
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, ExactSpelling = true)]
    private static extern uint GetLongPathNameW(string path, StringBuilder result, uint capacity);
    public static string Expand(string path) {
        if (string.IsNullOrEmpty(path)) return null;
        var result = new StringBuilder(32768);
        uint size = GetLongPathNameW(path, result, (uint)result.Capacity);
        return size > 0 && size < result.Capacity ? result.ToString() : null;
    }
}
'@
    $expected = [OpenGuardPaths]::Expand($env:OPEN_GUARD_EXPECTED)
    if (!$expected) { throw "Cannot expand expected folder '$env:OPEN_GUARD_EXPECTED'" }
    $last = @()
    $deadline = [DateTime]::UtcNow.AddSeconds(15)
    do {
        $last = @(Windows)
        foreach ($entry in $last) {
            $actual = [OpenGuardPaths]::Expand($entry.Path)
            if ($actual -and [string]::Equals($actual, $expected, [StringComparison]::OrdinalIgnoreCase)) {
                # SelectedItems returns a FolderItems collection. Read its documented Count/Item
                # interface explicitly, retaining the old pipeline result only as a diagnostic.
                # Never call SelectItem here: that would repair the product's result in the test.
                $items = $entry.Window.Document.SelectedItems()
                $selected = @(for ($index = 0; $index -lt $items.Count; $index++) {
                    [string]$items.Item($index).Path
                })
                $focused = try { [string]$entry.Window.Document.FocusedItem.Path } catch { '' }
                $pipeline = @($items | ForEach-Object { [string]$_.Path })
                $entry | Add-Member -NotePropertyName SelectionCount -NotePropertyValue $items.Count
                $entry | Add-Member -NotePropertyName Selected -NotePropertyValue $selected
                $entry | Add-Member -NotePropertyName Focused -NotePropertyValue $focused
                $entry | Add-Member -NotePropertyName PipelineSelected -NotePropertyValue $pipeline
                # Hosted /select exposes neither reliable focus nor selection. Target identity
                # is verified separately using the production PIDLs; GUI checks require this
                # exact parent and retain item state strictly as observations.
                Observation @($entry)
                exit 0
            }
        }
        Start-Sleep -Milliseconds 200
    } while ([DateTime]::UtcNow -lt $deadline)
    Observation $last
    [Console]::Error.WriteLine("No actual Explorer window at '$env:OPEN_GUARD_EXPECTED' (item '$env:OPEN_GUARD_SELECTED' is diagnostic only)")
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
