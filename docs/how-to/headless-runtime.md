---
title: Run JustSearch as a headless runtime (Headless / MCP Lite)
type: how-to
status: draft
description: "Launch the JustSearch backend as a local loopback service without the desktop shell, selecting an install/runtime mode."
---

# Run JustSearch as a headless runtime (Headless / MCP Lite)

## What this does

Runs the JustSearch backend (the Head process + its Worker) as a local, loopback-only
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
JAVA_OPTS="-Djustsearch.prod=true -Djustsearch.repo.root=/absolute/path/to/ui" \
  JUSTSEARCH_MODE=mcp-lite /absolute/path/to/ui/bin/ui
```

On Windows, the PowerShell form is:

```powershell
.\justsearch-headless.ps1 -Mode mcp-lite
```

To reuse an existing install's data and models, set these before launching:

```bat
set JUSTSEARCH_DATA_DIR=%LOCALAPPDATA%\JustSearch\data
set JUSTSEARCH_HOME=%APPDATA%\io.justsearch.shell
```

On Linux, set the corresponding paths in Bash before invoking the launcher:

```sh
export JUSTSEARCH_DATA_DIR=/absolute/path/to/engine-data
export JUSTSEARCH_HOME=/absolute/path/to/ai-assets
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

The runtime manifest reports the mode. With the API port known (default dev port `33221`):

```bash
curl -s http://127.0.0.1:33221/api/runtime/manifest | jq '.mode'
# { "intent": "mcp-lite", "realized": "retrieval-only" }
```

- `intent` is the mode you launched with.
- `realized` is the coarse capability actually up: `full` (retrieval + LLM ready),
  `retrieval-only` (retrieval up, LLM not present/needed), or `degraded` (worker not ready).
  `realized` reflects reality, so it can differ from `intent` while things start up or if a
  component is down.

To see the download weight a mode implies before installing:

```bash
curl -s http://127.0.0.1:33221/api/ai/install/plan-preview | jq '.tiers'
```

## Notes

- The service binds to `127.0.0.1` only, like the desktop app.
- Agent clients connect over the production MCP endpoint (`POST /mcp`); which tools a lite
  deployment exposes is governed separately (see the MCP production server reference).
- This is a launch mode, not a separate build — the same `ui-headless.jar` runs every mode.

## Restart after a crash

Headless runs one Engine JVM. It has no desktop supervisor, cooldown retry, or
"Restart JustSearch" button. Run it under an OS supervisor if it must recover
unattended. Exit **2** means another process holds the data directory; leave that
stopped and resolve the lock instead of repeatedly launching another Engine.

For a Linux user service, save this as
`~/.config/systemd/user/justsearch.service`, replacing the absolute paths:

```ini
[Unit]
Description=JustSearch headless Engine

[Service]
Type=simple
Environment=JUSTSEARCH_MODE=headless
Environment=JUSTSEARCH_DATA_DIR=/absolute/path/to/engine-data
Environment=JUSTSEARCH_HOME=/absolute/path/to/ai-assets
Environment="JAVA_OPTS=-Djustsearch.prod=true -Djustsearch.repo.root=\"/absolute/path/to/ui\" -XX:ErrorFile=\"/absolute/path/to/engine-data/crashes/hs_err_pid%%p.log\" -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=\"/absolute/path/to/engine-data/crashes/\" -XX:+ExitOnOutOfMemoryError"
ExecStartPre=/usr/bin/mkdir -p /absolute/path/to/engine-data/crashes
ExecStart=/absolute/path/to/ui/bin/ui
Restart=on-failure
RestartPreventExitStatus=2
RestartSec=30

[Install]
WantedBy=default.target
```

Run `systemctl --user daemon-reload` and
`systemctl --user enable --now justsearch.service`. A clean exit stays stopped.
Use `journalctl --user -u justsearch.service` to inspect a failure.

`-Djustsearch.prod=true` selects production mode. `justsearch.repo.root` points
the configuration loader at the installed distribution, as the desktop shell
does; it requires no repository checkout or particular working directory. The
crash directory is created before each start. JVM fatal reports land there as `hs_err_pid<PID>.log`,
and heap OOMs leave `java_pid<PID>.hprof` before exiting 3. In a systemd unit,
`%%p` passes the JVM's literal `%p` placeholder; the escaped quotes preserve paths
containing spaces in `JAVA_OPTS`. Quote paths with spaces in `ExecStartPre` and
`ExecStart` too. Heap dumps are not included automatically in diagnostics exports.

On Windows, Task Scheduler's **Restart the task if it fails** setting needs a
launcher that reports exit 2 as success, so the scheduler leaves a locked profile
alone. Save the following as a `.ps1` file and use it as the task's action with
`powershell.exe -NoProfile -File "C:\path with spaces\run-justsearch.ps1"`:

```powershell
$env:JUSTSEARCH_DATA_DIR = 'C:\JustSearch\data'
$env:JUSTSEARCH_HOME = 'C:\JustSearch\assets'
& 'C:\Program Files\JustSearch\justsearch-headless.ps1' -Mode headless
$engineExit = $LASTEXITCODE
if ($engineExit -eq 2) { exit 0 }
exit $engineExit
```

Configure the task to restart after failure (for example, every minute, up to
three attempts) and **Do not start a new instance** if it is already running.
A Windows service host can use the same policy: restart after failure, but
exclude Engine exit 2. Point it at the Engine launcher and preserve the exit code.

Poison-file quarantine belongs inside the Engine and does not depend on the
shell. Its implementation is a separate change; these OS restart policies do
not themselves identify or quarantine a crashing file.
