<#
.SYNOPSIS
  Consume POST /api/chat/ask as Server-Sent Events (SSE), not JSON.

.DESCRIPTION
  CLAUDE.md documents at length that /api/chat/ask streams SSE and that
  piping the raw response through ConvertFrom-Json looks exactly like an
  empty answer -- but nothing staged actually read the stream until this
  script existed. It opens a raw HttpWebRequest with Accept:
  text/event-stream, reads the response line by line, and reports HTTP
  status, elapsed time, frame-line count, and a summary of event types
  (event:* lines) alongside the raw captured frames.

  Port is discovered from the runtime manifest at launch time (never
  hardcoded), so this works unmodified against any instance/port.

.PARAMETER Question
  The question text to POST as {"question": "..."}.

.PARAMETER Out
  Optional path to also save the raw captured stream to disk.

.PARAMETER TimeoutSec
  Client-side read timeout in seconds (default 180).

.EXAMPLE
  .\chat-ask.ps1 -Question "What is the main finding?" -Out .\evidence\chat\post-upgrade-rag.txt

.NOTES
  Provenance: adopted from round 11 (tmp/sandbox-round11/share/chat-ask.ps1)
  per tempdoc 805 Part E / G.5 -- round 11's retrospective named this a
  "strong candidate for staging" because every prior round had to write it
  from scratch to avoid mistaking an SSE stream for an empty JSON answer.
#>
param(
  [Parameter(Mandatory=$true)][string]$Question,
  [string]$Out = "",
  [int]$TimeoutSec = 180
)

$mf = "$env:APPDATA\io.justsearch.shell\runtime\manifest.json"
$port = (Get-Content $mf -Raw | ConvertFrom-Json).head.apiPort
$base = "http://127.0.0.1:$port"
$tok = [string](Get-Content -LiteralPath $mf -Raw | ConvertFrom-Json).head.sessionToken

$body = @{ question = $Question } | ConvertTo-Json -Compress
$bytes = [Text.Encoding]::UTF8.GetBytes($body)

$req = [Net.HttpWebRequest]::Create("$base/api/chat/ask")
$req.Method = "POST"
$req.ContentType = "application/json"
$req.Accept = "text/event-stream"
$req.Headers.Add("X-JustSearch-Session", $tok)
$req.Timeout = $TimeoutSec * 1000
$req.ReadWriteTimeout = $TimeoutSec * 1000
$rs = $req.GetRequestStream(); $rs.Write($bytes, 0, $bytes.Length); $rs.Close()

$sw = [Diagnostics.Stopwatch]::StartNew()
$lines = New-Object Collections.Generic.List[string]
$status = -1
try {
  $resp = $req.GetResponse()
  $status = [int]$resp.StatusCode
  $reader = New-Object IO.StreamReader($resp.GetResponseStream())
  while (-not $reader.EndOfStream) {
    $line = $reader.ReadLine()
    $lines.Add($line)
    if ($sw.Elapsed.TotalSeconds -gt $TimeoutSec) { $lines.Add("[[client timeout]]"); break }
  }
  $reader.Close(); $resp.Close()
} catch {
  $r = $_.Exception.Response
  if ($r) {
    $status = [int]$r.StatusCode
    $sr = New-Object IO.StreamReader($r.GetResponseStream())
    $lines.Add("[[error body]] " + $sr.ReadToEnd())
  } else { $lines.Add("[[exception]] " + $_.Exception.Message) }
}
$sw.Stop()

$raw = $lines -join "`n"
"HTTP status : $status"
"elapsed     : $([math]::Round($sw.Elapsed.TotalSeconds,1))s"
"frame lines : $($lines.Count)"
$evts = $lines | Where-Object { $_ -like 'event:*' } | ForEach-Object { $_.Substring(6).Trim() }
"event types : $(($evts | Group-Object | ForEach-Object { "$($_.Name)x$($_.Count)" }) -join ', ')"

if ($Out) {
  New-Item -ItemType Directory -Force -Path (Split-Path $Out) | Out-Null
  $raw | Set-Content -Path $Out -Encoding utf8
  "saved       : $Out"
}
$raw
