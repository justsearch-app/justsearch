---
title: Run JustSearch as a headless runtime (Headless / MCP Lite)
type: how-to
status: draft
description: "Launch the JustSearch backend as a local loopback service without the desktop shell, selecting an install/runtime mode."
---

# Run JustSearch as a headless runtime (Headless / MCP Lite)

## What this does

Runs the JustSearch backend (one Engine JVM with API and index halves) as a local, loopback-only
service **without** the Tauri desktop shell, in a chosen install/runtime **mode** (tempdoc 657):

| Mode | What it is | Model tiers |
|------|------------|-------------|
| `full-desktop` | The complete desktop experience (default; used by the desktop app). | retrieval + LLM |
| `headless` | The backend as a local service, no desktop UI. | retrieval + LLM |
| `mcp-lite` | Fast retrieval for agent developers, no LLM. | retrieval only |

The mode is a single launch-time value; it drives which model packs "Install AI" fetches
(the `mcp-lite` mode skips the LLM + CUDA-runtime tiers) and is reported on the runtime
manifest so callers do not have to guess.

## Launch it

From an installed bundle (the launcher sits next to `ui-headless.jar`):

```bat
justsearch-headless.cmd mcp-lite
```

On Linux, from an installed Gradle distribution, use its native launcher:

```sh
JUSTSEARCH_MODE=mcp-lite /absolute/path/to/ui/bin/ui
```

On Windows, the PowerShell form is:

```powershell
.\justsearch-headless.ps1 -Mode mcp-lite
```

To reuse the desktop app's index, settings, and models, quit the desktop app first, then
set both roots to its data folder before launching. Do not run two Engines against the same data:

```bat
set JUSTSEARCH_DATA_DIR=%APPDATA%\io.justsearch.shell
set JUSTSEARCH_HOME=%APPDATA%\io.justsearch.shell
```

On Linux, set the corresponding paths in Bash before invoking the launcher:

```sh
export JUSTSEARCH_DATA_DIR="${XDG_DATA_HOME:-$HOME/.local/share}/io.justsearch.shell"
export JUSTSEARCH_HOME="$JUSTSEARCH_DATA_DIR"
```

The desktop shell retains its platform app-data directory. On Linux its private
test location is selected with `XDG_DATA_HOME`; `JUSTSEARCH_DATA_DIR` remains a
headless/dev override and does not relocate the desktop shell's saved data or
its delete-data target. The shell passes its selected directory to the Engine,
which publishes the runtime manifest there for the UI/tools.

### From a dev checkout

```bash
node scripts/lib/tool-invoker.cjs gradle :modules:ui:runHeadless -Pmode=mcp-lite
```

`-Pmode` sets `-Djustsearch.mode`; omit it for `full-desktop`. The equivalent environment
variable is `JUSTSEARCH_MODE` (see `docs/reference/configuration/environment-variables.md`).

## Confirm the active mode

The launcher prints `JUSTSEARCH_API_PORT=<port>` on stdout and records `head.apiPort` in
`<data>/runtime/manifest.json`. Bundle launchers default to `8080`, falling back to a free port
if taken; `JUSTSEARCH_API_PORT` overrides the requested port. The Gradle `runHeadless` task
defaults to `33221`. Substitute the actual port below (these examples use `33221`):

```bash
curl -s http://127.0.0.1:33221/api/runtime/manifest | jq '.mode'
# { "intent": "mcp-lite", "realized": "retrieval-only" }
```

- `intent` is the mode you launched with.
- `realized` is the coarse capability actually up: `full` (retrieval + LLM ready),
  `retrieval-only` (retrieval up, LLM not present/needed), or `degraded` (index half not ready).
  `realized` reflects reality, so it can differ from `intent` while things start up or if a
  component is down.

To see the download weight a mode implies before installing:

```bash
curl -s http://127.0.0.1:33221/api/ai/install/plan-preview | jq '.tiers'
```

## Notes

- The service binds to `127.0.0.1` only. These launchers do not set production mode, so they
  do not mint or enforce the desktop app's per-boot mutation token by default. Host/Origin
  filters still apply; local callers can mutate without that token. Use these launchers
  only where all local callers are trusted. The desktop shell explicitly enables production mode.
- Agent clients connect over the production MCP endpoint (`POST /mcp`); which tools a lite
  deployment exposes is governed separately (see the MCP production server reference).
- This is a launch mode, not a separate build — the same `ui-headless.jar` runs every mode.

## Restart after a crash

Headless runs one Engine JVM. It has no desktop supervisor, cooldown retry, or
"Restart JustSearch" button. Run it under an OS supervisor if it must recover
unattended. Exit **2** means another process holds the data directory; leave that
stopped and resolve the lock instead of repeatedly launching another Engine.

In the source checkout, build the installed launcher with
`./gradlew :modules:ui:installDist -PskipWebBuild=true` (a backend-only build,
as in the [UI guide](use-ui.md)). For a Linux user service, save this as
`~/.config/systemd/user/justsearch.service`, replacing the absolute paths:

```ini
[Unit]
Description=JustSearch headless Engine

[Service]
Type=simple
Environment="JUSTSEARCH_MODE=headless"
Environment="JUSTSEARCH_DATA_DIR=/absolute/path/to/engine-data"
Environment="JUSTSEARCH_HOME=/absolute/path/to/ai-assets"
Environment="JAVA_OPTS=-Djustsearch.prod=true -XX:ErrorFile=\"/absolute/path/to/engine-data/crashes/hs_err_pid%%p.log\" -XX:+ExitOnOutOfMemoryError"
WorkingDirectory=/absolute/path/to/justsearch
ExecStartPre="/usr/bin/mkdir" -p "/absolute/path/to/engine-data/crashes"
ExecStart="/absolute/path/to/justsearch/modules/ui/build/install/ui/bin/ui"
Restart=on-failure
SuccessExitStatus=143
RestartPreventExitStatus=2
RestartSec=30

[Install]
WantedBy=default.target
```

Run `systemctl --user daemon-reload` and
`systemctl --user enable --now justsearch.service`. A clean exit stays stopped.
The launcher returns 143 on SIGTERM; `SuccessExitStatus=143` makes
`systemctl --user stop justsearch.service` leave it inactive rather than failed.
For unattended operation after logout and across boots, run
`loginctl enable-linger <user>` for the account running the service.

Use `journalctl --user -u justsearch.service` for exit status and restart history.
Exit status 2 means the data folder is held by another process.
The Engine's own messages are in
`build/headless-data/logs/engine.log` under `WorkingDirectory`
(`/absolute/path/to/justsearch/build/headless-data/logs/engine.log` above), even
with `JUSTSEARCH_DATA_DIR` set: logging initializes before that environment
setting is mirrored to the JVM's `justsearch.data.dir` property.

`-Djustsearch.prod=true` selects production mode. `WorkingDirectory` must point
at a source checkout: the Gradle-installed distribution lacks the `SSOT/`
catalogs needed for search. This is a known limitation of the Gradle install.
The crash directory is created before each start; JVM fatal reports land there
as `hs_err_pid<PID>.log`. Headless does not keep heap dumps.

In a systemd unit, `%%p` passes the JVM's literal `%p` placeholder. The quoted
`Environment` assignments and escaped quotes inside `JAVA_OPTS` preserve paths
containing spaces. Command paths and arguments in `ExecStartPre` and `ExecStart`
are quoted too. `WorkingDirectory` takes one path, including literal spaces;
leave that value unquoted and do not escape its spaces.

On Windows, save this wrapper as `run-justsearch.ps1`. It restarts after a
non-zero Engine exit other than 2, waiting 30 seconds and allowing at most three
restarts in a rolling 30-minute window; on exit 0 or 2, or when the budget is
spent, it stops with the Engine's last exit code.

```powershell
$ErrorActionPreference = 'Stop'
$env:JUSTSEARCH_DATA_DIR = 'C:\JustSearch\data'
$env:JUSTSEARCH_HOME = 'C:\JustSearch\assets'
$restarts = @()
while ($true) {
    & 'C:\Program Files\JustSearch\justsearch-headless.ps1' -Mode headless
    $engineExit = $LASTEXITCODE
    if ($engineExit -eq 0 -or $engineExit -eq 2) { exit $engineExit }
    $now = [DateTime]::UtcNow
    $restarts = @($restarts | Where-Object { $_ -gt $now.AddMinutes(-30) })
    if ($restarts.Count -ge 3) { exit $engineExit }
    $restarts += $now
    Start-Sleep -Seconds 30
}
```

Run the wrapper at logon or startup through Task Scheduler with
`powershell.exe -NoProfile -ExecutionPolicy Bypass -File "C:\path with spaces\run-justsearch.ps1"`.
Select **Do not start a new instance** and leave Task Scheduler's
restart-on-failure setting off; the wrapper owns retries.
The Windows launcher does not set a crash-log location, so JVM fatal logs land
in its working directory.

Poison-file quarantine belongs inside the Engine and does not depend on the
shell. Its implementation is a separate change; these OS restart policies do
not themselves identify or quarantine a crashing file.
