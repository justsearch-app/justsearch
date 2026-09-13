#Requires -Version 5.1
# Last touched: retrigger CI after llama-server b8157 upgrade.
[CmdletBinding()]
param(
  # Path to a pre-built NSIS installer (*-setup.exe). If omitted, the newest bundle output is used.
  [string]$SetupExePath,

  # Build the NSIS installer before verifying it (local-friendly).
  [switch]$BuildAndVerify,

  # If set, skips build even when -BuildAndVerify is provided.
  [switch]$SkipBuild,

  # Where to write evidence logs. Relative paths are resolved from repo root.
  [string]$EvidenceDir = "tmp/installer-verify",

  # Override install directory (must NOT contain spaces for robust NSIS /D handling).
  [string]$InstallDir,

  # Keep the install directory after verification (debugging).
  [switch]$KeepInstallDir,

  # Timeout waiting for JUSTSEARCH_API_PORT from the spawned headless backend.
  [int]$PortTimeoutSec = 60,

  # Timeout waiting for the backend to reach "ready" after the port is known.
  # This includes Worker readiness (indexAvailable=true) and /api/health worker.status == "UP".
  [int]$ReadyTimeoutSec = 60,

  # Timeout per HTTP request to /api/status and /api/health.
  [int]$HttpTimeoutSec = 5,

  # If set, capture an EvidenceBundle v1 (API snapshots + diagnostics export) and validate it.
  [switch]$CaptureEvidenceBundle,

  # If set, treat determinism-budget validation failures as fatal (default is warn-only).
  [switch]$EnforceDeterminismBudget,

  # EvidenceBundle v1 scenario slug (used in output path).
  [string]$EvidenceScenario = "installer-nsis-backend",

  # Optional CSV for extra snapshots (e.g. debug,policy,inference,gpu,ui_ready,effective_config).
  [string]$EvidenceInclude = "",

  # Where to write EvidenceBundle v1 output (defaults to <EvidenceDir>/agent-evidence).
  [string]$EvidenceBundleOutRoot,

  # Skip the restart leg (tempdoc 805 G.4 leg 2): kill + relaunch the payload against the SAME
  # data dir and assert the manifest instanceId changes + stale-token rejection (the R11-F2
  # catcher). Runs by default.
  [switch]$SkipRestartLeg,

  # Include the upgrade-arrival leg (tempdoc 805 G.4 leg 3): boot against a checked-in fixture
  # data dir shaped like a v0.1.0 install. OFF by default -- it asserts an install-status field
  # (repairNeeded) that lands in a separate in-flight bundle (W-TRUTH); it must stay dormant
  # until that field exists.
  [switch]$IncludeUpgradeArrival
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

# Ensure HttpClient type is available in Windows PowerShell 5.1
try {
  Add-Type -AssemblyName System.Net.Http
} catch {
  # best-effort; script will fail later with a clearer error if HTTP types are unavailable
}

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = Split-Path -Parent (Split-Path -Parent $scriptDir) # scripts/ci -> scripts -> repo root

# Fallback: if invoked from nested scripts/* dir, detect top-level repo (contains gradlew.bat)
$gradlew = Join-Path -Path $repoRoot -ChildPath "gradlew.bat"
if (-not (Test-Path -LiteralPath $gradlew)) {
  $maybeRoot = Split-Path -Parent $repoRoot
  $fallbackGradlew = Join-Path -Path $maybeRoot -ChildPath "gradlew.bat"
  if (Test-Path -LiteralPath $fallbackGradlew) {
    $repoRoot = $maybeRoot
    $gradlew = $fallbackGradlew
  }
}
if (-not (Test-Path -LiteralPath $gradlew)) {
  throw "Unable to find repo root (gradlew.bat not found). scriptDir=$scriptDir"
}

$detModule = Join-Path -Path $repoRoot -ChildPath "scripts\\test-support\\DeterminismBudget.psm1"
Import-Module -Name $detModule -Force -DisableNameChecking
$det = New-DeterminismBudget -StdoutSentinel "JUSTSEARCH_API_PORT=..." -LogScrapeAllowed 1

function Resolve-PathRelative {
  param([Parameter(Mandatory = $true)][string]$Path)
  if ([string]::IsNullOrWhiteSpace($Path)) { return $null }
  if ([System.IO.Path]::IsPathRooted($Path)) {
    if (Test-Path -LiteralPath $Path) {
      return (Resolve-Path -LiteralPath $Path).Path
    }
    return [System.IO.Path]::GetFullPath($Path)
  }
  $candidate = Join-Path -Path $repoRoot -ChildPath $Path
  if (Test-Path -LiteralPath $candidate) {
    return (Resolve-Path -LiteralPath $candidate).Path
  }
  return $candidate
}

function Invoke-External {
  param(
    [Parameter(Mandatory = $true)][string]$FilePath,
    [Parameter(Mandatory = $false)][string[]]$Arguments
  )
  & $FilePath @Arguments
  if ($LASTEXITCODE -ne 0) {
    throw "Command failed (exit=$LASTEXITCODE): $FilePath $($Arguments -join ' ')"
  }
}

function New-TempDirNoSpaces {
  param([Parameter(Mandatory = $true)][string]$Prefix)
  $base = Join-Path -Path $env:TEMP -ChildPath ($Prefix + "-" + [Guid]::NewGuid().ToString("N"))
  # $env:TEMP should have no spaces; still ensure our final path contains no spaces.
  if ($base -match "\s") {
    $base = Join-Path -Path "C:\\Temp" -ChildPath ($Prefix + "-" + [Guid]::NewGuid().ToString("N"))
  }
  New-Item -ItemType Directory -Force -Path $base | Out-Null
  return $base
}

function Find-NewestFile {
  param(
    [Parameter(Mandatory = $true)][string]$Path,
    [Parameter(Mandatory = $true)][string]$Filter
  )
  $items = Get-ChildItem -LiteralPath $Path -Filter $Filter -File -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending
  return $items | Select-Object -First 1
}

function Assert {
  param([Parameter(Mandatory = $true)][bool]$Condition, [Parameter(Mandatory = $true)][string]$Message)
  if (-not $Condition) { throw $Message }
}

function Get-HttpBody {
  param(
    [Parameter(Mandatory = $true)][System.Net.Http.HttpClient]$Client,
    [Parameter(Mandatory = $true)][string]$Uri
  )
  $resp = $Client.GetAsync($Uri).Result
  $body = $resp.Content.ReadAsStringAsync().Result
  return [pscustomobject]@{ StatusCode = [int]$resp.StatusCode; Body = $body; Headers = $resp.Headers }
}

function Send-Options {
  param(
    [Parameter(Mandatory = $true)][System.Net.Http.HttpClient]$Client,
    [Parameter(Mandatory = $true)][string]$Uri,
    [Parameter(Mandatory = $true)][string]$Origin
  )
  $req = New-Object System.Net.Http.HttpRequestMessage([System.Net.Http.HttpMethod]::Options, $Uri)
  $null = $req.Headers.TryAddWithoutValidation("Origin", $Origin)
  $null = $req.Headers.TryAddWithoutValidation("Access-Control-Request-Method", "GET")
  $resp = $Client.SendAsync($req).Result
  $body = $resp.Content.ReadAsStringAsync().Result
  return [pscustomobject]@{
    StatusCode = [int]$resp.StatusCode
    Body = $body
    Headers = $resp.Headers
  }
}

function Send-JsonPost {
  # POSTs a JSON body and returns the status/body WITHOUT throwing on non-2xx
  # (same HttpClient shape as Get-HttpBody/Send-Options). A 401 has to be
  # ASSERTABLE here -- Invoke-WebRequest's throw-on-non-2xx would turn the
  # token-enforcement assertions into catch-and-hope.
  param(
    [Parameter(Mandatory = $true)][System.Net.Http.HttpClient]$Client,
    [Parameter(Mandatory = $true)][string]$Uri,
    [Parameter(Mandatory = $true)][string]$Json,
    [string]$SessionToken
  )
  $req = New-Object System.Net.Http.HttpRequestMessage([System.Net.Http.HttpMethod]::Post, $Uri)
  $req.Content = New-Object System.Net.Http.StringContent($Json, [System.Text.Encoding]::UTF8, "application/json")
  if (-not [string]::IsNullOrEmpty($SessionToken)) {
    $null = $req.Headers.TryAddWithoutValidation("X-JustSearch-Session", $SessionToken)
  }
  $resp = $Client.SendAsync($req).Result
  $body = $resp.Content.ReadAsStringAsync().Result
  return [pscustomobject]@{ StatusCode = [int]$resp.StatusCode; Body = $body; Headers = $resp.Headers }
}

function Get-HeaderValuesOrEmpty {
  param(
    [Parameter(Mandatory = $true)]$Headers,
    [Parameter(Mandatory = $true)][string]$Name
  )
  try {
    return @($Headers.GetValues($Name))
  } catch {
    return @()
  }
}

function Wait-ForBackendReady {
  param(
    [Parameter(Mandatory = $true)][hashtable]$Det,
    [Parameter(Mandatory = $true)][System.Net.Http.HttpClient]$Client,
    [Parameter(Mandatory = $true)][int]$Port,
    [Parameter(Mandatory = $true)][int]$TimeoutSec,
    [Parameter(Mandatory = $true)][string]$EvidenceFile
  )

  $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSec)
  $lastStatus = $null
  $lastHealth = $null
  $lastStatusJson = $null
  $lastHealthJson = $null
  $lastError = $null

  while ([DateTime]::UtcNow -lt $deadline) {
    # /api/status (retry on transient errors)
    try {
      $lastStatus = Get-HttpBody -Client $Client -Uri ("http://127.0.0.1:$Port/api/status")
    } catch {
      $lastError = $_.Exception.Message
      Add-BackoffSleep -Det $Det -Reason "wait_for_backend_ready" -Ms 250
      continue
    }
    if ($lastStatus.StatusCode -ne 200) {
      Add-BackoffSleep -Det $Det -Reason "wait_for_backend_ready" -Ms 250
      continue
    }
    try {
      $lastStatusJson = $lastStatus.Body | ConvertFrom-Json
    } catch {
      $lastError = $_.Exception.Message
      Add-BackoffSleep -Det $Det -Reason "wait_for_backend_ready" -Ms 250
      continue
    }

    # Fatal conditions (fail fast)
    $ksErr = [string]$lastStatusJson.knowledgeServerStartError
    if (-not [string]::IsNullOrWhiteSpace($ksErr)) {
      Add-Content -LiteralPath $EvidenceFile -Value ("ERROR: /api/status reported knowledgeServerStartError='" + $ksErr + "'")
      Add-Content -LiteralPath $EvidenceFile -Value ("ERROR: Full /api/status body: " + $lastStatus.Body)
      throw "Backend reported knowledgeServerStartError='$ksErr'. Evidence=$EvidenceFile"
    }

    if ($lastStatusJson.indexAvailable -ne $true) {
      Add-BackoffSleep -Det $Det -Reason "wait_for_backend_ready" -Ms 250
      continue
    }

    # indexState moved from the top level into worker.core in the current /api/status shape;
    # read whichever is present (StrictMode-safe) — an absent field is "not knowable yet", not fatal.
    $state = ""
    if ($lastStatusJson.PSObject.Properties['indexState']) {
      $state = [string]$lastStatusJson.indexState
    } elseif ($lastStatusJson.PSObject.Properties['worker'] -and
              $lastStatusJson.worker.PSObject.Properties['core'] -and
              $lastStatusJson.worker.core.PSObject.Properties['indexState']) {
      $state = [string]$lastStatusJson.worker.core.indexState
    }
    if ($state -eq "ERROR") {
      Add-Content -LiteralPath $EvidenceFile -Value ("ERROR: /api/status reported indexState=ERROR. Full body: " + $lastStatus.Body)
      throw "Backend reported indexState=ERROR. Evidence=$EvidenceFile"
    }

    # /api/health (retry on transient errors)
    try {
      $lastHealth = Get-HttpBody -Client $Client -Uri ("http://127.0.0.1:$Port/api/health")
    } catch {
      $lastError = $_.Exception.Message
      Add-BackoffSleep -Det $Det -Reason "wait_for_backend_ready" -Ms 250
      continue
    }
    if ($lastHealth.StatusCode -ne 200) {
      Add-BackoffSleep -Det $Det -Reason "wait_for_backend_ready" -Ms 250
      continue
    }
    try {
      $lastHealthJson = $lastHealth.Body | ConvertFrom-Json
    } catch {
      $lastError = $_.Exception.Message
      Add-BackoffSleep -Det $Det -Reason "wait_for_backend_ready" -Ms 250
      continue
    }
    $workerStatus = ""
    try { 
      # Check components.worker.state (new API format) first, fallback to worker.status (legacy)
      if ($lastHealthJson.components -and $lastHealthJson.components.worker) {
        $workerStatus = [string]$lastHealthJson.components.worker.state
      } else {
        $workerStatus = [string]$lastHealthJson.worker.status
      }
    } catch { $workerStatus = "" }

    # Accept "UP" (legacy), "READY" (transitional), and "LIFECYCLE_STATE_READY" (current
    # lifecycle-enum shape — the value the shipped payload actually reports, 2026-08-04).
    if ($workerStatus -eq "UP" -or $workerStatus -eq "READY" -or $workerStatus -eq "LIFECYCLE_STATE_READY") {
      return [pscustomobject]@{
        StatusRaw = $lastStatus
        StatusJson = $lastStatusJson
        HealthRaw = $lastHealth
        HealthJson = $lastHealthJson
      }
    }

    Add-BackoffSleep -Det $Det -Reason "wait_for_backend_ready" -Ms 250
  }

  Add-Content -LiteralPath $EvidenceFile -Value ("ERROR: Timed out waiting for backend readiness within ${TimeoutSec}s. LastError=" + $lastError)
  if ($lastStatus) { Add-Content -LiteralPath $EvidenceFile -Value ("ERROR: Last /api/status (" + $lastStatus.StatusCode + "): " + $lastStatus.Body) }
  if ($lastHealth) { Add-Content -LiteralPath $EvidenceFile -Value ("ERROR: Last /api/health (" + $lastHealth.StatusCode + "): " + $lastHealth.Body) }
  throw "Timed out waiting for backend readiness within ${TimeoutSec}s. Evidence=$EvidenceFile"
}

function Start-HeadlessBackend {
  # Launches the installed payload's bundled JRE and blocks until the JUSTSEARCH_API_PORT stdout
  # sentinel is observed (or the process exits / the timeout elapses). Factored out of the
  # original inline fresh-boot block so the restart leg (tempdoc 805 G.4 leg 2) can relaunch the
  # SAME payload against the SAME data dir without duplicating the sentinel-parse loop.
  param(
    [Parameter(Mandatory = $true)][string]$JavaBin,
    [Parameter(Mandatory = $true)][string]$HeadlessDir,
    [Parameter(Mandatory = $true)][string[]]$JavaArgs,
    [Parameter(Mandatory = $true)][string]$EvidenceDir,
    [Parameter(Mandatory = $true)][string]$EvidenceFile,
    [Parameter(Mandatory = $true)][hashtable]$Det,
    [Parameter(Mandatory = $true)][int]$PortTimeoutSec,
    [string]$LogTag = "headless-backend"
  )

  $stamp = Get-Date -Format "yyyyMMdd-HHmmss-fff"
  $stdout = Join-Path -Path $EvidenceDir -ChildPath ("$LogTag-$stamp.stdout.log")
  $stderr = Join-Path -Path $EvidenceDir -ChildPath ("$LogTag-$stamp.stderr.log")
  $argString = $JavaArgs -join " "
  Add-Content -LiteralPath $EvidenceFile -Value ("INFO: Starting headless backend: " + $JavaBin + " " + $argString)
  Add-Content -LiteralPath $EvidenceFile -Value ("INFO: Headless stdout -> " + $stdout)
  Add-Content -LiteralPath $EvidenceFile -Value ("INFO: Headless stderr -> " + $stderr)

  $proc = Start-Process `
    -FilePath $JavaBin `
    -WorkingDirectory $HeadlessDir `
    -ArgumentList $JavaArgs `
    -PassThru `
    -NoNewWindow `
    -RedirectStandardOutput $stdout `
    -RedirectStandardError $stderr

  $deadline = [DateTime]::UtcNow.AddSeconds($PortTimeoutSec)
  $port = 0
  while ([DateTime]::UtcNow -lt $deadline) {
    Add-StdoutSentinelParse -Det $Det
    # Scan only the tail to avoid rereading large logs.
    $tail = @()
    if (Test-Path -LiteralPath $stdout) {
      $tail = Get-Content -LiteralPath $stdout -Tail 200 -ErrorAction SilentlyContinue
    }
    foreach ($line in $tail) {
      if ($line -match '^JUSTSEARCH_API_PORT=(\d+)$') {
        $port = [int]$Matches[1]
      }
    }
    if ($port -gt 0) { break }
    if ($proc -and $proc.HasExited) { break }
    Add-BackoffSleep -Det $Det -Reason "wait_for_port_sentinel" -Ms 200
  }

  if ($port -le 0) {
    Add-Content -LiteralPath $EvidenceFile -Value ("ERROR: Did not observe JUSTSEARCH_API_PORT within ${PortTimeoutSec}s.")
    try {
      if (Test-Path -LiteralPath $stdout) {
        Add-Content -LiteralPath $EvidenceFile -Value "---- headless stdout (tail 200) ----"
        Add-Content -LiteralPath $EvidenceFile -Value (Get-Content -LiteralPath $stdout -Tail 200 -ErrorAction SilentlyContinue)
      }
      if (Test-Path -LiteralPath $stderr) {
        Add-Content -LiteralPath $EvidenceFile -Value "---- headless stderr (tail 200) ----"
        Add-Content -LiteralPath $EvidenceFile -Value (Get-Content -LiteralPath $stderr -Tail 200 -ErrorAction SilentlyContinue)
      }
    } catch { }
    # Kill the orphaned process from HERE, before throwing: this process handle is local to
    # this function, so a caller-side `finally` teardown keyed on ITS OWN process variable
    # (which never gets assigned when this function throws before returning) cannot reach it.
    # Losing that cleanup would leak a java.exe on the runner every time this path fires.
    if ($proc -and -not $proc.HasExited) {
      try { & taskkill /PID $proc.Id /T /F | Out-Null } catch {}
      try { $proc.Kill() } catch {}
    }
    throw "Headless backend did not emit JUSTSEARCH_API_PORT within ${PortTimeoutSec}s. Evidence=$EvidenceFile"
  }

  return [pscustomobject]@{
    Process = $proc
    Port = $port
    StdoutPath = $stdout
    StderrPath = $stderr
  }
}

function Stop-HeadlessBackendAndWait {
  # Kills the Head process tree and blocks until the OS reports it exited (used by the restart
  # leg, which needs a clean exit before relaunching against the same data dir -- a still-alive
  # old process would otherwise hold the manifest/port and make the "did instanceId change"
  # assertion meaningless).
  param(
    [Parameter(Mandatory = $true)]$Process,
    [Parameter(Mandatory = $true)][hashtable]$Det,
    [int]$TimeoutSec = 30
  )
  if ($Process -and -not $Process.HasExited) {
    try { & taskkill /PID $Process.Id /T /F | Out-Null } catch {}
    try { $Process.Kill() } catch {}
  }
  $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSec)
  while ($Process -and -not $Process.HasExited -and [DateTime]::UtcNow -lt $deadline) {
    Add-BackoffSleep -Det $Det -Reason "wait_for_headless_exit" -Ms 200
  }
  return ($Process -and $Process.HasExited)
}

$resolvedEvidenceDir = Resolve-PathRelative -Path $EvidenceDir
if (-not $resolvedEvidenceDir) {
  $resolvedEvidenceDir = Join-Path -Path $repoRoot -ChildPath $EvidenceDir
}
New-Item -ItemType Directory -Force -Path $resolvedEvidenceDir | Out-Null

$resolvedEvidenceBundleOutRoot = $null
if ($EvidenceBundleOutRoot) {
  $resolvedEvidenceBundleOutRoot = Resolve-PathRelative -Path $EvidenceBundleOutRoot
} else {
  $resolvedEvidenceBundleOutRoot = Join-Path -Path $resolvedEvidenceDir -ChildPath "agent-evidence"
}

if (-not $SkipBuild.IsPresent -and $BuildAndVerify.IsPresent) {
  Push-Location $repoRoot
  try {
    # Preflight: ensure rustup shims take precedence over any system-installed Rust (e.g., chocolatey GNU toolchain).
    # This avoids Tauri builds accidentally linking with MinGW when MSVC is required.
    $rustupBin = Join-Path -Path $env:USERPROFILE -ChildPath ".cargo\\bin"
    if (Test-Path -LiteralPath $rustupBin) {
      $env:Path = "$rustupBin;$env:Path"
    }

    # Preflight: require MSVC Rust host triple (GNU fails at link time for Tauri on Windows).
    $hostLine = (& rustc -Vv 2>$null | Select-String -Pattern '^host:\s+' | Select-Object -First 1).Line
    if ($hostLine -match '^host:\s*(\S+)\s*$') {
      # NOTE: PowerShell variables are case-insensitive; `$Host` is a built-in read-only variable.
      # Do not use `$host` here.
      $rustHost = $Matches[1]
      if ($rustHost -like '*windows-gnu') {
        throw "Rust host triple is '$rustHost' (GNU). Tauri on Windows requires MSVC. Fix: rustup default stable-x86_64-pc-windows-msvc (and ensure %USERPROFILE%\\.cargo\\bin is first in PATH)."
      }
    }

    # Build NSIS installer (tauri.conf.json now enforces ui-web build + sidecar bundle via hooks).
    # NOTE: dev/CI smoke builds must not require Windows SDK SignTool. Release pipelines sign separately.
    Invoke-External -FilePath "npm" -Arguments @("--prefix", ".\\modules\\shell", "run", "tauri", "--", "build", "--bundles", "nsis", "--no-sign")
  } finally {
    Pop-Location
  }
}

if ($SetupExePath) {
  $SetupExePath = Resolve-PathRelative -Path $SetupExePath
}

if (-not $SetupExePath) {
  $nsisOutDir = Join-Path -Path $repoRoot -ChildPath "modules\\shell\\src-tauri\\target\\release\\bundle\\nsis"
  $newest = Find-NewestFile -Path $nsisOutDir -Filter "*-setup.exe"
  Assert ($null -ne $newest) "No NSIS installer found at $nsisOutDir (expected *-setup.exe). Run with -BuildAndVerify or provide -SetupExePath."
  $SetupExePath = $newest.FullName
}

Assert (Test-Path -LiteralPath $SetupExePath) "Setup exe not found: $SetupExePath"

if (-not $InstallDir) {
  $InstallDir = New-TempDirNoSpaces -Prefix "JustSearch-nsis-install"
} else {
  if ($InstallDir -match "\s") {
    throw "InstallDir must not contain spaces for robust NSIS /D handling: '$InstallDir'"
  }
  New-Item -ItemType Directory -Force -Path $InstallDir | Out-Null
}

$dataDir = New-TempDirNoSpaces -Prefix "JustSearch-installer-data"
$evidenceFile = Join-Path -Path $resolvedEvidenceDir -ChildPath ("verify-installer-nsis-" + (Get-Date -Format "yyyyMMdd-HHmmss") + ".log")
"" | Set-Content -LiteralPath $evidenceFile -Encoding UTF8

$headlessProc = $null
$installedUninstaller = $null
# Pre-initialize so the finally block can read these even if the try
# throws early (Set-StrictMode -Version Latest above would otherwise
# mask the real error with "variable not set" on finally's $port/$client
# access).
$port = 0
$client = $null

$mainError = $null
try {
  Write-Host "Installer: $SetupExePath"
  Write-Host "InstallDir: $InstallDir"
  Write-Host "DataDir:    $dataDir"
  Write-Host "Evidence:   $evidenceFile"

  # ---------------------------------------------------------------------------
  # 1) Silent NSIS install (per-user, no admin). /D must be last argument.
  # ---------------------------------------------------------------------------
  $installArgs = @("/S", "/D=$InstallDir")
  $installProc = Start-Process -FilePath $SetupExePath -ArgumentList $installArgs -Wait -PassThru
  Assert ($installProc.ExitCode -eq 0) "NSIS installer failed (exit=$($installProc.ExitCode)). Evidence=$evidenceFile"

  # ---------------------------------------------------------------------------
  # 2) Locate resources/headless by searching for ui-headless.jar
  # ---------------------------------------------------------------------------
  $uiJar = Get-ChildItem -LiteralPath $InstallDir -Filter "ui-headless.jar" -Recurse -File -ErrorAction SilentlyContinue | Select-Object -First 1
  Assert ($null -ne $uiJar) "Installed payload missing ui-headless.jar under $InstallDir"
  $headlessDir = Split-Path -Parent $uiJar.FullName

  # ---------------------------------------------------------------------------
  # 3) Validate expected sidecar files exist
  # ---------------------------------------------------------------------------
  $javaBin = Join-Path -Path $headlessDir -ChildPath "runtime\\bin\\java.exe"
  # Lane F stage A item A13: ONE classpath. The bundle used to carry the Engine JARs in `lib/` plus
  # a whole second copy of the index half in `lib/worker/` (the Worker distribution, tempdoc 226).
  # The Engine composes the index half in-process, so `lib/` is the only classpath dir now.
  $engineLibDir = Join-Path -Path $headlessDir -ChildPath "lib"
  $configPath = Join-Path -Path $headlessDir -ChildPath "config\\application.yaml"
  $ssotPath = Join-Path -Path $headlessDir -ChildPath "SSOT"
  $manifestPath = Join-Path -Path $ssotPath -ChildPath "manifest.v1.json"
  $pluginsManifest = Join-Path -Path $ssotPath -ChildPath "manifests\\plugins\\pipeline-stage-plugins.v1.json"

  # v1 Simple Mode: bundled llama-server payload must include required DLLs (exe-only fails in Windows Sandbox).
  $llamaDir = Join-Path -Path $headlessDir -ChildPath "native-bin\\llama-server"
  $llamaExe = Join-Path -Path $llamaDir -ChildPath "llama-server.exe"
  Assert (Test-Path -LiteralPath $llamaExe) "Missing bundled llama-server.exe: $llamaExe"
  # NOTE: We bundle the pinned upstream Windows CPU build from ggml-org/llama.cpp (b8157), which ships
  # multiple cpu backend DLLs (ggml-cpu-*.dll), libomp, and requires msvcp140_codecvt_ids.dll.
  # Note: libcurl-x64.dll was removed from upstream releases starting at b8157.
  $requiredFiles = @(
    "llama.dll",
    "ggml.dll",
    "ggml-base.dll",
    "mtmd.dll",
    "libomp140.x86_64.dll",
    "msvcp140_codecvt_ids.dll",
    "runtime-version.txt"
  )
  $missingFiles = @()
  foreach ($f in $requiredFiles) {
    $p = Join-Path -Path $llamaDir -ChildPath $f
    if (-not (Test-Path -LiteralPath $p)) { $missingFiles += $f }
  }
  $cpuBackends = Get-ChildItem -LiteralPath $llamaDir -Filter "ggml-cpu*.dll" -File -ErrorAction SilentlyContinue
  if ($missingFiles.Count -gt 0 -or -not $cpuBackends -or $cpuBackends.Count -lt 1) {
    $present = Get-ChildItem -LiteralPath $llamaDir -File -ErrorAction SilentlyContinue | Sort-Object Name | Select-Object -ExpandProperty Name
    $cpuMsg = if (-not $cpuBackends -or $cpuBackends.Count -lt 1) { " Missing ggml-cpu*.dll backends." } else { "" }
    throw "Bundled llama-server payload incomplete.$cpuMsg Missing: $($missingFiles -join ', '). Present: $($present -join ', '). Dir=$llamaDir"
  }

  Assert (Test-Path -LiteralPath $javaBin) "Missing bundled java runtime: $javaBin"
  Assert (Test-Path -LiteralPath $engineLibDir) "Missing Engine classpath dir in bundle: $engineLibDir"
  $engineJarCount = (Get-ChildItem -LiteralPath $engineLibDir -Filter "*.jar" -File -ErrorAction SilentlyContinue | Measure-Object).Count
  # The pre-A13 lib/worker/ layout produced 176 JARs at 2026-04-24; the merged lib/ is a superset.
  # 50 is a safe floor that still catches a broken bundle while tolerating dependency churn.
  Assert ($engineJarCount -ge 50) "Engine classpath dir has only $engineJarCount JARs at $engineLibDir (expected >= 50)"
  # The index half must be ON that one classpath -- the A13 collapse is only real if these are here.
  foreach ($indexHalfJar in @("indexer-worker-*.jar", "worker-services-*.jar")) {
    $found = Get-ChildItem -LiteralPath $engineLibDir -Filter $indexHalfJar -File -ErrorAction SilentlyContinue
    Assert ($null -ne $found) "Missing $indexHalfJar in $engineLibDir -- the Engine cannot open an index without the index half on its classpath"
  }
  Assert (Test-Path -LiteralPath $configPath) "Missing config/application.yaml in bundle: $configPath"
  Assert (Test-Path -LiteralPath $manifestPath) "Missing SSOT/manifest.v1.json in bundle: $manifestPath"
  # The plugins manifest has NEVER shipped in the NSIS bundle (verified against the round-8,
  # round-10 and round-11 candidates, 2026-08-04) and the packaged shell passes the same
  # dangling path to the Head unconditionally (lib.rs:712-769), which the Head tolerates.
  # Mirror the shipped behavior: warn, don't fail. Whether pipeline-stage plugins SHOULD ship
  # is an open product question tracked in the observations inbox — if they ever do ship,
  # restore this to a hard Assert.
  if (-not (Test-Path -LiteralPath $pluginsManifest)) {
    Write-Host "WARN: plugins manifest absent from bundle (expected today; Head tolerates): $pluginsManifest"
  }

  # Optional: sanity check config keeps AI disabled by default.
  try {
    $cfg = Get-Content -LiteralPath $configPath -Raw -ErrorAction Stop
    if ($cfg -notmatch '(?m)^[\s#]*llm:\s*\r?\n[\s#]*enabled:\s*false\s*$') {
      Add-Content -LiteralPath $evidenceFile -Value "WARN: config/application.yaml did not match expected 'llm.enabled: false' pattern (best-effort check)."
    }
  } catch {
    Add-Content -LiteralPath $evidenceFile -Value "WARN: unable to read config/application.yaml for sanity check: $($_.Exception.Message)"
  }

  # Best-effort locate uninstaller for cleanup.
  $installedUninstaller = Get-ChildItem -LiteralPath $InstallDir -Filter "*uninstall*.exe" -Recurse -File -ErrorAction SilentlyContinue | Select-Object -First 1
  if ($installedUninstaller) {
    Add-Content -LiteralPath $evidenceFile -Value ("INFO: Found uninstaller: " + $installedUninstaller.FullName)
  } else {
    Add-Content -LiteralPath $evidenceFile -Value "WARN: Could not find uninstaller exe under install dir (cleanup will be best-effort)."
  }

  # ---------------------------------------------------------------------------
  # 4) Boot the bundled backend from installed payload and assert readiness
  # ---------------------------------------------------------------------------
  $libDir = Join-Path -Path $headlessDir -ChildPath "lib"
  $libGlob = Join-Path -Path $libDir -ChildPath "*"
  $cp = "$($uiJar.FullName);$libGlob"
  $javaArgs = @(
    "-Djustsearch.prod=true",
    "-Djustsearch.data.dir=$dataDir",
    "-Djustsearch.home=$dataDir",
    "-Djustsearch.ui.settings.mode=IN_MEMORY",
    "-Djustsearch.config=$configPath",
    "-Djustsearch.repo.root=$headlessDir",
    "-Djustsearch.ssot.path=$ssotPath",
    "-Djustsearch.plugins.manifest=$pluginsManifest",
    "-cp",
    $cp,
    "io.justsearch.ui.HeadlessApp"
  )
  $boot = Start-HeadlessBackend -JavaBin $javaBin -HeadlessDir $headlessDir -JavaArgs $javaArgs -EvidenceDir $resolvedEvidenceDir -EvidenceFile $evidenceFile -Det $det -PortTimeoutSec $PortTimeoutSec -LogTag "headless-backend"
  $headlessProc = $boot.Process
  $port = $boot.Port
  $headlessStdout = $boot.StdoutPath
  $headlessStderr = $boot.StderrPath

  Write-Host "Backend port: $port"

  # ---------------------------------------------------------------------------
  # 5) Deterministic readiness assertions
  # ---------------------------------------------------------------------------
  $client = New-Object System.Net.Http.HttpClient
  $client.Timeout = [TimeSpan]::FromSeconds($HttpTimeoutSec)
  $ready = Wait-ForBackendReady -Det $det -Client $client -Port $port -TimeoutSec $ReadyTimeoutSec -EvidenceFile $evidenceFile
  $statusJson = $ready.StatusJson
  $healthJson = $ready.HealthJson

  # Loopback bind check (best effort).
  $listeners = @(Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue)
  Assert ($listeners.Count -gt 0) "Expected a LISTEN socket on port $port (Get-NetTCPConnection returned none)."
  $nonLoopback = @($listeners | Where-Object { $_.LocalAddress -ne "127.0.0.1" -and $_.LocalAddress -ne "::1" })
  Assert ($nonLoopback.Count -eq 0) ("Expected loopback-only bind on port $port, got: " + ($listeners | ForEach-Object { "$($_.LocalAddress):$($_.LocalPort)" } | Sort-Object | Out-String))

  # CORS posture checks (prod mode).
  # Tauri app origins differ by runtime version:
  # - Tauri v1:  tauri://localhost
  # - Tauri v2+: https://tauri.localhost
  foreach ($allowedOrigin in @("tauri://localhost", "https://tauri.localhost", "http://tauri.localhost")) {
    $optAllowed = Send-Options -Client $client -Uri ("http://127.0.0.1:$port/api/status") -Origin $allowedOrigin
    Assert ($optAllowed.StatusCode -ge 200 -and $optAllowed.StatusCode -lt 300) "Expected preflight from $allowedOrigin to succeed, got $($optAllowed.StatusCode). Body=$($optAllowed.Body)"
    $acaosAllowed = @(Get-HeaderValuesOrEmpty -Headers $optAllowed.Headers -Name "Access-Control-Allow-Origin")
    Assert ($acaosAllowed.Count -eq 1 -and $acaosAllowed[0] -eq $allowedOrigin) ("Expected Access-Control-Allow-Origin=$allowedOrigin, got: " + ($acaosAllowed -join ", "))
  }

  foreach ($blockedOrigin in @("http://localhost:5173", "http://127.0.0.1:5173")) {
    $optBrowser = Send-Options -Client $client -Uri ("http://127.0.0.1:$port/api/status") -Origin $blockedOrigin
    Assert ($optBrowser.StatusCode -eq 403) "Expected preflight from $blockedOrigin to be rejected with 403 in prod, got $($optBrowser.StatusCode). Body=$($optBrowser.Body)"
    $acaos = @(Get-HeaderValuesOrEmpty -Headers $optBrowser.Headers -Name "Access-Control-Allow-Origin")
    Assert ($acaos.Count -eq 0) ("Expected no Access-Control-Allow-Origin header for blocked origin $blockedOrigin, got: " + ($acaos -join ", "))
  }

  # ---------------------------------------------------------------------------
  # 6) Session-token enforcement on the MUTATING surface (tempdoc 804 §B4.3)
  # ---------------------------------------------------------------------------
  # Sandbox round 10's F7 class: every readiness/CORS check above is a GET, so a
  # payload whose entire non-GET surface answers 401 still scored green. The
  # installed payload boots with -Djustsearch.prod=true, so HeadlessApp
  # generates a session token and ApiSecurityFilters.setupSessionTokenEnforcement
  # arms 401-on-missing/invalid for POST/PUT/DELETE. Assert all three legs --
  # the deny, the allow, AND the wrong-token deny. Without the third leg a 200
  # in (b) could equally mean enforcement was never armed at all.
  $searchUri = "http://127.0.0.1:$port/api/knowledge/search"
  $searchBody = '{"query":"test","limit":1}'

  # (a) No session header -> 401 (enforcement is really armed).
  $searchNoToken = Send-JsonPost -Client $client -Uri $searchUri -Json $searchBody
  Add-Content -LiteralPath $evidenceFile -Value ("INFO: POST /api/knowledge/search without session header -> " + $searchNoToken.StatusCode)
  Assert ($searchNoToken.StatusCode -eq 401) ("Expected POST /api/knowledge/search WITHOUT a session token to be rejected with 401 in prod mode, got $($searchNoToken.StatusCode). Body=$($searchNoToken.Body)")

  # (b) GET /api/mcp/token, then POST with that token -> 200 (server half of the chain works).
  $tokenResp = Get-HttpBody -Client $client -Uri ("http://127.0.0.1:$port/api/mcp/token")
  Assert ($tokenResp.StatusCode -eq 200) "Expected GET /api/mcp/token to return 200, got $($tokenResp.StatusCode). Body=$($tokenResp.Body)"
  $sessionToken = $null
  try {
    $sessionToken = [string]($tokenResp.Body | ConvertFrom-Json).token
  } catch {
    throw "GET /api/mcp/token returned unparsable JSON: $($tokenResp.Body)"
  }
  Assert (-not [string]::IsNullOrWhiteSpace($sessionToken)) ("Expected GET /api/mcp/token to carry a non-empty token in prod mode (empty means token enforcement is DISABLED). Body=" + $tokenResp.Body)
  Add-Content -LiteralPath $evidenceFile -Value ("INFO: GET /api/mcp/token returned a token of length " + $sessionToken.Length)

  $searchWithToken = Send-JsonPost -Client $client -Uri $searchUri -Json $searchBody -SessionToken $sessionToken
  Add-Content -LiteralPath $evidenceFile -Value ("INFO: POST /api/knowledge/search with the real session token -> " + $searchWithToken.StatusCode)
  Assert ($searchWithToken.StatusCode -eq 200) ("Expected POST /api/knowledge/search WITH the session token from /api/mcp/token to return 200, got $($searchWithToken.StatusCode). Body=$($searchWithToken.Body)")

  # (c) Deliberately wrong token -> 401 (proves (b)'s 200 came from the RIGHT token, not from enforcement being off).
  $wrongToken = "not-the-session-token-0000000000000000000000"
  Assert ($wrongToken -ne $sessionToken) "Wrong-token fixture collided with the real session token -- pick a different fixture."
  $searchWrongToken = Send-JsonPost -Client $client -Uri $searchUri -Json $searchBody -SessionToken $wrongToken
  Add-Content -LiteralPath $evidenceFile -Value ("INFO: POST /api/knowledge/search with a deliberately wrong session token -> " + $searchWrongToken.StatusCode)
  Assert ($searchWrongToken.StatusCode -eq 401) ("Expected POST /api/knowledge/search with a WRONG session token to be rejected with 401, got $($searchWrongToken.StatusCode) -- the 200 above did not depend on the token, i.e. enforcement is not armed. Body=$($searchWrongToken.Body)")

  Add-Content -LiteralPath $evidenceFile -Value "INFO: Session-token enforcement verified on the mutating surface (401 no-token / 200 right-token / 401 wrong-token)."

  Write-Host "PASS: NSIS installer payload boots, backend readiness checks passed, and session-token enforcement holds on the mutating surface."

  # ---------------------------------------------------------------------------
  # 7) Restart leg (tempdoc 805 G.4 leg 2) -- the R11-F2 catcher at the payload tier.
  # ---------------------------------------------------------------------------
  # Round 11's blocker (a stale session token surviving a backend restart) was findable on the
  # host in minutes -- this lane just never restarted the payload. Kill the Head this script
  # started, relaunch the SAME payload against the SAME data dir, and assert: the manifest
  # instanceId changed (a fresh boot identity, not stale residue), a freshly-fetched token is
  # accepted, and the OLD boot's token is now rejected.
  if (-not $SkipRestartLeg.IsPresent) {
    $manifestPath = Join-Path -Path $dataDir -ChildPath "runtime\\manifest.json"
    Assert (Test-Path -LiteralPath $manifestPath) "Restart leg precondition FAILED: expected runtime manifest at $manifestPath before restart."
    $oldManifestJson = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
    $oldInstanceId = [string]$oldManifestJson.instanceId
    Assert (-not [string]::IsNullOrWhiteSpace($oldInstanceId)) "Restart leg precondition FAILED: expected a non-empty top-level instanceId in the manifest before restart. Manifest=$manifestPath"
    $oldSessionToken = $sessionToken
    Add-Content -LiteralPath $evidenceFile -Value ("INFO: Restart leg -- pre-restart instanceId=" + $oldInstanceId)

    $exited = Stop-HeadlessBackendAndWait -Process $headlessProc -Det $det -TimeoutSec 30
    Assert $exited "Restart leg FAILED: the Head process this script started did not exit within 30s after being killed."
    Add-Content -LiteralPath $evidenceFile -Value "INFO: Restart leg -- Head process exited; relaunching against the same data dir."

    $restartBoot = Start-HeadlessBackend -JavaBin $javaBin -HeadlessDir $headlessDir -JavaArgs $javaArgs -EvidenceDir $resolvedEvidenceDir -EvidenceFile $evidenceFile -Det $det -PortTimeoutSec $PortTimeoutSec -LogTag "headless-backend-restart"
    $headlessProc = $restartBoot.Process
    $port = $restartBoot.Port
    $headlessStdout = $restartBoot.StdoutPath
    $headlessStderr = $restartBoot.StderrPath
    Write-Host "Restart leg -- backend port: $port"

    $null = Wait-ForBackendReady -Det $det -Client $client -Port $port -TimeoutSec $ReadyTimeoutSec -EvidenceFile $evidenceFile

    Assert (Test-Path -LiteralPath $manifestPath) "Restart leg FAILED: expected runtime manifest at $manifestPath after restart."
    $newManifestJson = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
    $newInstanceId = [string]$newManifestJson.instanceId
    Assert (-not [string]::IsNullOrWhiteSpace($newInstanceId)) "Restart leg FAILED: expected a non-empty top-level instanceId in the manifest after restart. Manifest=$manifestPath"
    Assert ($newInstanceId -ne $oldInstanceId) "Restart leg FAILED: manifest instanceId did NOT change across restart (old=$oldInstanceId new=$newInstanceId). This is R11-F2's exact precondition -- stale instance identity surviving a restart. Manifest=$manifestPath"
    Add-Content -LiteralPath $evidenceFile -Value ("INFO: Restart leg -- post-restart instanceId=" + $newInstanceId + " (changed from " + $oldInstanceId + ")")

    $tokenResp2 = Get-HttpBody -Client $client -Uri ("http://127.0.0.1:$port/api/mcp/token")
    Assert ($tokenResp2.StatusCode -eq 200) "Restart leg FAILED: GET /api/mcp/token returned $($tokenResp2.StatusCode) after restart, expected 200. Body=$($tokenResp2.Body)"
    $newSessionToken = $null
    try {
      $newSessionToken = [string]($tokenResp2.Body | ConvertFrom-Json).token
    } catch {
      throw "Restart leg FAILED: GET /api/mcp/token returned unparsable JSON after restart: $($tokenResp2.Body)"
    }
    Assert (-not [string]::IsNullOrWhiteSpace($newSessionToken)) "Restart leg FAILED: GET /api/mcp/token returned an empty token after restart (empty means enforcement is DISABLED). Body=$($tokenResp2.Body)"

    $searchWithNewToken = Send-JsonPost -Client $client -Uri $searchUri -Json $searchBody -SessionToken $newSessionToken
    Assert ($searchWithNewToken.StatusCode -eq 200) "Restart leg FAILED: POST /api/knowledge/search with the FRESH post-restart token returned $($searchWithNewToken.StatusCode), expected 200. Body=$($searchWithNewToken.Body)"
    Add-Content -LiteralPath $evidenceFile -Value "INFO: Restart leg -- fresh post-restart token accepted (200)."

    $searchWithOldToken = Send-JsonPost -Client $client -Uri $searchUri -Json $searchBody -SessionToken $oldSessionToken
    Assert ($searchWithOldToken.StatusCode -eq 401) "Restart leg FAILED: POST /api/knowledge/search with the OLD PRE-RESTART token returned $($searchWithOldToken.StatusCode), expected 401 -- this IS round 11's blocker: a stale session token surviving a backend restart. Body=$($searchWithOldToken.Body)"
    Add-Content -LiteralPath $evidenceFile -Value "INFO: Restart leg -- stale pre-restart token correctly rejected (401)."

    $sessionToken = $newSessionToken

    Write-Host "PASS: Restart leg -- manifest instanceId changed across restart, fresh token accepted, stale pre-restart token rejected."
  } else {
    Add-Content -LiteralPath $evidenceFile -Value "INFO: Restart leg skipped (-SkipRestartLeg)."
  }

  # ---------------------------------------------------------------------------
  # 8) Upgrade-arrival leg (tempdoc 805 G.4 leg 3) -- DEFAULT OFF.
  # ---------------------------------------------------------------------------
  # Boots the payload against a checked-in fixture data dir shaped like a v0.1.0 install
  # (scripts/ci/fixtures/upgrade-arrival-v010) and asserts the round-10/11-class regressions
  # don't recur: settings persist instead of 409ing, the install-status shape carries the
  # observed-outcome fields tempdoc 805 W-TRUTH adds, and activation never regresses to
  # MODEL_PATH_REQUIRED for an install the contract actually covers. Dormant by design (see the
  # -IncludeUpgradeArrival param doc) until W-TRUTH lands repairNeeded.
  if ($IncludeUpgradeArrival.IsPresent) {
    $fixtureDir = Join-Path -Path $repoRoot -ChildPath "scripts\\ci\\fixtures\\upgrade-arrival-v010"
    Assert (Test-Path -LiteralPath $fixtureDir) "Upgrade-arrival leg FAILED: fixture directory missing: $fixtureDir"
    $fixtureContract = Join-Path -Path $fixtureDir -ChildPath "install-contract.v2.json"
    Assert (Test-Path -LiteralPath $fixtureContract) "Upgrade-arrival leg FAILED: fixture contract missing: $fixtureContract"

    $upgradeDataDir = New-TempDirNoSpaces -Prefix "JustSearch-upgrade-arrival"
    Copy-Item -LiteralPath $fixtureContract -Destination (Join-Path -Path $upgradeDataDir -ChildPath "install-contract.v2.json") -Force
    Assert (-not (Test-Path -LiteralPath (Join-Path -Path $upgradeDataDir -ChildPath "ui\\settings.json"))) "Upgrade-arrival leg precondition FAILED: a settings file exists in the fresh copy before boot."

    # Deliberately NO -Djustsearch.ui.settings.mode override here (unlike the fresh/restart legs
    # above, which force IN_MEMORY for isolation): the whole point of this leg is that settings
    # persistence behaves the way the packaged shell ACTUALLY launches -- prod=true and nothing
    # else -- so persistence must default to READ_WRITE (tempdoc 804 sec B4.2's round-10 regression).
    $upgradeJavaArgs = @(
      "-Djustsearch.prod=true",
      "-Djustsearch.data.dir=$upgradeDataDir",
      "-Djustsearch.home=$upgradeDataDir",
      "-Djustsearch.config=$configPath",
      "-Djustsearch.repo.root=$headlessDir",
      "-Djustsearch.ssot.path=$ssotPath",
      "-Djustsearch.plugins.manifest=$pluginsManifest",
      "-cp",
      $cp,
      "io.justsearch.ui.HeadlessApp"
    )

    $upgradeProc = $null
    try {
      $upgradeBoot = Start-HeadlessBackend -JavaBin $javaBin -HeadlessDir $headlessDir -JavaArgs $upgradeJavaArgs -EvidenceDir $resolvedEvidenceDir -EvidenceFile $evidenceFile -Det $det -PortTimeoutSec $PortTimeoutSec -LogTag "headless-backend-upgrade-arrival"
      $upgradeProc = $upgradeBoot.Process
      $upgradePort = $upgradeBoot.Port
      Write-Host "Upgrade-arrival leg -- backend port: $upgradePort"

      $null = Wait-ForBackendReady -Det $det -Client $client -Port $upgradePort -TimeoutSec $ReadyTimeoutSec -EvidenceFile $evidenceFile

      $upgradeTokenResp = Get-HttpBody -Client $client -Uri ("http://127.0.0.1:$upgradePort/api/mcp/token")
      Assert ($upgradeTokenResp.StatusCode -eq 200) "Upgrade-arrival leg FAILED: GET /api/mcp/token returned $($upgradeTokenResp.StatusCode), expected 200. Body=$($upgradeTokenResp.Body)"
      $upgradeToken = $null
      try {
        $upgradeToken = [string]($upgradeTokenResp.Body | ConvertFrom-Json).token
      } catch {
        throw "Upgrade-arrival leg FAILED: GET /api/mcp/token returned unparsable JSON: $($upgradeTokenResp.Body)"
      }
      Assert (-not [string]::IsNullOrWhiteSpace($upgradeToken)) "Upgrade-arrival leg FAILED: GET /api/mcp/token returned an empty token. Body=$($upgradeTokenResp.Body)"

      # (1) Settings persist -- tempdoc 804 sec B4.2's exact round-10 regression: prod=true must not
      # silently switch UiSettingsStore to in-memory just because no settings file exists yet.
      $settingsResp = Send-JsonPost -Client $client -Uri ("http://127.0.0.1:$upgradePort/api/settings/v2") -Json "{}" -SessionToken $upgradeToken
      Assert ($settingsResp.StatusCode -eq 200) "Upgrade-arrival leg FAILED: POST /api/settings/v2 on a v0.1.0-shaped data dir (no settings file yet) returned $($settingsResp.StatusCode), expected 200 -- a 409 here means prod=true silently disabled settings persistence again (round 10's regression). Body=$($settingsResp.Body)"
      Add-Content -LiteralPath $evidenceFile -Value "INFO: Upgrade-arrival leg -- POST /api/settings/v2 persisted (200, not 409)."

      # (2) Install-status shape carries the observed-outcome fields tempdoc 805 Part G.3
      # (W-TRUTH) adds. TODO(tempdoc 805 W-TRUTH): once repairNeeded's semantics land, tighten
      # this from a shape-only check to asserting the VALUE this fixture should produce
      # (repair-needed, since the contract's own cuda-runtime entry is recorded skipped -- U3).
      $installStatusResp = Get-HttpBody -Client $client -Uri ("http://127.0.0.1:$upgradePort/api/ai/install/status")
      Assert ($installStatusResp.StatusCode -eq 200) "Upgrade-arrival leg FAILED: GET /api/ai/install/status returned $($installStatusResp.StatusCode), expected 200. Body=$($installStatusResp.Body)"
      $installStatusJson = $installStatusResp.Body | ConvertFrom-Json
      Assert ($null -ne $installStatusJson.PSObject.Properties['installedFully']) "Upgrade-arrival leg FAILED: /api/ai/install/status is missing 'installedFully'. Body=$($installStatusResp.Body)"
      Assert ($null -ne $installStatusJson.PSObject.Properties['pendingRegistryAdditions']) "Upgrade-arrival leg FAILED: /api/ai/install/status is missing 'pendingRegistryAdditions'. Body=$($installStatusResp.Body)"
      Assert ($null -ne $installStatusJson.PSObject.Properties['repairNeeded']) "Upgrade-arrival leg FAILED: /api/ai/install/status is missing 'repairNeeded' -- tempdoc 805 W-TRUTH's repair-needed consequence has not landed yet, so this leg must stay behind -IncludeUpgradeArrival until it does. Body=$($installStatusResp.Body)"
      Add-Content -LiteralPath $evidenceFile -Value "INFO: Upgrade-arrival leg -- /api/ai/install/status carries installedFully/pendingRegistryAdditions/repairNeeded."

      # (3) Activation must not regress to MODEL_PATH_REQUIRED -- the fixture contract covers
      # 'chat', so a model PATH always resolves via the contract fallback (resolveChatModelFromInstallContract).
      # Whatever OTHER error activation hits (missing variant exe, missing model bytes -- this
      # fixture ships no real model bytes on purpose) is acceptable; MODEL_PATH_REQUIRED specifically is not.
      $activateResp = Send-JsonPost -Client $client -Uri ("http://127.0.0.1:$upgradePort/api/ai/runtime/activate") -Json '{"variantId":"cuda12"}' -SessionToken $upgradeToken
      Assert ($activateResp.StatusCode -eq 200) "Upgrade-arrival leg FAILED: POST /api/ai/runtime/activate returned $($activateResp.StatusCode), expected 200. Body=$($activateResp.Body)"

      $activationDeadline = [DateTime]::UtcNow.AddSeconds(30)
      $activationState = $null
      $activationErrorCode = $null
      while ([DateTime]::UtcNow -lt $activationDeadline) {
        $runtimeStatusResp = Get-HttpBody -Client $client -Uri ("http://127.0.0.1:$upgradePort/api/ai/runtime/status")
        if ($runtimeStatusResp.StatusCode -eq 200) {
          $runtimeStatusJson = $runtimeStatusResp.Body | ConvertFrom-Json
          if ($runtimeStatusJson.activation) {
            $activationState = [string]$runtimeStatusJson.activation.state
            $activationErrorCode = [string]$runtimeStatusJson.activation.errorCode
            if ($activationState -ne "running" -and $activationState -ne "idle") { break }
          }
        }
        Add-BackoffSleep -Det $det -Reason "wait_for_activation_settle" -Ms 250
      }
      Assert ($activationErrorCode -ne "MODEL_PATH_REQUIRED") "Upgrade-arrival leg FAILED: activation errorCode is MODEL_PATH_REQUIRED -- this is round 11's regression class: the install contract covers 'chat', so a model path must resolve via the contract fallback even without real model bytes on disk. state=$activationState errorCode=$activationErrorCode"
      Add-Content -LiteralPath $evidenceFile -Value ("INFO: Upgrade-arrival leg -- activation settled state=" + $activationState + " errorCode=" + $activationErrorCode + " (not MODEL_PATH_REQUIRED, as expected).")

      Write-Host "PASS: Upgrade-arrival leg -- settings persisted, install-status shape present, activation did not regress to MODEL_PATH_REQUIRED."
    } finally {
      if ($upgradeProc -and -not $upgradeProc.HasExited) {
        try { & taskkill /PID $upgradeProc.Id /T /F | Out-Null } catch {}
        try { $upgradeProc.Kill() } catch {}
      }
      try { Remove-Item -LiteralPath $upgradeDataDir -Recurse -Force -ErrorAction SilentlyContinue } catch {}
    }
  } else {
    Add-Content -LiteralPath $evidenceFile -Value "INFO: Upgrade-arrival leg skipped (pass -IncludeUpgradeArrival to run; dormant until tempdoc 805 W-TRUTH lands repairNeeded)."
  }

} catch {
  $mainError = $_
  throw
} finally {
  # ---------------------------------------------------------------------------
  # EvidenceBundle v1 capture (optional; run BEFORE uninstall/teardown so backend is alive)
  # ---------------------------------------------------------------------------
  $bundleCaptureError = $null
  $bundlePath = $null
  $captureEnabled = $CaptureEvidenceBundle.IsPresent -or (-not [string]::IsNullOrWhiteSpace($env:CI))
  if ($captureEnabled -and $port -gt 0) {
    try {
      New-Item -ItemType Directory -Force -Path $resolvedEvidenceBundleOutRoot | Out-Null

      $apiBaseUrl = "http://127.0.0.1:$port"
      $enforceDet = [bool]$EnforceDeterminismBudget.IsPresent -or ($env:JUSTSEARCH_DETERMINISM_ENFORCE -eq "1")

      # Snapshot harness-level determinism budget as an attachment (best-effort; run-metadata.json is produced by the capture script).
      $detFile = Join-Path -Path $resolvedEvidenceDir -ChildPath ("determinism-harness-" + (Get-Date -Format "yyyyMMdd-HHmmss") + ".json")
      try { $null = (Enforce-DeterminismBudget -Det $det) } catch { }
      try { ($det | ConvertTo-Json -Depth 15) | Set-Content -LiteralPath $detFile -Encoding UTF8 } catch { $detFile = $null }

      $captureArgs = @(
        (Join-Path -Path $repoRoot -ChildPath "modules\\ui-web\\scripts\\capture-evidence-bundle.mjs"),
        "--scenario=$EvidenceScenario",
        "--api-base-url=$apiBaseUrl",
        "--out-root=$resolvedEvidenceBundleOutRoot",
        "--attach-label=harness"
      )

      if (-not [string]::IsNullOrWhiteSpace($EvidenceInclude)) {
        $captureArgs += "--include=$EvidenceInclude"
      }

      # NOTE: capture-evidence-bundle.mjs has no --enforce-determinism/--merge-determinism
      # options (passing either makes it print usage and exit 1 -- caught by the first CI run
      # of the installer_verify lane, 2026-08-04). Determinism enforcement happens via the
      # separate validate-determinism-budget-v1.mjs step below, gated by $enforceDet; the
      # harness determinism snapshot rides along as a plain attachment.
      foreach ($p in @($evidenceFile, $headlessStdout, $headlessStderr)) {
        if ($p -and (Test-Path -LiteralPath $p)) {
          $captureArgs += "--attach-file=$p"
        }
      }
      if ($detFile -and (Test-Path -LiteralPath $detFile)) {
        $captureArgs += "--attach-file=$detFile"
      }

      if ($mainError) {
        $captureArgs += "--external-status=failed"
        $captureArgs += ("--external-error=Installer NSIS verification failed: " + $mainError.Exception.Message)
      } else {
        $captureArgs += "--external-status=passed"
      }
      Add-Content -LiteralPath $evidenceFile -Value ("INFO: Capturing EvidenceBundle v1 (scenario=$EvidenceScenario) ...")
      $bundlePathRaw = & node @captureArgs
      $capExit = $LASTEXITCODE
      # stdout may carry more than one line (node warnings precede the path); the bundle path
      # is the last non-empty line. @() guards the single-line (non-array) case.
      $bundlePath = [string](@($bundlePathRaw) | Where-Object { -not [string]::IsNullOrWhiteSpace([string]$_) } | Select-Object -Last 1)
      $bundlePath = $bundlePath.Trim()
      Add-Content -LiteralPath $evidenceFile -Value ("INFO: EvidenceBundle dir: " + $bundlePath)
      if ([string]::IsNullOrWhiteSpace($bundlePath)) {
        throw "EvidenceBundle capture produced no bundle path (exit=$capExit)."
      }
      # NOTE: capture-evidence-bundle may exit non-zero when external-status=failed; that's expected.
      if (-not $mainError -and $capExit -ne 0) {
        throw "EvidenceBundle capture reported failed status (exit=$capExit). bundle=$bundlePath"
      }

      # Validate bundle invariants (hashing/layout/scope).
      $validator = Join-Path -Path $repoRoot -ChildPath "scripts\\evidence\\validate-evidencebundle-v1.mjs"
      & node $validator $bundlePath
      if ($LASTEXITCODE -ne 0) {
        throw "EvidenceBundle validator failed (exit=$LASTEXITCODE). bundle=$bundlePath"
      }

      # Validate determinism-budget invariants (policy enforcement; separate from structural EBv1 validation).
      $detValidator = Join-Path -Path $repoRoot -ChildPath "scripts\\evidence\\validate-determinism-budget-v1.mjs"
      & node $detValidator $bundlePath
      if ($LASTEXITCODE -ne 0) {
        if ($enforceDet) {
          throw "Determinism Budget validator failed (exit=$LASTEXITCODE). bundle=$bundlePath"
        } else {
          Add-Content -LiteralPath $evidenceFile -Value ("WARN: Determinism Budget validator failed (warn-only; re-run with -EnforceDeterminismBudget to gate). exit=$LASTEXITCODE bundle=$bundlePath")
        }
      }
      Add-Content -LiteralPath $evidenceFile -Value "INFO: EvidenceBundle v1 validation OK."
    } catch {
      $bundleCaptureError = $_.Exception.Message
      Add-Content -LiteralPath $evidenceFile -Value ("ERROR: EvidenceBundle capture/validation failed: " + $bundleCaptureError)
    }
  }

  if (-not $KeepInstallDir.IsPresent) {
    # Uninstall should be safe even when the backend is still running (DoD-4).
    # We intentionally run uninstall before killing the headless process so we can catch regressions
    # where the uninstaller leaks backend processes.
    if ($installedUninstaller) {
      try {
        $u = Start-Process -FilePath $installedUninstaller.FullName -ArgumentList @("/S") -Wait -PassThru
        if ($u.ExitCode -ne 0) {
          throw "Uninstaller exited with code $($u.ExitCode)"
        }
      } catch {
        Add-Content -LiteralPath $evidenceFile -Value ("ERROR: Uninstall failed: " + $_.Exception.Message)
        throw
      }

      # Assert uninstall did not leave any processes running from the install directory.
      $leaked = $null
      try {
        $leaked = @(
          Get-CimInstance Win32_Process -ErrorAction Stop |
            Where-Object { $_.ExecutablePath -and $_.ExecutablePath.StartsWith($InstallDir, [System.StringComparison]::OrdinalIgnoreCase) }
        )
      } catch {
        Add-Content -LiteralPath $evidenceFile -Value ("WARN: Unable to query leaked processes (best-effort): " + $_.Exception.Message)
        $leaked = $null
      }
      if ($leaked -ne $null -and $leaked.Count -gt 0) {
        Add-Content -LiteralPath $evidenceFile -Value ("ERROR: Uninstaller left running processes from install dir: " + $InstallDir)
        foreach ($p in $leaked) {
          Add-Content -LiteralPath $evidenceFile -Value ("ERROR: Leaked PID=" + $p.ProcessId + " EXE=" + $p.ExecutablePath)
        }
        # Cleanup so we don't strand processes on the build host.
        foreach ($p in $leaked) {
          try { Stop-Process -Id $p.ProcessId -Force -ErrorAction SilentlyContinue } catch {}
        }
        throw "Uninstaller leaked running processes from install dir. Evidence=$evidenceFile"
      }

      # Verify uninstall cleaned up registry (best-effort, non-blocking).
      $uninstRegKey = "HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\JustSearch"
      if (Test-Path $uninstRegKey) {
        Add-Content -LiteralPath $evidenceFile -Value "WARN: Uninstall left registry key: $uninstRegKey"
      } else {
        Add-Content -LiteralPath $evidenceFile -Value "INFO: Uninstall registry key removed OK."
      }

      # Verify shortcuts removed (best-effort, non-blocking).
      $startMenuLnk = Join-Path -Path ([Environment]::GetFolderPath("Programs")) -ChildPath "JustSearch\JustSearch.lnk"
      $desktopLnk = Join-Path -Path ([Environment]::GetFolderPath("Desktop")) -ChildPath "JustSearch.lnk"
      foreach ($lnk in @($startMenuLnk, $desktopLnk)) {
        if (Test-Path -LiteralPath $lnk) {
          Add-Content -LiteralPath $evidenceFile -Value ("WARN: Uninstall left shortcut: " + $lnk)
        }
      }

      # Verify Start Menu folder removed (best-effort, non-blocking).
      $startMenuFolder = Join-Path -Path ([Environment]::GetFolderPath("Programs")) -ChildPath "JustSearch"
      if (Test-Path -LiteralPath $startMenuFolder) {
        Add-Content -LiteralPath $evidenceFile -Value ("WARN: Uninstall left Start Menu folder: " + $startMenuFolder)
      }
    }
    try { Remove-Item -LiteralPath $InstallDir -Recurse -Force -ErrorAction SilentlyContinue } catch {}
  }

  # Teardown headless backend (kill process tree on Windows). This is a cleanup fallback.
  if ($headlessProc -and -not $headlessProc.HasExited) {
    try { & taskkill /PID $headlessProc.Id /T /F | Out-Null } catch {}
    try { $headlessProc.Kill() } catch {}
  }

  try { Remove-Item -LiteralPath $dataDir -Recurse -Force -ErrorAction SilentlyContinue } catch {}

  # If the main run succeeded, make EvidenceBundle capture failures actionable without masking the real failure.
  if ($captureEnabled -and $bundleCaptureError -and -not $mainError) {
    throw "EvidenceBundle capture/validation failed: $bundleCaptureError. Evidence=$evidenceFile"
  }
}


