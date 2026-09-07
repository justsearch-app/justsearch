<#
.SYNOPSIS
    Captures a "capture-half" evidence snapshot of a running JustSearch backend
    inside a Windows Sandbox validation round (tempdoc 728).

.DESCRIPTION
    This script only CAPTURES raw data -- it does not interpret or judge it.
    Judgment (is the UI honest/scary, does the coverage look right) stays with
    the human/agent reviewing the evidence directory afterward.

    Steps:
      1. Discover the backend's API port (canonical: runtime manifest;
         fallback: listening-socket scan).
      2. Hit a fixed API sanity ladder and save each raw response body.
      2.5. Fetch the session token (GET /api/mcp/token) and exercise the
         MUTATING surface with it (POST /api/knowledge/search). A 401 here is
         reported as a LOUD FAILURE, not a quietly recorded status: the
         all-GET ladder above scored 6/6 in round 10 while every non-GET
         request the product makes was 401-dead. The verdict is ALSO written
         to evidence\mutating-probe.v1.json, which check_coverage.py grades
         fail-closed host-side (tempdoc 808 I1b) -- before that it existed
         only in console output and the summary, which nothing read.
      2.6. Copy every ladder snapshot into evidence\api-history\<UTC stamp>\
         so repeated runs stop overwriting each other's evidence, and append
         one record to evidence\collect-runs.ndjson (tempdoc 808 I3). The
         fixed-name snapshots are unchanged -- other consumers read them by
         name.
      3. Exercise the /mcp endpoint via the official MCP Inspector CLI (npx).
      3.1. Raw POST /mcp reachability probe carrying the session header the
         Inspector CLI cannot send -- run whenever the inspector path did not
         succeed, so a harness limitation is never mistaken for a dead /mcp.
      4. Write a plain-text summary of what was captured.

    Windows PowerShell 5.1 compatible. Runs unattended -- no interactive
    prompts, no dialogs.

.PARAMETER EvidenceDir
    Directory to write evidence files into. Created if missing. Default: .\evidence

.PARAMETER DataDir
    JustSearch data directory containing runtime\manifest.json.
    Default: $env:APPDATA\io.justsearch.shell
#>

param(
    [string]$EvidenceDir = ".\evidence",
    [string]$DataDir = "$env:APPDATA\io.justsearch.shell"
)

$ErrorActionPreference = "Stop"

function Write-Log {
    param([string]$Message)
    Write-Host "[collect-evidence] $Message"
}

function Get-SanitizedFileName {
    param([string]$ApiPath)
    $sanitized = $ApiPath -replace '^/', '' -replace '/', '-'
    if ([string]::IsNullOrEmpty($sanitized)) {
        $sanitized = "root"
    }
    return $sanitized
}

function Write-Utf8NoBom {
    # Windows PowerShell 5.1's `Out-File -Encoding utf8` still writes a UTF-8
    # BOM (ef bb bf), which broke the host-side checkers' plain `open(path,
    # encoding="utf-8")` reads (JSONDecodeError: Expecting value: line 1
    # column 1 -- a false "no fingerprint" BLOCKING verdict against a round
    # that was actually fine). The checkers are now BOM-tolerant
    # (utf-8-sig), but this writer-side fix keeps new evidence BOM-free too,
    # so any other consumer of these files doesn't hit the same trap.
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true, ValueFromPipeline = $true)][AllowEmptyString()][string]$Content
    )
    process {
        $utf8NoBom = New-Object System.Text.UTF8Encoding $false
        [System.IO.File]::WriteAllText($Path, $Content, $utf8NoBom)
    }
}

function Get-FirstSpanStartUtc {
    # Extracts the EARLIEST span's "start" timestamp from a traces.ndjson file,
    # via regex over the raw text -- NEVER line-by-line JSON parsing. Per
    # sandbox-CLAUDE.md's documented trap: traces.ndjson embeds document
    # excerpts in span attrs that can contain literal CRLFs, so a "line" of
    # the file is not reliably one JSON document, and Get-Content | ConvertFrom-Json
    # can throw or silently under-read. Spans are appended in chronological
    # order, so the FIRST "start" match in the raw text is the earliest span.
    # Returns $null if the file is missing/unreadable or has no "start" field.
    param([Parameter(Mandatory = $true)][string]$Path)
    if (-not (Test-Path -LiteralPath $Path)) {
        return $null
    }
    try {
        $content = Get-Content -LiteralPath $Path -Raw -ErrorAction Stop
    }
    catch {
        return $null
    }
    $m = [regex]::Match($content, '"start"\s*:\s*"([^"]+)"')
    if (-not $m.Success) {
        return $null
    }
    try {
        return [datetime]::Parse(
            $m.Groups[1].Value, [System.Globalization.CultureInfo]::InvariantCulture,
            [System.Globalization.DateTimeStyles]::RoundtripKind)
    }
    catch {
        return $null
    }
}

function Write-MutatingProbeVerdict {
    # tempdoc 808 I1b: the mutating-surface rung's verdict, machine-readable.
    # It was already detected and already printed loudly -- but only to the
    # console and collect-evidence-summary.txt, which NO host-side checker
    # reads, so three rounds of tested detection never reached an exit code.
    # check_coverage.py's check_mutating_probe grades this file. Still no exit
    # -code change here: collect-evidence is capture-only by contract.
    param(
        [Parameter(Mandatory = $true)][string]$EvidenceDirectory,
        [Parameter(Mandatory = $true)][ValidateSet("pass", "fail", "skipped")][string]$Status,
        [Parameter(Mandatory = $true)][AllowEmptyString()][string]$Detail
    )
    $record = New-Object PSObject -Property @{
        schema = "mutating-probe.v1"
        status = $Status
        detail = $Detail
    }
    $outPath = Join-Path -Path $EvidenceDirectory -ChildPath "mutating-probe.v1.json"
    ($record | ConvertTo-Json -Depth 5) | Write-Utf8NoBom -Path $outPath
    Write-Log "Wrote mutating-probe.v1.json (status=$Status)"
}

function Add-CollectRunRecord {
    # tempdoc 808 I3: one appended JSON line per invocation. sandbox-CLAUDE.md
    # tells rounds to run this script "early and after each major step", and
    # every run overwrote the last one's fixed-name snapshots -- so the
    # product's progression through install/enrichment was unrecoverable
    # afterwards (rounds 10-13 could not reconstruct it). This is pure
    # information preservation: nothing grades it.
    param(
        [Parameter(Mandatory = $true)][string]$EvidenceDirectory,
        [Parameter(Mandatory = $true)][string]$Mode,
        [Parameter(Mandatory = $true)][bool]$BackendReachable,
        [Parameter(Mandatory = $true)][bool]$LadderOk,
        [Parameter(Mandatory = $true)][string]$MutatingProbe
    )
    $record = New-Object PSObject -Property @{
        ts               = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ")
        mode             = $Mode
        backendReachable = $BackendReachable
        ladderOk         = $LadderOk
        mutatingProbe    = $MutatingProbe
    }
    # Compress-Archive-style one-line JSON: -Compress keeps each record on a
    # single line, which is what makes the file NDJSON rather than a stream of
    # pretty-printed blocks.
    $line = ($record | ConvertTo-Json -Depth 5 -Compress)
    $outPath = Join-Path -Path $EvidenceDirectory -ChildPath "collect-runs.ndjson"
    $existing = ""
    if (Test-Path -LiteralPath $outPath) {
        try {
            $existing = [System.IO.File]::ReadAllText($outPath)
        }
        catch {
            $existing = ""
        }
    }
    if (($existing -ne "") -and (-not $existing.EndsWith("`n"))) {
        $existing = $existing + "`r`n"
    }
    ($existing + $line + "`r`n") | Write-Utf8NoBom -Path $outPath
    Write-Log "Appended a run record to collect-runs.ndjson (mode=$Mode, ladderOk=$LadderOk, mutatingProbe=$MutatingProbe)"
}

function Invoke-ApiRequest {
    # Windows PowerShell 5.1's Invoke-WebRequest THROWS on any non-2xx, so a
    # 401 -- the exact status this harness now has to be able to SEE (round 10:
    # the whole mutating surface answered 401 while the all-GET ladder scored
    # 6/6) -- arrives as an exception, not as a response. This normalises both
    # paths into one { StatusCode; Body; Ok; Error } shape so callers assert on
    # a status code instead of catching and hoping.
    param(
        [Parameter(Mandatory = $true)][string]$Url,
        [string]$Method = "GET",
        [string]$Body,
        [hashtable]$Headers,
        [int]$TimeoutSec = 30
    )
    $statusCode = $null
    $responseBody = ""
    $errorMessage = ""
    try {
        $requestParams = @{
            Uri             = $Url
            Method          = $Method
            UseBasicParsing = $true
            TimeoutSec      = $TimeoutSec
            ErrorAction     = "Stop"
        }
        if ($Headers -ne $null) {
            $requestParams["Headers"] = $Headers
        }
        if ($PSBoundParameters.ContainsKey("Body")) {
            $requestParams["Body"] = $Body
            $requestParams["ContentType"] = "application/json"
        }
        $response = Invoke-WebRequest @requestParams
        $statusCode = [int]$response.StatusCode
        $responseBody = $response.Content
    }
    catch {
        $errorMessage = $_.Exception.Message
        # PS 5.1 drains the error response body into $_.ErrorDetails.Message, so
        # GetResponseStream() on the same response comes back EMPTY (verified
        # live against a 401 here). Read ErrorDetails first; keep the stream read
        # as the fallback for the cases where it is not populated.
        if (($_.ErrorDetails -ne $null) -and (-not [string]::IsNullOrEmpty($_.ErrorDetails.Message))) {
            $responseBody = $_.ErrorDetails.Message
        }
        if ($_.Exception.Response -ne $null) {
            try {
                $statusCode = [int]$_.Exception.Response.StatusCode
            }
            catch {
                $statusCode = $null
            }
            if ([string]::IsNullOrEmpty($responseBody)) {
                try {
                    $stream = $_.Exception.Response.GetResponseStream()
                    if ($stream -ne $null) {
                        $reader = New-Object System.IO.StreamReader($stream)
                        $responseBody = $reader.ReadToEnd()
                        $reader.Close()
                    }
                }
                catch {
                    $responseBody = ""
                }
            }
        }
    }
    $isOk = $false
    if ($statusCode -ne $null) {
        $isOk = ($statusCode -ge 200) -and ($statusCode -lt 300)
    }
    return New-Object PSObject -Property @{
        StatusCode = $statusCode
        Body       = $responseBody
        Ok         = $isOk
        Error      = $errorMessage
    }
}

# ---------------------------------------------------------------------------
# Setup
# ---------------------------------------------------------------------------

if (-not (Test-Path -LiteralPath $EvidenceDir)) {
    New-Item -ItemType Directory -Path $EvidenceDir -Force | Out-Null
}
$EvidenceDir = (Resolve-Path -LiteralPath $EvidenceDir).ProviderPath

$timestamp = Get-Date -Format "yyyy-MM-ddTHH:mm:ss.fffK"
Write-Log "Starting evidence collection at $timestamp"
Write-Log "EvidenceDir: $EvidenceDir"
Write-Log "DataDir: $DataDir"

# ---------------------------------------------------------------------------
# Step 0: Elevation self-check (D3, tempdoc 728-followup; reframed tempdoc 729)
# ---------------------------------------------------------------------------
# The README's "no admin rights needed" claim is verified host-side by
# scripts/ci/check-installer-execution-level.mjs (config + built-artifact
# manifest) -- this round no longer needs to prove it. What THIS session's
# elevation state still affects is REALISM, not observability-of-a-claim: an
# elevated round does not reproduce a normal user's environment and can mask
# permission defects a real (non-admin) user would hit -- a pass that depends
# on an environment precondition is not a pass (this repo's
# green-masked-destructive principle). It also happens to make a UAC prompt
# unobservable (an already-elevated process tree is never re-prompted), which
# is a secondary, structural consequence worth recording, not the main reason
# to avoid running elevated. Same detection pattern as
# scripts/bench/_lib/launch-elevated.ps1.
$isElevated = [Security.Principal.WindowsPrincipal]::new(
    [Security.Principal.WindowsIdentity]::GetCurrent()
).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)

if ($isElevated) {
    $elevationNote = "ELEVATED: this session's terminal is running with Administrator " +
        "privileges. This round does NOT reproduce a normal (non-admin) user's " +
        "environment and can mask permission defects a real user would hit -- a pass " +
        "obtained under this precondition is not a clean pass. As a structural " +
        "consequence, no UAC prompt can appear during the JustSearch install this round " +
        "regardless of what the installer requests (an already-elevated process tree is " +
        "never re-prompted); record any UAC-observation item as STRUCTURALLY " +
        "UNOBSERVABLE this round (with this reason), not as a pass or as silence. The " +
        "no-admin claim itself does not depend on this round: the installer is per-user " +
        "(bundle.windows.nsis.installMode: currentUser, ADR-0024) and requests no " +
        "elevation, asserted mechanically -- config AND built-artifact manifest -- by " +
        "scripts/ci/check-installer-execution-level.mjs (proves only that the installer " +
        "doesn't request elevation; says nothing about SmartScreen/publisher trust)."
}
else {
    $elevationNote = "NOT ELEVATED: this session's terminal is running as a standard " +
        "user, reproducing a normal user's install environment. If a UAC prompt appears " +
        "during the JustSearch install, that is a finding -- record the publisher shown " +
        "and report it."
}
Write-Log $elevationNote

# Install-state fingerprint (tempdoc 729-followup, extends this same Step-0
# self-check slot rather than adding a parallel one). A round once relaunched
# the installer over an already-installed product because nothing checked
# for a prior install first. Three independent signals a per-user NSIS
# install leaves behind (bundle.windows.nsis.installMode: currentUser,
# ADR-0024): the installed binary under %LOCALAPPDATA%\JustSearch directly
# (per ADR-0024: "Installation target is per-user at %LOCALAPPDATA%\JustSearch"
# -- there is no Programs subfolder; confirmed live against a real install,
# Sandbox round 6, tempdoc 734), the app data dir
# (-DataDir, default %APPDATA%\io.justsearch.shell), and the uninstall
# registry entry (verified against
# scripts/ci/verify-installer-nsis-win.ps1's $uninstRegKey). This is
# informational, not blocking -- a round may deliberately be re-running
# against an existing install (upgrade/repair scenarios) -- but it must be
# recorded so a fresh-install round can't silently double-install.
#
# tempdoc 750 Part C: an upgrade-from-release round deliberately installs
# the PREVIOUS release before the candidate, so a prior install is that
# round's ASSERTED-EXPECTED precondition, not a defect. sandbox-launch.py
# writes a machine-readable "ExpectPriorInstall: true/false" marker into
# validation-mode.md for exactly this -- read it back here so the verdict
# flips: FOUND becomes OK and a NOT-FOUND signal becomes the warning
# instead (the upgrade scenario did not happen as expected). Absent/
# malformed/missing validation-mode.md defaults to false, i.e. today's
# behavior below, unchanged.
#
# tempdoc 808 I3: the same file is the only in-sandbox source for the round's
# resolved MODE (write_validation_mode writes a "- Mode: <mode>" line), so it
# is read back here too for the collect-runs.ndjson invocation log below --
# recorded, never guessed. Absent/malformed leaves it "unknown".
$validationModePath = Join-Path -Path $PSScriptRoot -ChildPath "validation-mode.md"
$expectPriorInstall = $false
$roundMode = "unknown"
if (Test-Path -LiteralPath $validationModePath) {
    try {
        $validationModeContent = Get-Content -LiteralPath $validationModePath -Raw -ErrorAction Stop
        if ($validationModeContent -match 'ExpectPriorInstall:\s*true') {
            $expectPriorInstall = $true
        }
        if ($validationModeContent -match '(?m)^\s*-\s*Mode:\s*(\S+)') {
            $roundMode = $Matches[1]
        }
    }
    catch {
        Write-Log "Could not read $validationModePath for ExpectPriorInstall ($($_.Exception.Message)) -- defaulting to false."
    }
}

$installedExePath = Join-Path -Path $env:LOCALAPPDATA -ChildPath "JustSearch\JustSearch.exe"
$installedExeFound = Test-Path -LiteralPath $installedExePath
$dataDirFound = Test-Path -LiteralPath $DataDir
$uninstallRegKey = "HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\JustSearch"
$uninstallRegFound = Test-Path -LiteralPath $uninstallRegKey

# Round-start-relative fingerprint (round-15 retrospective item 3, tempdoc
# 798): the raw presence check above cannot distinguish "installed by THIS
# round, moments ago" from "a genuinely stale prior install" -- on every
# fresh-install round that installs before ever calling collect-evidence.ps1
# (the normal, documented workflow: install, THEN start capturing evidence),
# the FIRST invocation already finds the just-installed exe and fires the
# "known round defect" alarm against a candidate that has nothing wrong with
# it. Windows Sandbox boots a fresh, ephemeral environment per launch
# (sandbox-CLAUDE.md's "Mission" section) -- so the OS's own boot time IS
# this round's start, with no marker file to write and no invocation-order
# dependency. An artifact created AFTER boot was necessarily created during
# THIS round's own session; only an artifact that PREDATES boot (impossible
# in a true ephemeral Sandbox launch, but a real signal if this script is
# ever run against a persistent/reused environment) is evidence of a
# genuinely stale install.
$roundStartUtc = $null
$roundStartUnreadableReason = ""
try {
    # LastBootUpTime is a LOCAL-kind DateTime; the artifact side of the
    # comparison is CreationTimeUtc. PowerShell's -lt compares raw ticks and
    # ignores Kind, so on any UTC+N sandbox a file created after boot still
    # read as "predates boot" (941 round 19 H1: app data created 04:55Z vs
    # boot 04:32Z printed "predates boot: True"). Normalize here, once.
    $roundStartUtc = (Get-CimInstance -ClassName Win32_OperatingSystem -ErrorAction Stop).LastBootUpTime.ToUniversalTime()
}
catch {
    $roundStartUnreadableReason = $_.Exception.Message
    Write-Log "Could not read system boot time for the round-start-relative install fingerprint ($roundStartUnreadableReason) -- falling back to the non-timestamped fingerprint."
}

function Test-ArtifactPredatesRoundStart {
    # Fails OPEN (treats as "predates round start", i.e. potentially stale)
    # on any read error -- a missing timestamp must not silently suppress a
    # real warning, it should fall back to the pre-fix behavior instead.
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][datetime]$RoundStartUtc
    )
    if (-not (Test-Path -LiteralPath $Path)) {
        return $false
    }
    try {
        $created = (Get-Item -LiteralPath $Path -ErrorAction Stop).CreationTimeUtc
        return $created -lt $RoundStartUtc
    }
    catch {
        return $true
    }
}

function Get-ArtifactCreationTimeDisplay {
    # Reporting companion to Test-ArtifactPredatesRoundStart (round 16,
    # tempdoc 823 section 4): the predicate above collapses three very different
    # states -- "created after boot", "created before boot", "could not read
    # the timestamp at all" -- into one boolean, and the warning text below
    # used to collapse them further into a single "predates this session's
    # boot OR the boot time could not be read" sentence. A round on a
    # genuinely clean machine could not tell the real finding from the
    # unreadable-timestamp fallback. This returns the timestamp (or the
    # reason it is unavailable) so the warning can PRINT what it compared.
    param([Parameter(Mandatory = $true)][string]$Path)
    if (-not (Test-Path -LiteralPath $Path)) {
        return "not present"
    }
    try {
        return ((Get-Item -LiteralPath $Path -ErrorAction Stop).CreationTimeUtc.ToString("o") + " UTC")
    }
    catch {
        return "UNREADABLE ($($_.Exception.Message))"
    }
}

# The exe's own CreationTimeUtc is NOT a freshness oracle: the installer
# preserves the source file's creation time (941 round 19 H1: the exe read
# 04:23:40Z, the CI build, on a sandbox booted at 04:32Z). The install
# DIRECTORY is created by the installer at install time, so its creation
# time is the timestamp that actually says when this install happened.
$installedDirPath = Split-Path -Parent $installedExePath
$exePredatesRound = $false
$dataDirPredatesRound = $false
if ($roundStartUtc) {
    if ($installedExeFound) {
        $exePredatesRound = Test-ArtifactPredatesRoundStart -Path $installedDirPath -RoundStartUtc $roundStartUtc
    }
    if ($dataDirFound) {
        $dataDirPredatesRound = Test-ArtifactPredatesRoundStart -Path $DataDir -RoundStartUtc $roundStartUtc
    }
}
# The uninstall registry key's creation time is not exposed by Get-Item for a
# registry path (unlike a filesystem path) without extra P/Invoke -- its
# signal stays presence-only and does not gate staleness by itself.
$installPredatesRound = $exePredatesRound -or $dataDirPredatesRound

$installStateLines = @(
    "",
    "--- Install-state fingerprint (tempdoc 729-followup / 750 Part C) ---",
    "ExpectPriorInstall (from validation-mode.md): $expectPriorInstall",
    "Installed exe ($installedExePath): $(if ($installedExeFound) { 'FOUND' } else { 'not found' })",
    "App data dir ($DataDir): $(if ($dataDirFound) { 'FOUND' } else { 'not found' })",
    "Uninstall registry key ($uninstallRegKey): $(if ($uninstallRegFound) { 'FOUND' } else { 'not found' })"
)

if ($expectPriorInstall) {
    $missingSignals = @()
    if (-not $installedExeFound) { $missingSignals += "installed exe" }
    if (-not $dataDirFound) { $missingSignals += "app data dir" }
    if (-not $uninstallRegFound) { $missingSignals += "uninstall registry key" }
    if ($missingSignals.Count -gt 0) {
        $installStateLines += ("WARNING: this is an upgrade-from-release round (validation-mode.md: " +
            "ExpectPriorInstall: true), which asserts a prior install as the EXPECTED " +
            "precondition -- but the following signal(s) are NOT FOUND: " +
            ($missingSignals -join ", ") + ". The upgrade scenario (install the previous " +
            "release, seed data, quit it fully, then install the candidate over it) did not " +
            "happen as expected this round.")
        Write-Log "WARNING: upgrade-from-release round but prior-install signal(s) missing -- see elevation-check.txt"
    }
    else {
        $installStateLines += ("Prior-install state confirmed (all three signals FOUND) -- the expected " +
            "precondition for upgrade-from-release mode holds.")
    }
}
elseif ($installedExeFound -or $dataDirFound -or $uninstallRegFound) {
    if ($roundStartUtc -and -not $installPredatesRound) {
        $installStateLines += ("Install signal(s) found, but timestamped AFTER this session's boot " +
            "($($roundStartUtc.ToString('o')) UTC) -- consistent with an install performed by THIS " +
            "round itself (Windows Sandbox boots a fresh, ephemeral environment per launch), not a " +
            "stale prior install. No warning (round-start-relative fingerprint, tempdoc 817).")
        Write-Log "Install signal(s) found, but post-dates this session's boot -- treated as this round's own install, not a defect."
    }
    else {
        # Name the branch and print BOTH sides of the comparison (round 16,
        # tempdoc 823 section 4). The two branches mean opposite things -- one is a
        # real stale-install finding, the other is the harness admitting it
        # could not read a clock -- and round 16 spent a detour on a false
        # alarm because the single combined sentence could not be told apart
        # from the real finding on a genuinely clean machine.
        if (-not $roundStartUtc) {
            $branchLabel = "BOOT-TIME-UNREADABLE (fallback branch -- NOT a demonstrated stale install)"
            $bootDisplay = "UNREADABLE"
            if ($roundStartUnreadableReason -ne "") {
                $bootDisplay = "UNREADABLE ($roundStartUnreadableReason)"
            }
            # Never print "predates boot: False" when there is no boot time to
            # compare against -- that reads as a checked negative.
            $exePredatesDisplay = "not compared (no boot time)"
            $dataDirPredatesDisplay = "not compared (no boot time)"
        }
        else {
            $branchLabel = "ARTIFACT-PREDATES-BOOT (a timestamped artifact really is older than this session's boot)"
            $bootDisplay = ($roundStartUtc.ToUniversalTime().ToString("o") + " UTC")
            $exePredatesDisplay = $exePredatesRound
            $dataDirPredatesDisplay = $dataDirPredatesRound
        }
        $installStateLines += ("WARNING: JustSearch already appears to be installed on this system " +
            "(at least one signal above is FOUND). Branch that fired: $branchLabel.")
        $installStateLines += ("  Session boot time (Win32_OperatingSystem.LastBootUpTime): $bootDisplay")
        $installStateLines += ("  Install dir creation time ($installedDirPath): " +
            "$(Get-ArtifactCreationTimeDisplay -Path $installedDirPath) (predates boot: $exePredatesDisplay)")
        $installStateLines += ("  Installed exe creation time (informational; installer-preserved, never compared): " +
            "$(Get-ArtifactCreationTimeDisplay -Path $installedExePath)")
        $installStateLines += ("  App data dir creation time: " +
            "$(Get-ArtifactCreationTimeDisplay -Path $DataDir) (predates boot: $dataDirPredatesDisplay)")
        $installStateLines += ("  (Uninstall registry key timestamps are not exposed by Get-Item for a " +
            "registry path, so that signal is presence-only and never gates this branch.)")
        $installStateLines += ("Relaunching the installer over an " +
            "existing install is a known round defect. If this round intends a fresh " +
            "install, uninstall first (or use a clean sandbox); if this round intends " +
            "to validate upgrade/repair/re-run behavior over an existing install, record " +
            "that as the deliberate scenario instead of an oversight. If the branch above " +
            "is BOOT-TIME-UNREADABLE, this warning proves nothing about staleness on its " +
            "own -- compare the artifact creation times printed above against when this " +
            "round actually started before treating it as a finding.")
        Write-Log "WARNING: install-state fingerprint fired the '$branchLabel' branch -- see elevation-check.txt"
    }
}
else {
    $installStateLines += "No existing install detected -- clean-install precondition holds."
}

foreach ($line in $installStateLines) {
    if ($line) { Write-Log $line }
}

($elevationNote + "`n" + ($installStateLines -join "`n")) |
    Write-Utf8NoBom -Path (Join-Path -Path $EvidenceDir -ChildPath "elevation-check.txt")

# ---------------------------------------------------------------------------
# Step 0.5: snap-fail-loud precondition (round-11 charter item 8, tempdoc 805
# item 7)
# ---------------------------------------------------------------------------
# Round 11's retrospective: "charter item 8 asked me to verify that snap.ps1
# now fails loud on an unsavable path ... it should not have been mine to
# remember" -- a 3-line check that belongs in this Step 0, running every
# round automatically, not competing for a round's attention with product
# testing. It is also a direct instance of the class charter item 8 guards
# against: round 11's OWN capture wrapper called Save-DesktopShot with the
# wrong parameter name, every capture threw, was swallowed by a try/catch,
# and the script printed "captures: 209" for zero files actually written --
# judge a capture by Test-Path/exit code, never a printed line, including
# your own. This precondition proves gui\snap.ps1 (the shared, staged
# capture entry point) still fails LOUD -- non-zero exit, no "saved:" line
# -- when it cannot write its output, before any real evidence capture
# begins. Fast (<5s: one desktop capture attempt, no product interaction)
# and cleans up its own temp artifacts.
$snapScript = Join-Path -Path $PSScriptRoot -ChildPath "gui\snap.ps1"
if (Test-Path -LiteralPath $snapScript) {
    $precheckParent = Join-Path -Path ([System.IO.Path]::GetTempPath()) -ChildPath ("snap-precheck-" + [Guid]::NewGuid().ToString("N"))
    try {
        # Deliberately unsavable path: $precheckParent is a PLAIN FILE, so no
        # directory can ever be created under it and the PNG save must fail
        # -- exactly the "parent path exists but is not a directory" case
        # Save-PngChecked (JustSearchGui.psm1) detects and throws on.
        New-Item -ItemType File -Path $precheckParent -Force | Out-Null
        $badOut = Join-Path -Path $precheckParent -ChildPath "unreachable.png"

        # Run as a SEPARATE process (not `&` in this session): snap.ps1 calls
        # `exit 1` on failure, which would terminate this collector's own
        # PowerShell host if invoked in-process instead of just the sub-script.
        $snapOutput = & powershell.exe -NoProfile -File $snapScript -Out $badOut 2>&1
        $snapExit = $LASTEXITCODE

        if ($snapExit -eq 0) {
            throw ("PRECONDITION FAILED: gui\snap.ps1 against a deliberately unsavable path " +
                "($badOut, parent is a FILE) exited 0 (SILENT FAILURE) instead of a non-zero exit " +
                "code with no PNG written. A capture script that can fail silently can report " +
                "evidence that was never produced -- refusing to proceed with evidence collection " +
                "until snap.ps1 fails loud again. Captured output: $($snapOutput -join ' | ')")
        }
        Write-Log "Precondition OK: gui\snap.ps1 fails loud (exit $snapExit) against an unsavable path."
    }
    finally {
        # Clean up regardless of outcome -- including the badOut PNG, in the
        # (should-be-impossible) case the precondition itself did not fail.
        Remove-Item -LiteralPath $precheckParent -Force -ErrorAction SilentlyContinue
    }
}
else {
    Write-Log "WARNING: gui\snap.ps1 not found at $snapScript -- skipping snap-fail-loud precondition check."
}

# ---------------------------------------------------------------------------
# Step 1: Port discovery (canonical: runtime manifest.json; tempdoc 501)
# ---------------------------------------------------------------------------

$port = $null
$portSource = $null
$portDiscoveryNote = ""

$manifestPath = Join-Path -Path $DataDir -ChildPath "runtime\manifest.json"

if (Test-Path -LiteralPath $manifestPath) {
    try {
        $manifestRaw = Get-Content -LiteralPath $manifestPath -Raw -ErrorAction Stop
        $manifest = $manifestRaw | ConvertFrom-Json -ErrorAction Stop
        if ($manifest.head -and $manifest.head.apiPort) {
            $port = [int]$manifest.head.apiPort
            $portSource = "manifest:$manifestPath"
            Write-Log "Resolved apiPort $port from runtime manifest."
        }
        else {
            $portDiscoveryNote = "manifest.json found at $manifestPath but .head.apiPort was missing/empty."
            Write-Log $portDiscoveryNote
        }
    }
    catch {
        $portDiscoveryNote = "Failed to read/parse manifest.json at $manifestPath : $($_.Exception.Message)"
        Write-Log $portDiscoveryNote
    }
}
else {
    $portDiscoveryNote = "manifest.json not found at $manifestPath."
    Write-Log $portDiscoveryNote
}

$fallbackCandidates = @()
if (-not $port) {
    Write-Log "Falling back to listening-socket scan (Get-NetTCPConnection)."
    try {
        $connections = Get-NetTCPConnection -State Listen -ErrorAction Stop |
            Where-Object { $_.LocalAddress -eq '127.0.0.1' }
        $fallbackCandidates = $connections | Select-Object -ExpandProperty LocalPort -Unique | Sort-Object
        if ($fallbackCandidates -and $fallbackCandidates.Count -gt 0) {
            Write-Log "Candidate loopback listening ports found: $($fallbackCandidates -join ', ')"
            Write-Log "Not guessing which one is the backend -- recording candidates, not selecting automatically."
        }
        else {
            Write-Log "No loopback listening ports found."
        }
    }
    catch {
        Write-Log "Get-NetTCPConnection fallback failed: $($_.Exception.Message)"
    }
}

if (-not $port) {
    $errorLines = @()
    $errorLines += "ERROR: could not determine the backend API port."
    $errorLines += "Manifest lookup: $portDiscoveryNote"
    if ($fallbackCandidates -and $fallbackCandidates.Count -gt 0) {
        $errorLines += "Fallback candidate loopback listening ports (unconfirmed): $($fallbackCandidates -join ', ')"
        $errorLines += "None of these were assumed to be the backend port -- inspect manually and re-run with a known port if needed."
    }
    else {
        $errorLines += "Fallback listening-socket scan found no loopback listeners either."
    }
    $errorText = $errorLines -join "`r`n"
    Write-Host $errorText
    $summaryPath = Join-Path -Path $EvidenceDir -ChildPath "collect-evidence-summary.txt"
    $summaryLines = @()
    $summaryLines += "JustSearch Sandbox evidence collection summary"
    $summaryLines += "Timestamp: $timestamp"
    $summaryLines += "Resolved port: NONE"
    $summaryLines += $errorText
    ($summaryLines -join "`r`n") | Write-Utf8NoBom -Path $summaryPath
    # tempdoc 808 I1b/I3: this is the ONE branch where 'skipped' is honest --
    # the backend was never reachable, so the mutating surface was never
    # probed (as opposed to probed and refused). Both artifacts are still
    # written, so a round that died here is legible host-side instead of
    # simply missing the files.
    Write-MutatingProbeVerdict -EvidenceDirectory $EvidenceDir -Status "skipped" -Detail (
        "Backend API port could not be determined, so no request was ever sent. " + $errorText
    )
    Add-CollectRunRecord -EvidenceDirectory $EvidenceDir -Mode $roundMode `
        -BackendReachable $false -LadderOk $false -MutatingProbe "skipped"
    exit 1
}

$base = "http://127.0.0.1:$port"
Write-Log "Using base URL: $base (source: $portSource)"

# ---------------------------------------------------------------------------
# Step 2: API sanity ladder
# ---------------------------------------------------------------------------

$ladderPaths = @(
    "/api/health",
    "/api/status",
    "/api/knowledge/status",
    "/api/ai/runtime/status",
    "/api/inference/status",
    "/api/ai/install/status"
)

$ladderResults = @()

foreach ($apiPath in $ladderPaths) {
    $fileName = "api-" + (Get-SanitizedFileName -ApiPath $apiPath) + ".json"
    $outFile = Join-Path -Path $EvidenceDir -ChildPath $fileName
    $url = "$base$apiPath"
    Write-Log "GET $url"

    try {
        $response = Invoke-WebRequest -Uri $url -UseBasicParsing -ErrorAction Stop
        $statusCode = [int]$response.StatusCode
        $body = $response.Content
        $body | Write-Utf8NoBom -Path $outFile
        $ok = ($statusCode -ge 200) -and ($statusCode -lt 300)
        $ladderResults += New-Object PSObject -Property @{
            Method     = "GET"
            Path       = $apiPath
            StatusCode = $statusCode
            Ok         = $ok
            File       = $fileName
        }
        Write-Log "  -> $statusCode saved to $fileName"
    }
    catch {
        $statusCode = $null
        $exceptionMessage = $_.Exception.Message
        if ($_.Exception.Response -ne $null) {
            try {
                $statusCode = [int]$_.Exception.Response.StatusCode
            }
            catch {
                $statusCode = $null
            }
        }

        $errorBody = ""
        try {
            if ($_.Exception.Response -ne $null) {
                $stream = $_.Exception.Response.GetResponseStream()
                if ($stream -ne $null) {
                    $reader = New-Object System.IO.StreamReader($stream)
                    $errorBody = $reader.ReadToEnd()
                    $reader.Close()
                }
            }
        }
        catch {
            $errorBody = ""
        }

        $errorRecord = New-Object PSObject -Property @{
            path        = $apiPath
            url         = $url
            statusCode  = $statusCode
            exception   = $exceptionMessage
            responseBody = $errorBody
        }
        ($errorRecord | ConvertTo-Json -Depth 5) | Write-Utf8NoBom -Path $outFile

        $ladderResults += New-Object PSObject -Property @{
            Method     = "GET"
            Path       = $apiPath
            StatusCode = $statusCode
            Ok         = $false
            File       = $fileName
        }
        Write-Log "  -> ERROR ($exceptionMessage) recorded to $fileName"
    }
}

# ---------------------------------------------------------------------------
# Step 2.5: session token + the MUTATING-surface rung (round-10 finding F7/H1)
# ---------------------------------------------------------------------------
# Round 10 scored the ladder above 6/6 green while every non-GET request the
# product makes answered 401: in prod mode the head arms session-token
# enforcement on POST/PUT/DELETE, and an all-GET ladder is structurally unable
# to see that. One POST rung closes the hole -- and a 401 on it must FAIL
# LOUDLY, because silently recording it is exactly the green that shipped a
# product whose entire mutating surface was dead.

$sessionToken = ""
$sessionTokenNote = ""
$tokenResult = Invoke-ApiRequest -Url "$base/api/mcp/token"
if ($tokenResult.Ok) {
    try {
        $sessionToken = [string](($tokenResult.Body | ConvertFrom-Json).token)
    }
    catch {
        $sessionToken = ""
        $sessionTokenNote = "GET /api/mcp/token returned 200 but unparsable JSON ($($_.Exception.Message))"
    }
    if ($sessionTokenNote -eq "") {
        if ([string]::IsNullOrWhiteSpace($sessionToken)) {
            $sessionToken = ""
            $sessionTokenNote = "GET /api/mcp/token returned an EMPTY token -- session-token enforcement is off (dev mode). A 200 on the POST rung below therefore proves reachability, NOT that the token chain works."
        }
        else {
            $sessionTokenNote = "GET /api/mcp/token returned a token of length $($sessionToken.Length) (value deliberately NOT written to evidence)."
        }
    }
}
else {
    $statusText = "no response"
    if ($tokenResult.StatusCode -ne $null) {
        $statusText = "HTTP $($tokenResult.StatusCode)"
    }
    $sessionTokenNote = "GET /api/mcp/token FAILED ($statusText; $($tokenResult.Error)) -- the POST rung below runs WITHOUT a session header, so a 401 there cannot distinguish 'enforcement armed' from 'token endpoint broken'."
}
Write-Log $sessionTokenNote
# Presence/length only -- the token itself is a credential and must never land
# in an archived evidence directory.
$sessionTokenNote | Write-Utf8NoBom -Path (Join-Path -Path $EvidenceDir -ChildPath "session-token-probe.txt")

$mutatingPath = "/api/knowledge/search"
$mutatingUrl = "$base$mutatingPath"
$mutatingFileName = "api-post-" + (Get-SanitizedFileName -ApiPath $mutatingPath) + ".json"
$mutatingOutFile = Join-Path -Path $EvidenceDir -ChildPath $mutatingFileName
$mutatingRequestBody = '{"query":"sanity","limit":1}'
$mutatingHeaders = $null
if ($sessionToken -ne "") {
    $mutatingHeaders = @{ "X-JustSearch-Session" = $sessionToken }
}
Write-Log "POST $mutatingUrl (session header: $(if ($sessionToken -ne '') { 'sent' } else { 'NOT sent' }))"
$mutatingResult = Invoke-ApiRequest -Url $mutatingUrl -Method "POST" -Body $mutatingRequestBody -Headers $mutatingHeaders

$mutatingFailReason = ""
if ($mutatingResult.Ok) {
    $mutatingResult.Body | Write-Utf8NoBom -Path $mutatingOutFile
    Write-Log "  -> $($mutatingResult.StatusCode) saved to $mutatingFileName"
}
else {
    $mutatingRecord = New-Object PSObject -Property @{
        method       = "POST"
        path         = $mutatingPath
        url          = $mutatingUrl
        requestBody  = $mutatingRequestBody
        sessionHeaderSent = ($sessionToken -ne "")
        statusCode   = $mutatingResult.StatusCode
        exception    = $mutatingResult.Error
        responseBody = $mutatingResult.Body
    }
    ($mutatingRecord | ConvertTo-Json -Depth 5) | Write-Utf8NoBom -Path $mutatingOutFile

    if ($mutatingResult.StatusCode -eq 401) {
        $mutatingFailReason = "FAIL: POST $mutatingPath returned 401 UNAUTHORIZED" +
            "$(if ($sessionToken -ne '') { ' EVEN WITH the session token from GET /api/mcp/token' } else { ' and no session token could be obtained' })" +
            " -- the product's entire mutating surface (search, ingest, chat) is unusable in this round. " +
            "Every GET rung above can still be green while this is true; that combination is the round-10 " +
            "false-green (finding F7). Do NOT read this round's ladder as a pass. Details: $mutatingFileName. $sessionTokenNote"
    }
    else {
        $statusText = "no response"
        if ($mutatingResult.StatusCode -ne $null) {
            $statusText = "HTTP $($mutatingResult.StatusCode)"
        }
        $mutatingFailReason = "FAIL: POST $mutatingPath did not succeed ($statusText; $($mutatingResult.Error)) -- " +
            "the mutating surface was NOT proven to work this round. Details: $mutatingFileName."
    }
    Write-Log $mutatingFailReason
}

$ladderResults += New-Object PSObject -Property @{
    Method     = "POST"
    Path       = $mutatingPath
    StatusCode = $mutatingResult.StatusCode
    Ok         = $mutatingResult.Ok
    File       = $mutatingFileName
}

# tempdoc 808 I1b: the same verdict the console and the summary already carry,
# now in a file check_coverage.py actually reads. 'skipped' is NOT reachable
# here -- the backend answered something, or the run exited earlier at the
# port-discovery branch above.
if ($mutatingFailReason -ne "") {
    Write-MutatingProbeVerdict -EvidenceDirectory $EvidenceDir -Status "fail" -Detail $mutatingFailReason
    $mutatingProbeStatus = "fail"
}
else {
    Write-MutatingProbeVerdict -EvidenceDirectory $EvidenceDir -Status "pass" -Detail (
        "POST $mutatingPath returned $($mutatingResult.StatusCode) -- the ladder is not GET-only. $sessionTokenNote"
    )
    $mutatingProbeStatus = "pass"
}

# ---------------------------------------------------------------------------
# Step 2.6: timestamped API-ladder history (tempdoc 808 I3)
# ---------------------------------------------------------------------------
# Every rung above wrote a FIXED filename, and sandbox-CLAUDE.md tells rounds
# to run this script "early and after each major step" -- so each run silently
# destroyed the previous one's snapshots and the product's progression through
# install/enrichment was unreconstructable afterwards. The fixed-name copies
# stay exactly where they were (check_golden_parity.py reads
# api-api-knowledge-status.json BY NAME, and the golden capture below reads it
# too); this ADDITIONALLY copies each snapshot into a per-invocation directory.
# Copying the already-written files -- rather than writing twice at each rung --
# keeps the two copies byte-identical by construction and leaves every existing
# write path untouched.
$apiHistoryDir = Join-Path -Path $EvidenceDir -ChildPath ("api-history\" + (Get-Date).ToUniversalTime().ToString("yyyyMMdd-HHmmss"))
try {
    if (-not (Test-Path -LiteralPath $apiHistoryDir)) {
        New-Item -ItemType Directory -Path $apiHistoryDir -Force | Out-Null
    }
    $historyCopied = 0
    foreach ($result in $ladderResults) {
        $sourceFile = Join-Path -Path $EvidenceDir -ChildPath $result.File
        if (Test-Path -LiteralPath $sourceFile) {
            Copy-Item -LiteralPath $sourceFile -Destination (Join-Path -Path $apiHistoryDir -ChildPath $result.File) -Force
            $historyCopied++
        }
    }
    Write-Log "Archived $historyCopied ladder snapshot(s) to $apiHistoryDir"
}
catch {
    # Never fatal: history is a bonus, the fixed-name snapshots are the contract.
    Write-Log "Could not write the API-ladder history dir ($($_.Exception.Message)) -- fixed-name snapshots are unaffected."
}

# ---------------------------------------------------------------------------
# Step 3: MCP endpoint check via the official MCP Inspector CLI (npx, MIT)
# ---------------------------------------------------------------------------

$mcpUrl = "$base/mcp"
$mcpRan = $false
$mcpExitCode = $null
$mcpToolNames = @()
$mcpNote = ""
# The npx Inspector CLI has no way to attach an X-JustSearch-Session header, so
# once the head arms session-token enforcement the inspector path is blocked by
# construction (round-10 retrospective item 4). Record that explicitly rather
# than as a generic "MCP failed", and fall back to the raw probe in Step 3.1.
$mcpBlockedBySession = $false
$mcpInspectorOutput = ""
$mcpOutFile = Join-Path -Path $EvidenceDir -ChildPath "mcp-tools-list.json"

$npxCommand = Get-Command "npx" -ErrorAction SilentlyContinue

if ($npxCommand -eq $null) {
    # Self-repair before declaring Node absent: a clean-environment race (Node
    # installed mid-session by a PATH-mutating MSI, but this PowerShell process
    # started before that MSI ran) leaves node.exe genuinely ON DISK while this
    # session's PATH is stale. A prior smoke round hit exactly this and lost
    # all /mcp coverage to a false "Node.js may not be installed" diagnostic
    # (tempdoc 727-followup). Probe well-known install dirs plus the MACHINE
    # (not just session) PATH, and repair the session PATH if found, before
    # concluding Node is missing.
    $repaired = $false
    $wellKnownNodeDirs = @("C:\Program Files\nodejs", "C:\Program Files (x86)\nodejs")
    foreach ($dir in $wellKnownNodeDirs) {
        if ((Test-Path -LiteralPath (Join-Path $dir "node.exe")) -and ($env:Path -notlike "*$dir*")) {
            $env:Path = "$env:Path;$dir"
            Write-Log "Found node.exe at $dir (not on session PATH) -- prepending to session PATH."
            $repaired = $true
        }
    }
    $machinePath = [Environment]::GetEnvironmentVariable("Path", "Machine")
    if ($machinePath) {
        foreach ($segment in $machinePath -split ";") {
            if ($segment -and ($segment -like "*nodejs*") -and (Test-Path -LiteralPath (Join-Path $segment "node.exe") -ErrorAction SilentlyContinue) -and ($env:Path -notlike "*$segment*")) {
                $env:Path = "$env:Path;$segment"
                Write-Log "Found node.exe via machine PATH at $segment (not on session PATH) -- prepending to session PATH."
                $repaired = $true
            }
        }
    }

    if ($repaired) {
        $npxCommand = Get-Command "npx" -ErrorAction SilentlyContinue
    }
}

if ($npxCommand -eq $null) {
    $nodeOnDisk = (Test-Path -LiteralPath "C:\Program Files\nodejs\node.exe") -or (Test-Path -LiteralPath "C:\Program Files (x86)\nodejs\node.exe")
    if ($nodeOnDisk) {
        $note = "MCP Inspector CLI was NOT exercised: npx exists on disk but was not on this " +
                "session's PATH (session likely started before Node install) -- PATH repair was " +
                "attempted but did not resolve npx. This is a harness/environment-timing gap, not " +
                "evidence that Node.js is missing."
    }
    else {
        $note = "MCP Inspector CLI was NOT exercised: 'npx' was not found on PATH in this sandbox, " +
                "and node.exe was not found in well-known install dirs either. Node.js/npm may " +
                "genuinely not be installed. This is a recorded gap, not a fatal error."
    }
    Write-Log $note
    $note | Write-Utf8NoBom -Path $mcpOutFile
}
else {
    Write-Log "Running MCP Inspector CLI against $mcpUrl"
    $inspectorArgs = @(
        "@modelcontextprotocol/inspector",
        "--cli",
        $mcpUrl,
        "--transport",
        "http",
        "--method",
        "tools/list"
    )

    try {
        $stdoutFile = [System.IO.Path]::GetTempFileName()
        $stderrFile = [System.IO.Path]::GetTempFileName()

        # 'npx' is a shell shim (npx.cmd), not a real PE. Start-Process -FilePath goes
        # straight to Win32 CreateProcess, which cannot execute an extensionless shim --
        # that always fails with "%1 is not a valid Win32 application" even when Node is
        # installed correctly (see 08-collect-evidence-npx-bug.md). Resolve the real
        # dispatchable form: prefer npx.cmd directly; fall back to cmd.exe /c npx.
        $npxCmdCommand = Get-Command "npx.cmd" -ErrorAction SilentlyContinue
        if ($npxCmdCommand -ne $null) {
            $process = Start-Process -FilePath $npxCmdCommand.Source -ArgumentList $inspectorArgs `
                -NoNewWindow -Wait -PassThru `
                -RedirectStandardOutput $stdoutFile -RedirectStandardError $stderrFile
        }
        else {
            Write-Log "npx.cmd not found on PATH; falling back to 'cmd.exe /c npx'."
            $cmdArgs = @("/c", "npx") + $inspectorArgs
            $process = Start-Process -FilePath "cmd.exe" -ArgumentList $cmdArgs `
                -NoNewWindow -Wait -PassThru `
                -RedirectStandardOutput $stdoutFile -RedirectStandardError $stderrFile
        }

        $mcpExitCode = $process.ExitCode
        $stdoutContent = Get-Content -LiteralPath $stdoutFile -Raw -ErrorAction SilentlyContinue
        $stderrContent = Get-Content -LiteralPath $stderrFile -Raw -ErrorAction SilentlyContinue
        Remove-Item -LiteralPath $stdoutFile -Force -ErrorAction SilentlyContinue
        Remove-Item -LiteralPath $stderrFile -Force -ErrorAction SilentlyContinue

        # Success criterion (734 follow-up, live-verified): the Inspector CLI
        # returns complete, valid tools/list JSON on stdout and THEN crashes in
        # Node teardown on Windows (libuv assertion
        # "!(handle->flags & UV_HANDLE_CLOSING)", exit 0xC0000409) -- a nonzero
        # exit with a fully successful call. Judge success from stdout content,
        # not the exit code; still log the exit code and flag the known-benign
        # crash separately. A genuine failure (no/invalid stdout, no tools[])
        # still reads as a failure.
        $isJson = $false
        $parsedJson = $null
        if ($stdoutContent -ne $null) {
            $trimmed = $stdoutContent.Trim()
            if ($trimmed.StartsWith("{") -or $trimmed.StartsWith("[")) {
                try {
                    $parsedJson = $trimmed | ConvertFrom-Json -ErrorAction Stop
                    $isJson = $true
                }
                catch {
                    $isJson = $false
                }
            }
        }

        $mcpSuccess = $false
        if ($isJson -and $parsedJson -ne $null -and ($parsedJson.PSObject.Properties.Name -contains "tools")) {
            $toolsArray = @($parsedJson.tools)
            if ($toolsArray.Count -gt 0) {
                $mcpSuccess = $true
                $mcpToolNames = $toolsArray | ForEach-Object { $_.name }
            }
        }

        $mcpInspectorOutput = "$stdoutContent`r`n$stderrContent"
        if (-not $mcpSuccess) {
            # A 401 does not necessarily SAY "401": Inspector 2.x reacts to an
            # unauthorized response by starting an interactive OAuth flow and
            # dying with {"error":{"code":"auth_required", ... requires a TTY}}
            # (reproduced live against a 401-returning endpoint). Match both the
            # bare-401 and the OAuth-fallout shapes, or this branch never fires
            # for the exact case it exists to name.
            $blockedPatterns = @('\b401\b', 'Unauthorized', 'auth_required', 'Interactive OAuth', 'stored-auth-only')
            foreach ($pattern in $blockedPatterns) {
                if ($mcpInspectorOutput -match $pattern) {
                    $mcpBlockedBySession = $true
                }
            }
            if ($mcpBlockedBySession) {
                $mcpNote = "MCP requires session token; inspector path blocked -- the npx Inspector CLI (--cli) " +
                    "cannot attach the X-JustSearch-Session header the head demands in prod mode, so its failure " +
                    "(a bare 401, or Inspector 2.x's 'auth_required'/interactive-OAuth fallout from one) is a " +
                    "HARNESS limitation, not evidence that /mcp is broken. Reachability is probed directly in Step 3.1."
                Write-Log $mcpNote
            }
        }

        if ($mcpSuccess -and $mcpExitCode -ne 0) {
            $mcpNote = "Note: process exited nonzero (exit code $mcpExitCode) AFTER returning valid tools/list JSON on stdout -- this is the known-benign Node teardown crash on Windows (libuv assertion '!(handle->flags & UV_HANDLE_CLOSING)', exit 0xC0000409), not a real failure. Judged SUCCESS from stdout content, per the 734 follow-up fix."
            Write-Log $mcpNote
        }

        $toolNamesText = if ($mcpToolNames.Count -gt 0) { ($mcpToolNames -join ", ") } else { "(none)" }
        $combined = "exit code: $mcpExitCode`r`n--- stdout ---`r`n$stdoutContent`r`n--- stderr ---`r`n$stderrContent"
        if ($mcpSuccess) {
            $combined = "SUCCESS -- tools discovered: $toolNamesText`r`n`r`n$combined"
        }
        if ($mcpNote -ne "") {
            $combined = "$mcpNote`r`n`r`n$combined"
        }

        if ($isJson) {
            $mcpOutFile = Join-Path -Path $EvidenceDir -ChildPath "mcp-tools-list.json"
            $combined | Write-Utf8NoBom -Path $mcpOutFile
        }
        else {
            $mcpOutFile = Join-Path -Path $EvidenceDir -ChildPath "mcp-tools-list.txt"
            $combined | Write-Utf8NoBom -Path $mcpOutFile
        }

        $mcpRan = $mcpSuccess
        Write-Log "MCP Inspector CLI finished with exit code $mcpExitCode; success=$mcpSuccess (judged from stdout); output saved to $(Split-Path -Leaf $mcpOutFile)"
    }
    catch {
        $exceptionMessage = $_.Exception.Message
        $noteLines = @()
        $noteLines += "MCP Inspector CLI invocation failed: $exceptionMessage"
        if ($exceptionMessage -like "*is not a valid Win32 application*") {
            $noteLines += "Hint: '%1 is not a valid Win32 application' is the known npx-shim launch bug -- the launch pattern regressed, see 08-collect-evidence-npx-bug.md. This is a harness bug, not an environment gap."
        }
        else {
            $noteLines += "Hint: if the error above is '%1 is not a valid Win32 application', the launch pattern regressed -- see 08-collect-evidence-npx-bug.md. Otherwise this looks like a genuine environment gap (Node/npm/network), not that known harness bug."
        }
        $note = $noteLines -join "`r`n"
        Write-Log $note
        $note | Write-Utf8NoBom -Path $mcpOutFile
        $mcpRan = $false
    }
}

# ---------------------------------------------------------------------------
# Step 3.1: raw POST /mcp reachability probe (round-10 retrospective item 4)
# ---------------------------------------------------------------------------
# When the Inspector CLI could not speak to /mcp -- whether because it is
# blocked by session-token enforcement (the header it cannot send) or because
# npx was unavailable -- the round still needs to know whether /mcp is REACHABLE
# and whether the token opens it. This raw JSON-RPC `initialize` POST carries
# the X-JustSearch-Session header the inspector cannot, so a 401 here (unlike
# the inspector's) is a real product finding, not a harness limitation.

$mcpProbeStatus = $null
$mcpProbeRan = $false
$mcpProbeNote = ""
$mcpProbeFile = Join-Path -Path $EvidenceDir -ChildPath "mcp-raw-probe.txt"

if (-not $mcpRan) {
    $mcpProbeRan = $true
    $mcpProbeHeaders = @{ "Accept" = "application/json, text/event-stream" }
    if ($sessionToken -ne "") {
        $mcpProbeHeaders["X-JustSearch-Session"] = $sessionToken
    }
    $mcpProbeBody = '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"collect-evidence","version":"1.0"}}}'
    Write-Log "Raw POST $mcpUrl (initialize; session header: $(if ($sessionToken -ne '') { 'sent' } else { 'NOT sent' }))"
    $mcpProbeResult = Invoke-ApiRequest -Url $mcpUrl -Method "POST" -Body $mcpProbeBody -Headers $mcpProbeHeaders -TimeoutSec 20
    $mcpProbeStatus = $mcpProbeResult.StatusCode

    if ($mcpProbeResult.Ok) {
        $mcpProbeNote = "REACHABLE: raw POST /mcp initialize returned $($mcpProbeResult.StatusCode) with the session header attached."
    }
    elseif ($mcpProbeResult.StatusCode -eq 401) {
        $mcpProbeNote = "FAIL: raw POST /mcp initialize returned 401 EVEN WITH the session header" +
            "$(if ($sessionToken -eq '') { ' (no token could be obtained, so the header was absent)' } else { '' })" +
            " -- /mcp is not usable this round. $sessionTokenNote"
    }
    else {
        $statusText = "no response"
        if ($mcpProbeResult.StatusCode -ne $null) {
            $statusText = "HTTP $($mcpProbeResult.StatusCode)"
        }
        $mcpProbeNote = "FAIL: raw POST /mcp initialize did not succeed ($statusText; $($mcpProbeResult.Error))."
    }
    Write-Log $mcpProbeNote

    $probeLines = @(
        $mcpProbeNote,
        "",
        "url: $mcpUrl",
        "method: POST (jsonrpc initialize)",
        "session header sent: $($sessionToken -ne '')",
        "inspector blocked by session enforcement: $mcpBlockedBySession",
        "status code: $(if ($mcpProbeStatus -eq $null) { 'none' } else { $mcpProbeStatus })",
        "exception: $($mcpProbeResult.Error)",
        "--- response body ---",
        $mcpProbeResult.Body
    )
    ($probeLines -join "`r`n") | Write-Utf8NoBom -Path $mcpProbeFile
}

# ---------------------------------------------------------------------------
# Step 3.5: Persist the request-trace file past sandbox teardown (F4)
# ---------------------------------------------------------------------------
# traces.ndjson lives in the ephemeral sandbox data dir and is wiped on
# shutdown; copy it into the (mapped, host-persisted) evidence dir so the
# host-side finalize coverage check (check_coverage.py --traces) can read it
# after the VM closes. Requires JUSTSEARCH_HEAD_TRACING_LEVEL=detailed to have
# been set before the app launched (the .wsb LogonCommand does this).

$tracesSrc = Join-Path -Path $DataDir -ChildPath "telemetry\traces.ndjson"
$tracesDst = Join-Path -Path $EvidenceDir -ChildPath "traces.ndjson"
$tracesCopied = $false
if (Test-Path -LiteralPath $tracesSrc) {
    try {
        # Round-15 finding 5 (tempdoc 817, F1 renamed-aside-data-dir
        # reproduction): renaming %APPDATA%\io.justsearch.shell aside and
        # relaunching against a pristine data dir resets traces.ndjson to a
        # fresh, near-empty file. An unconditional overwrite here silently
        # replaced a round's real ~7-minute trace record with the pristine
        # instance's short one, and the finalize coverage check then reported
        # four false "uncovered" items because the real spans were gone.
        # Before overwriting, compare the two files' EARLIEST span timestamp:
        # if the existing evidence-root copy's first span predates the new
        # source file's first span, the existing copy carries history the new
        # file does not have, so archive it under evidence\api-history\
        # instead of losing it. Deliberately minimal: no merge, no dedup --
        # just don't destroy the older record.
        if (Test-Path -LiteralPath $tracesDst) {
            $existingFirstSpan = Get-FirstSpanStartUtc -Path $tracesDst
            $newFirstSpan = Get-FirstSpanStartUtc -Path $tracesSrc
            if ($existingFirstSpan -and $newFirstSpan -and ($existingFirstSpan -lt $newFirstSpan)) {
                $tracesArchiveDir = Join-Path -Path $EvidenceDir -ChildPath "api-history"
                if (-not (Test-Path -LiteralPath $tracesArchiveDir)) {
                    New-Item -ItemType Directory -Path $tracesArchiveDir -Force | Out-Null
                }
                $tracesArchiveStamp = (Get-Date).ToUniversalTime().ToString("yyyyMMddTHHmmssZ")
                $tracesArchivePath = Join-Path -Path $tracesArchiveDir -ChildPath "traces-preempted-$tracesArchiveStamp.ndjson"
                Copy-Item -LiteralPath $tracesDst -Destination $tracesArchivePath -Force -ErrorAction Stop
                Write-Log ("Existing traces.ndjson (first span $($existingFirstSpan.ToString('o'))) predates the " +
                    "new source's first span ($($newFirstSpan.ToString('o'))) -- archived the existing copy to " +
                    "$tracesArchivePath before overwriting, so the earlier trace history is not lost. " +
                    "check_coverage.py should be run against the UNION of traces.ndjson and evidence\api-history\*.ndjson.")
            }
        }
        Copy-Item -LiteralPath $tracesSrc -Destination $tracesDst -Force -ErrorAction Stop
        $tracesCopied = $true
        Write-Log "Copied traces.ndjson into evidence dir (for host-side coverage check)."
    }
    catch {
        Write-Log "Failed to copy traces.ndjson: $($_.Exception.Message)"
    }
}
else {
    Write-Log "traces.ndjson not found at $tracesSrc -- was JUSTSEARCH_HEAD_TRACING_LEVEL=detailed set before launch?"
}

# ---------------------------------------------------------------------------
# Step 3.6: Persist the service logs past sandbox teardown (tempdoc 750)
# ---------------------------------------------------------------------------
# Same reason as traces.ndjson above, and the same failure already bit us: the
# logs live in the ephemeral data dir and die with the VM. Round 4's logs exist
# only because a human hand-copied them; rounds 5 and 6 archived none, and a
# later host-side investigation into a real search-quality finding had to fall
# back to round 4's -- a round's own logs are the single richest attribution
# artifact it produces, and it was throwing them away. (Tempdoc 750 P1, "fail
# with attribution": a failure must carry enough evidence for a cheaper tier to
# root-cause it. That is worth nothing if the evidence is deleted at teardown.)
# Best-effort per file: a missing/locked log is logged, never fatal.

$logsSrcDir = Join-Path -Path $DataDir -ChildPath "logs"
$logsDstDir = Join-Path -Path $EvidenceDir -ChildPath "logs"
$logsCopiedCount = 0
if (Test-Path -LiteralPath $logsSrcDir) {
    if (-not (Test-Path -LiteralPath $logsDstDir)) {
        New-Item -ItemType Directory -Path $logsDstDir -Force | Out-Null
    }
    foreach ($logFile in Get-ChildItem -LiteralPath $logsSrcDir -File -ErrorAction SilentlyContinue) {
        try {
            Copy-Item -LiteralPath $logFile.FullName `
                -Destination (Join-Path -Path $logsDstDir -ChildPath $logFile.Name) `
                -Force -ErrorAction Stop
            $logsCopiedCount++
        }
        catch {
            Write-Log "Failed to copy log $($logFile.Name): $($_.Exception.Message)"
        }
    }
    Write-Log "Copied $logsCopiedCount log file(s) into evidence dir (survive teardown for host-side attribution)."
}
else {
    Write-Log "logs dir not found at $logsSrcDir -- no service logs archived this round."
}

# ---------------------------------------------------------------------------
# Step 3.7: Golden-query search-parity capture (capture-only, no judgment)
# ---------------------------------------------------------------------------
# Parity-with-dev search-quality harness (owner design): if a golden query set
# is staged next to this script, POST each query to /api/knowledge/search
# (hybrid, limit 10) and save the raw response under evidence/golden/<queryId>.json.
# This is capture-only -- the tolerance judgment (does the installed candidate's
# retrieval still match the dev-generated golden baseline for this build/corpus?)
# happens host-side at finalize via check_golden_parity.py. A missing queries
# file is a recorded gap, not a fatal error, matching the rest of this harness's
# capture-only philosophy.

$goldenQueriesPath = Join-Path -Path $PSScriptRoot -ChildPath "golden-queries.json"
$goldenDir = Join-Path -Path $EvidenceDir -ChildPath "golden"
$goldenCapturedCount = 0
$goldenFailedCount = 0
$goldenSkipped = $false
$goldenSkipReason = $null

# Per-leg capture (tempdoc 750 Part A): alongside the hybrid capture above,
# also capture each golden query once per retrieval-leg-only mode so the
# host-side checker can see what each leg alone would have surfaced,
# independent of hybrid fusion. Saved as golden/<id>.<mode>.json.
$goldenLegModes = @("vector", "text", "splade")
$goldenLegCapturedCount = 0
$goldenLegFailedCount = 0

# Corpus-sanity pre-check (tempdoc 729-followup): the docs tell a round to
# "run it early", which — unguarded — measures search quality against a
# nearly-empty index and poisons the parity evidence with a false regression
# signal. Mirrors the SAME baseline (golden-parity.json's `indexedDocuments`)
# and the SAME 50% floor check_golden_parity.py already enforces host-side
# (MIN_CORPUS_RATIO in check_golden_parity.py) so "run it early" becomes safe
# by construction instead of conditionally wrong: if the live docCount isn't
# there yet, this step auto-skips loudly rather than silently capturing junk.
# Only runs when a baseline is staged AND Step 2 already captured a live
# /api/knowledge/status snapshot (evidence/api-api-knowledge-status.json) --
# with neither signal available, the capture proceeds uncompared, same as
# before this change.
$goldenParityBaselinePath = Join-Path -Path $PSScriptRoot -ChildPath "golden-parity.json"
if ((Test-Path -LiteralPath $goldenQueriesPath) -and (Test-Path -LiteralPath $goldenParityBaselinePath)) {
    try {
        $baselineRaw = Get-Content -LiteralPath $goldenParityBaselinePath -Raw -ErrorAction Stop
        $baselineDoc = $baselineRaw | ConvertFrom-Json -ErrorAction Stop
        $baselineDocs = $baselineDoc.indexedDocuments

        $knowledgeStatusPath = Join-Path -Path $EvidenceDir -ChildPath "api-api-knowledge-status.json"
        $liveDocs = $null
        if (Test-Path -LiteralPath $knowledgeStatusPath) {
            $liveRaw = Get-Content -LiteralPath $knowledgeStatusPath -Raw -ErrorAction Stop
            $liveDoc = $liveRaw | ConvertFrom-Json -ErrorAction Stop
            $liveDocs = $liveDoc.indexedDocuments
        }

        if ($baselineDocs -and ([double]$baselineDocs -gt 0) -and ($liveDocs -ne $null)) {
            $ratio = [double]$liveDocs / [double]$baselineDocs
            if ($ratio -lt 0.5) {
                $goldenSkipReason = "Golden-query capture AUTO-SKIPPED: live indexedDocuments ($liveDocs) is only " +
                    "$([math]::Round($ratio * 100, 1))% of the baseline's ($baselineDocs) -- below the 50% corpus-sanity " +
                    "floor check_golden_parity.py enforces host-side (MIN_CORPUS_RATIO). Capturing now, against a " +
                    "not-yet-caught-up index, would poison the parity evidence with a false regression signal. " +
                    "Re-run collect-evidence.ps1 once ingestion has caught up to get a real capture."
            }
        }
        elseif ($baselineDocs -and ([double]$baselineDocs -gt 0) -and ($liveDocs -eq $null)) {
            Write-Log "Golden corpus-sanity pre-check: could not read live indexedDocuments from $knowledgeStatusPath -- proceeding with capture uncompared."
        }
    }
    catch {
        Write-Log "Golden corpus-sanity pre-check FAILED to parse baseline/live docCount ($($_.Exception.Message)) -- proceeding with capture uncompared."
    }
}

# Embedding-EP warm-session gate (round 16, tempdoc 823 section 3.1). Round 16's
# parity check exited 1 on three queries losing golden #1 -- and the round's
# own EP record explained it: at capture time
# /api/ai/runtime/status's embed feature read executionProvider="cpu",
# gpuFallback=true, fallbackReason="GPU session not yet initialized (lazy)"
# (OrtCudaStatus.pending -- the initial state of every GPU-capable encoder
# until the first inference batch). The query vectors were CPU-FP32 against a
# GPU-FP16 baseline; the dense leg alone collapsed while SPLADE/text stayed
# high, with shared-pair score deltas ~0.06-0.20 against a calibrated
# sandbox<->sandbox dense envelope of 1.8e-4. That is an instrument artifact,
# not a ranking regression -- and it is exactly what the observed-EP fields
# shipped for. So: before capturing, require the embed session to be warm on
# CUDA; if it is not, TRIGGER it (a vector-mode search runs the query encoder,
# which is what promotes pending -> ready) and poll briefly. Still cold after
# the budget => auto-skip with a note, the same mechanism as the corpus-ratio
# pre-check above, rather than capturing evidence that will be misread as a
# search-quality regression. The observed EP state is written to
# golden-capture-ep.json either way, so finalize can always see the condition
# the capture was taken under.
$goldenEpMaxWaitSec = 180
$goldenEpPollIntervalSec = 10
$goldenEpObserved = New-Object PSObject -Property @{
    executionProvider = "unknown"
    gpuFallback       = $null
    fallbackReason    = $null
    status            = $null
    modelActive       = $null
    warm              = $false
    readError         = ""
}
$goldenEpAttempts = 0
$goldenEpWarmupTriggered = $false
$goldenEpElapsedSec = 0
# Which condition the capture (or skip) happened under, recorded in golden-capture-ep.json so
# finalize never has to infer it: gpu-warm | cpu-native | skipped-cold-gpu | ep-unknown |
# skipped-no-golden-queries | unknown (EP gate never ran, e.g. an earlier pre-check skipped).
$goldenCaptureCondition = "unknown"

function Get-EmbedEpState {
    # Reads the embed feature out of GET /api/ai/runtime/status. NOTE the
    # shape: onnxFeatures is an ARRAY of feature records keyed by `id`, not an
    # object with an `embed` property -- reading it as `.onnxFeatures.embed`
    # yields $null on every round and would make this gate silently inert.
    param([Parameter(Mandatory = $true)][string]$BaseUrl)
    $result = New-Object PSObject -Property @{
        executionProvider = "unknown"
        gpuFallback       = $null
        fallbackReason    = $null
        status            = $null
        modelActive       = $null
        warm              = $false
        readError         = ""
    }
    $response = Invoke-ApiRequest -Url "$BaseUrl/api/ai/runtime/status" -Method "GET"
    if (-not $response.Ok) {
        $result.readError = "GET /api/ai/runtime/status -> $($response.StatusCode) $($response.Error)"
        return $result
    }
    try {
        $doc = $response.Body | ConvertFrom-Json -ErrorAction Stop
    }
    catch {
        $result.readError = "could not parse /api/ai/runtime/status body: $($_.Exception.Message)"
        return $result
    }
    $embed = @($doc.onnxFeatures) | Where-Object { $_.id -eq "embed" } | Select-Object -First 1
    if (-not $embed) {
        $result.readError = "no onnxFeatures entry with id='embed' in /api/ai/runtime/status"
        return $result
    }
    $result.executionProvider = $embed.executionProvider
    $result.gpuFallback = $embed.gpuFallback
    $result.fallbackReason = $embed.fallbackReason
    $result.status = $embed.status
    $result.modelActive = $embed.modelActive
    $result.warm = (($embed.executionProvider -eq "cuda") -and ($embed.gpuFallback -ne $true))
    return $result
}

if ((Test-Path -LiteralPath $goldenQueriesPath) -and (-not $goldenSkipReason)) {
    $epStopwatch = [System.Diagnostics.Stopwatch]::StartNew()
    $goldenEpObserved = Get-EmbedEpState -BaseUrl $base
    $goldenEpAttempts = 1
    if ($goldenEpObserved.readError -ne "") {
        $goldenCaptureCondition = "ep-unknown"
        Write-Log ("Golden EP pre-check: could not read the embed execution provider " +
            "($($goldenEpObserved.readError)) -- proceeding with capture, EP recorded as unknown.")
    }
    elseif ($goldenEpObserved.warm) {
        $goldenCaptureCondition = "gpu-warm"
        Write-Log "Golden EP pre-check: embed executionProvider=cuda, gpuFallback=$($goldenEpObserved.gpuFallback) -- warm GPU session, capturing."
    }
    else {
        Write-Log ("Golden EP pre-check: embed executionProvider=$($goldenEpObserved.executionProvider) " +
            "gpuFallback=$($goldenEpObserved.gpuFallback) reason='$($goldenEpObserved.fallbackReason)' -- " +
            "triggering a vector-mode warm-up search and polling up to ${goldenEpMaxWaitSec}s for a warm session.")
        # Warm-up trigger: a vector-mode search runs the query encoder, which
        # is what creates the lazy CUDA session. Uses the same session header
        # the captures below use; a failure here is logged, not fatal (the
        # poll simply runs out and the capture auto-skips with the recorded EP).
        $warmupHeaders = @{}
        if ($sessionToken -ne "") {
            $warmupHeaders["X-JustSearch-Session"] = $sessionToken
        }
        $warmupBody = @{ query = "golden capture embedding warm-up"; limit = 1; mode = "vector" } | ConvertTo-Json
        $warmupResponse = Invoke-ApiRequest -Url "$base/api/knowledge/search" -Method "POST" `
            -Body $warmupBody -Headers $warmupHeaders
        $goldenEpWarmupTriggered = $true
        if (-not $warmupResponse.Ok) {
            Write-Log ("Golden EP warm-up search returned $($warmupResponse.StatusCode) " +
                "$($warmupResponse.Error) -- polling anyway (enrichment/ingest traffic can warm the session too).")
        }
        while (($epStopwatch.Elapsed.TotalSeconds -lt $goldenEpMaxWaitSec) -and (-not $goldenEpObserved.warm)) {
            Start-Sleep -Seconds $goldenEpPollIntervalSec
            $goldenEpObserved = Get-EmbedEpState -BaseUrl $base
            $goldenEpAttempts++
            if ($goldenEpObserved.readError -ne "") {
                Write-Log "Golden EP poll $goldenEpAttempts : read error ($($goldenEpObserved.readError))"
            }
            else {
                Write-Log ("Golden EP poll $goldenEpAttempts : executionProvider=$($goldenEpObserved.executionProvider) " +
                    "gpuFallback=$($goldenEpObserved.gpuFallback)")
            }
        }
        if ($goldenEpObserved.warm) {
            $goldenCaptureCondition = "gpu-warm"
            Write-Log ("Golden EP gate: warm after $([math]::Round($epStopwatch.Elapsed.TotalSeconds, 1))s " +
                "($goldenEpAttempts read(s)) -- capturing.")
        }
        elseif ($goldenEpObserved.readError -ne "") {
            $goldenCaptureCondition = "ep-unknown"
            Write-Log ("Golden EP gate: still could not read the embed EP after " +
                "$([math]::Round($epStopwatch.Elapsed.TotalSeconds, 1))s -- proceeding with capture, EP recorded as unknown.")
        }
        elseif (($goldenEpObserved.executionProvider -eq "cpu") -and ($goldenEpObserved.gpuFallback -ne $true)) {
            # A genuinely CPU-only host, NOT a cold/failed GPU session: the runtime reports cpu with
            # no fallback. Skipping here would mean such a host can never produce a golden capture at
            # all, which is a worse outcome than capturing under a labelled condition -- the round
            # still gets its evidence, and whether it is comparable to the staged baseline is
            # host-side's call (check_golden_parity.py already fails typed when the embedding
            # fingerprint differs, which is exactly the CPU-FP32-vs-GPU-FP16 case). Refuter finding 7,
            # round-16 wave review.
            $goldenCaptureCondition = "cpu-native"
            $goldenCpuNativeNote = "Golden-query capture PROCEEDED under captureCondition 'cpu-native': the embedding " +
                "execution provider read 'cpu' with gpuFallback=$($goldenEpObserved.gpuFallback) " +
                "(reason='$($goldenEpObserved.fallbackReason)') after ${goldenEpMaxWaitSec}s plus a vector-mode " +
                "warm-up search -- i.e. this host has no GPU session to wait for, rather than a cold or failed one. " +
                "The capture is real evidence of this host's behaviour, but it is only comparable to a baseline " +
                "generated on the SAME embedding weights: a CPU round loads FP32 (model.onnx) where a GPU-generated " +
                "baseline used FP16 (model_fp16.onnx). check_golden_parity.py fails typed on that fingerprint " +
                "mismatch host-side; a CPU host needs its own CPU-generated golden baseline. Observed EP state: " +
                "evidence/golden-capture-ep.json."
            Write-Log $goldenCpuNativeNote
            $goldenCpuNativeNote | Write-Utf8NoBom -Path (Join-Path -Path $EvidenceDir -ChildPath "golden-capture-note.txt")
        }
        else {
            $goldenCaptureCondition = "skipped-cold-gpu"
            $goldenSkipReason = "Golden-query capture AUTO-SKIPPED: the embedding execution provider was " +
                "'$($goldenEpObserved.executionProvider)' (gpuFallback=$($goldenEpObserved.gpuFallback), " +
                "reason='$($goldenEpObserved.fallbackReason)') after ${goldenEpMaxWaitSec}s of waiting plus a " +
                "vector-mode warm-up search, not the 'cuda' the golden baseline was generated on. Capturing now " +
                "would compare CPU-FP32 query vectors against a GPU-FP16 baseline and surface as a phantom " +
                "ranking regression on the dense leg (round 16, tempdoc 823 section 3). Re-run collect-evidence.ps1 " +
                "once the GPU embedding session is warm (any real search or enrichment batch warms it) to get " +
                "a comparable capture. Observed EP state: evidence/golden-capture-ep.json."
        }
    }
    $epStopwatch.Stop()
    $goldenEpElapsedSec = [math]::Round($epStopwatch.Elapsed.TotalSeconds, 1)
}

if (-not (Test-Path -LiteralPath $goldenQueriesPath)) {
    $goldenSkipped = $true
    $goldenCaptureCondition = "skipped-no-golden-queries"
    $goldenSkipReason = "Golden-query capture SKIPPED: golden-queries.json not found at $goldenQueriesPath -- " +
            "no per-candidate search-parity baseline was staged for this round. Recorded as a gap, not a fatal error."
    Write-Log $goldenSkipReason
    $goldenSkipReason | Write-Utf8NoBom -Path (Join-Path -Path $EvidenceDir -ChildPath "golden-capture-note.txt")
}
elseif ($goldenSkipReason) {
    $goldenSkipped = $true
    Write-Log $goldenSkipReason
    $goldenSkipReason | Write-Utf8NoBom -Path (Join-Path -Path $EvidenceDir -ChildPath "golden-capture-note.txt")
}
else {
    if (-not (Test-Path -LiteralPath $goldenDir)) {
        New-Item -ItemType Directory -Path $goldenDir -Force | Out-Null
    }

    try {
        $goldenRaw = Get-Content -LiteralPath $goldenQueriesPath -Raw -ErrorAction Stop
        $goldenDoc = $goldenRaw | ConvertFrom-Json -ErrorAction Stop
        # Same mutating surface as the Step-2.5 rung: without the session header
        # every golden capture below is a 401 recorded as a per-query "error".
        $goldenHeaders = @{}
        if ($sessionToken -ne "") {
            $goldenHeaders["X-JustSearch-Session"] = $sessionToken
        }
        $goldenQueries = @($goldenDoc.queries)
        Write-Log "Loaded $($goldenQueries.Count) golden quer$(if ($goldenQueries.Count -eq 1) {'y'} else {'ies'}) from $goldenQueriesPath"

        foreach ($gq in $goldenQueries) {
            $qid = $gq.id
            $qtext = $gq.query
            $outFile = Join-Path -Path $goldenDir -ChildPath "$qid.json"
            $searchUrl = "$base/api/knowledge/search"
            $requestBody = @{ query = $qtext; limit = 10; mode = "hybrid" } | ConvertTo-Json

            try {
                $response = Invoke-WebRequest -Uri $searchUrl -Method Post -Body $requestBody `
                    -ContentType "application/json" -Headers $goldenHeaders -UseBasicParsing -ErrorAction Stop
                $response.Content | Write-Utf8NoBom -Path $outFile
                $goldenCapturedCount++
                Write-Log "  golden $qid -> captured to golden/$qid.json"
            }
            catch {
                $goldenFailedCount++
                $exceptionMessage = $_.Exception.Message
                $errorRecord = New-Object PSObject -Property @{
                    queryId   = $qid
                    query     = $qtext
                    url       = $searchUrl
                    exception = $exceptionMessage
                }
                ($errorRecord | ConvertTo-Json -Depth 5) | Write-Utf8NoBom -Path $outFile
                Write-Log "  golden $qid -> ERROR ($exceptionMessage) recorded to golden/$qid.json"
            }

            foreach ($legMode in $goldenLegModes) {
                $legOutFile = Join-Path -Path $goldenDir -ChildPath "$qid.$legMode.json"
                $legRequestBody = @{ query = $qtext; limit = 10; mode = $legMode } | ConvertTo-Json

                try {
                    $legResponse = Invoke-WebRequest -Uri $searchUrl -Method Post -Body $legRequestBody `
                        -ContentType "application/json" -Headers $goldenHeaders -UseBasicParsing -ErrorAction Stop
                    $legResponse.Content | Write-Utf8NoBom -Path $legOutFile
                    $goldenLegCapturedCount++
                    Write-Log "  golden $qid ($legMode) -> captured to golden/$qid.$legMode.json"
                }
                catch {
                    $goldenLegFailedCount++
                    $legExceptionMessage = $_.Exception.Message
                    $legErrorRecord = New-Object PSObject -Property @{
                        queryId   = $qid
                        query     = $qtext
                        mode      = $legMode
                        url       = $searchUrl
                        exception = $legExceptionMessage
                    }
                    ($legErrorRecord | ConvertTo-Json -Depth 5) | Write-Utf8NoBom -Path $legOutFile
                    Write-Log "  golden $qid ($legMode) -> ERROR ($legExceptionMessage) recorded to golden/$qid.$legMode.json"
                }
            }
        }
    }
    catch {
        $note = "Golden-query capture FAILED to parse $goldenQueriesPath : $($_.Exception.Message)"
        Write-Log $note
        $note | Write-Utf8NoBom -Path (Join-Path -Path $EvidenceDir -ChildPath "golden-capture-note.txt")
    }
}

# The capture CONDITION, recorded next to the captures themselves (round 16,
# tempdoc 823 section 3.1). Written on every run -- warm, cold-and-skipped, or
# unreadable -- so a finalize reader never has to infer from a parity delta
# what execution provider produced the query vectors. Round 16 could only
# explain its own BLOCKING parity exit because a round happened to snapshot
# /api/ai/runtime/status by hand at the right moment.
$goldenEpRecord = New-Object PSObject -Property @{
    schema             = "golden-capture-ep.v1"
    timestamp          = (Get-Date).ToUniversalTime().ToString("o")
    source             = "/api/ai/runtime/status onnxFeatures[id=embed]"
    executionProvider  = $goldenEpObserved.executionProvider
    gpuFallback        = $goldenEpObserved.gpuFallback
    fallbackReason     = $goldenEpObserved.fallbackReason
    featureStatus      = $goldenEpObserved.status
    modelActive        = $goldenEpObserved.modelActive
    warm               = $goldenEpObserved.warm
    captureCondition   = $goldenCaptureCondition
    readError          = $goldenEpObserved.readError
    reads              = $goldenEpAttempts
    warmupTriggered    = $goldenEpWarmupTriggered
    waitedSeconds      = $goldenEpElapsedSec
    maxWaitSeconds     = $goldenEpMaxWaitSec
    captureProceeded   = (-not $goldenSkipped)
    capturedCount      = $goldenCapturedCount
    legCapturedCount   = $goldenLegCapturedCount
    skipReason         = $goldenSkipReason
}
($goldenEpRecord | ConvertTo-Json -Depth 5) |
    Write-Utf8NoBom -Path (Join-Path -Path $EvidenceDir -ChildPath "golden-capture-ep.json")
Write-Log ("Golden capture EP condition recorded to golden-capture-ep.json " +
    "(executionProvider=$($goldenEpObserved.executionProvider), warm=$($goldenEpObserved.warm), " +
    "captureCondition=$goldenCaptureCondition, captured=$(-not $goldenSkipped))")

# ---------------------------------------------------------------------------
# Step 4: Summary
# ---------------------------------------------------------------------------

$summaryPath = Join-Path -Path $EvidenceDir -ChildPath "collect-evidence-summary.txt"
$summaryLines = @()
$summaryLines += "JustSearch Sandbox evidence collection summary"
$summaryLines += "Timestamp: $timestamp"
$summaryLines += "Resolved port: $port (source: $portSource)"
$summaryLines += "Base URL: $base"
$summaryLines += ""
if ($isElevated) {
    $summaryLines += "Elevation self-check: ELEVATED -- round does not reproduce a normal user's environment (can mask permission defects); UAC prompt during install is also structurally unobservable this round (see elevation-check.txt)"
}
else {
    $summaryLines += "Elevation self-check: not elevated -- round reproduces a normal user's environment; UAC prompt during install IS observable this round (see elevation-check.txt)"
}
$summaryLines += ""
$summaryLines += "API sanity ladder:"
foreach ($result in $ladderResults) {
    $statusText = if ($result.StatusCode -eq $null) { "ERROR" } else { $result.StatusCode }
    $okText = if ($result.Ok) { "2xx" } else { "error/non-2xx" }
    $summaryLines += ("  {0,-5} {1,-30} -> {2} ({3}) [{4}]" -f $result.Method, $result.Path, $statusText, $okText, $result.File)
}
$summaryLines += ""
$summaryLines += "Session token: $sessionTokenNote"
if ($mutatingFailReason -ne "") {
    $summaryLines += ""
    $summaryLines += "!!! MUTATING-SURFACE RUNG FAILED !!!"
    $summaryLines += $mutatingFailReason
}
else {
    $summaryLines += "Mutating-surface rung: OK -- POST $mutatingPath returned $($mutatingResult.StatusCode) (the ladder above is not GET-only)."
}
$summaryLines += ""
if ($mcpBlockedBySession) {
    $summaryLines += "MCP requires session token; inspector path blocked (npx Inspector CLI cannot send X-JustSearch-Session) -- see the raw probe below, not the inspector result, for whether /mcp is reachable."
}
if ($mcpRan) {
    $toolNamesSummary = if ($mcpToolNames.Count -gt 0) { ($mcpToolNames -join ", ") } else { "(none)" }
    $summaryLines += "MCP Inspector CLI: SUCCESS (exit code $mcpExitCode; judged from stdout, not exit code; tools: $toolNamesSummary) -> $(Split-Path -Leaf $mcpOutFile)"
    if ($mcpNote -ne "") {
        $summaryLines += "  $mcpNote"
    }
}
else {
    $exitText = if ($mcpExitCode -eq $null) { "not run" } else { "exit code $mcpExitCode" }
    $summaryLines += "MCP Inspector CLI: FAILED/NOT RUN ($exitText; gap recorded) -> $(Split-Path -Leaf $mcpOutFile)"
}
if ($mcpProbeRan) {
    $summaryLines += "MCP raw POST /mcp probe: $mcpProbeNote -> $(Split-Path -Leaf $mcpProbeFile)"
}
$summaryLines += ""
if ($tracesCopied) {
    $summaryLines += "Request traces: COPIED -> traces.ndjson (host-side coverage check input)"
}
else {
    $summaryLines += "Request traces: NOT COPIED (traces.ndjson absent -- tracing may not have been enabled)"
}
$summaryLines += ""
if ($goldenSkipped) {
    $summaryLines += ("Golden capture embedding EP: $($goldenEpObserved.executionProvider) " +
        "(gpuFallback=$($goldenEpObserved.gpuFallback), warm=$($goldenEpObserved.warm), " +
        "captureCondition=$goldenCaptureCondition) -- see golden-capture-ep.json")
    $summaryLines += "Golden-query search-parity capture: SKIPPED ($goldenSkipReason)"
}
else {
    $summaryLines += ("Golden capture embedding EP: $($goldenEpObserved.executionProvider) " +
        "(gpuFallback=$($goldenEpObserved.gpuFallback), warm=$($goldenEpObserved.warm), " +
        "captureCondition=$goldenCaptureCondition) -- see golden-capture-ep.json")
    $summaryLines += "Golden-query search-parity capture: $goldenCapturedCount captured, $goldenFailedCount failed -> golden/ (host-side check_golden_parity.py input)"
    $summaryLines += "Golden-query per-leg capture (vector/text/splade): $goldenLegCapturedCount captured, $goldenLegFailedCount failed -> golden/<id>.<mode>.json"
}

($summaryLines -join "`r`n") | Write-Utf8NoBom -Path $summaryPath

$okCount = ($ladderResults | Where-Object { $_.Ok }).Count
$totalCount = $ladderResults.Count
$mutatingVerdict = "OK"
if ($mutatingFailReason -ne "") {
    $mutatingVerdict = "FAIL"
}
Write-Host "[collect-evidence] Done. Port=$port Ladder=$okCount/$totalCount 2xx MutatingSurface=$mutatingVerdict MCP-ran=$mcpRan Golden=$goldenCapturedCount/$($goldenCapturedCount + $goldenFailedCount) EvidenceDir=$EvidenceDir"
if ($mutatingFailReason -ne "") {
    # Loud on the console too: the round-10 reader took the one-line Done
    # summary at face value. This deliberately does NOT change the exit code --
    # collect-evidence is capture-only by contract (judgment is host-side) --
    # but the failure can no longer hide inside a green-looking ladder count.
    Write-Host "[collect-evidence] $mutatingFailReason"
}

# tempdoc 808 I3: one line per invocation, so a round's sequence of captures is
# recoverable afterwards. ladderOk means EVERY rung (including the POST rung)
# answered 2xx -- the same number the Done line above prints.
Add-CollectRunRecord -EvidenceDirectory $EvidenceDir -Mode $roundMode `
    -BackendReachable $true -LadderOk ($okCount -eq $totalCount) -MutatingProbe $mutatingProbeStatus

exit 0
