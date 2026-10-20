<#
.SYNOPSIS
  Fixed-query-set before/after oracle for upgrade-from-release rounds.

.DESCRIPTION
  Runs a fixed set of scifact-corpus search queries against
  /api/knowledge/search, saves each raw response under
  <EvidenceDir>/oracle/<Label>-<queryId>.json, and writes/prints a
  <Label>-summary.txt with per-query HTTP status, hit count, and top-5
  result ids. Intended to be run once with -Label pre-upgrade before the
  candidate installer runs and again with -Label post-upgrade afterward, so
  the two summaries can be diffed directly.

  Port and session token are read from the owner-only canonical runtime manifest
  at run time (never hardcoded), so this works
  unmodified against any instance/port. See CLAUDE.md's session-token
  section: every non-public call needs X-JustSearch-Session or it 401s,
  which without this script's explicit status-code reporting can render as
  a false "index emptied" finding (round 10 nearly filed exactly that).

.PARAMETER Label
  A short tag distinguishing this run, e.g. "pre-upgrade" / "post-upgrade".
  Used as the output-file prefix.

.PARAMETER EvidenceDir
  Root evidence directory; results are written under
  <EvidenceDir>/oracle/.

.EXAMPLE
  .\oracle.ps1 -Label pre-upgrade
  .\oracle.ps1 -Label post-upgrade

.NOTES
  Provenance: adopted from round 11 (tmp/sandbox-round11/share/oracle.ps1)
  per tempdoc 805 Part E / G.5 -- round 11's retrospective called this "the
  core instrument of upgrade-from-release mode", previously described only
  in prose and rewritten by every round that needed it.
#>
param([string]$Label = "unlabeled", [string]$EvidenceDir = ".\evidence")

$mf = "$env:APPDATA\io.justsearch.shell\runtime\manifest.json"
$port = (Get-Content $mf -Raw | ConvertFrom-Json).head.apiPort
$base = "http://127.0.0.1:$port"
$tok = [string](Get-Content -LiteralPath $mf -Raw | ConvertFrom-Json).head.sessionToken
$H = @{ "X-JustSearch-Session" = $tok }

$queries = @(
  @{ id = "q1"; q = "RNA interference gene silencing siRNA" },
  @{ id = "q2"; q = "dendritic arbor compensatory growth synaptic input" },
  @{ id = "q3"; q = "T cell immune response infection" }
)

$outDir = Join-Path $EvidenceDir "oracle"
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

$summary = @()
foreach ($item in $queries) {
  $body = @{ query = $item.q; limit = 10 } | ConvertTo-Json -Compress
  $status = $null; $raw = $null
  try {
    $resp = Invoke-WebRequest -UseBasicParsing -Method Post -Uri "$base/api/knowledge/search" `
      -Headers $H -ContentType "application/json" -Body $body
    $status = [int]$resp.StatusCode
    $raw = $resp.Content
  } catch {
    $r = $_.Exception.Response
    if ($r) { $status = [int]$r.StatusCode; $sr = New-Object IO.StreamReader($r.GetResponseStream()); $raw = $sr.ReadToEnd() }
    else { $status = -1; $raw = $_.Exception.Message }
  }
  $file = Join-Path $outDir "$Label-$($item.id).json"
  Set-Content -Path $file -Value $raw -Encoding utf8

  $ids = @(); $n = 0
  if ($status -eq 200) {
    try { $j = $raw | ConvertFrom-Json; $n = @($j.results).Count; $ids = @($j.results | Select-Object -First 5 -ExpandProperty id) } catch {}
  }
  $summary += "[$Label] $($item.id) HTTP=$status hits=$n top5=$($ids -join ' | ')"
  $summary += "         query: $($item.q)"
}

$sumFile = Join-Path $outDir "$Label-summary.txt"
$summary | Set-Content -Path $sumFile -Encoding utf8
$summary
"saved -> $sumFile"
