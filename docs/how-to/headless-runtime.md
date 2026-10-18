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
